"""attach_claim_photos — store the photos and run the damage assessor. Automatic."""

from __future__ import annotations

import asyncio
import base64
import binascii
import time
from typing import Any

from pydantic import BaseModel, Field

from ..errors import InvalidInput
from ._base import Capability, Context, advance, usd

MAX_PHOTO_BYTES = 8 * 1024 * 1024
_SEVERITY = {"minor": "leve", "moderate": "moderado", "severe": "severo"}


class Input(BaseModel):
    claim_id: str
    photos: list[str] = Field(
        min_length=1,
        max_length=6,
        description="Photos of the damage: base64-encoded JPEG/PNG/WebP (data: URLs accepted) or gs:// URIs in the claims bucket",
    )


def sniff_mime(data: bytes) -> str:
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    raise InvalidInput("unsupported_photo", "Solo aceptamos fotos JPEG, PNG o WebP.")


async def _load(photo: str, ctx: Context) -> bytes:
    if photo.startswith("gs://"):
        try:
            return await ctx.services.blobs.get(photo)
        except (PermissionError, KeyError) as exc:
            raise InvalidInput("photo_not_found", "No pude leer esa foto del almacenamiento de Escudo.") from exc
    if photo.startswith("data:"):
        photo = photo.split(",", 1)[-1]
    try:
        return base64.b64decode(photo, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidInput("invalid_photo", "La foto debe venir en base64 o como gs:// URI.") from exc


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    loaded: list[tuple[bytes, str]] = []
    for photo in inp.photos:
        data = await _load(photo, ctx)
        if len(data) > MAX_PHOTO_BYTES:
            raise InvalidInput("photo_too_large", "Cada foto debe pesar menos de 8 MB.")
        loaded.append((data, sniff_mime(data)))

    first = len(claim.photos) + 1
    uploads = [
        ctx.services.blobs.put(f"claims/{claim.claim_id}/{first + i}.{mime.split('/')[1].replace('jpeg', 'jpg')}", data, mime)
        for i, (data, mime) in enumerate(loaded)
    ]
    v = policy.vehicle
    started = time.perf_counter()
    # Storing the originals and assessing run side by side; the assessor downscales for the model.
    *stored, assessment = await asyncio.gather(
        *uploads, ctx.services.assessor.assess(loaded, f"{v.make} {v.model} {v.year}", claim.description)
    )
    seconds = round(time.perf_counter() - started, 1)
    uris = [*claim.photos, *stored]
    await ctx.repo.update_claim(
        claim.claim_id,
        {"photos": uris, "assessment": assessment.model_dump(mode="json"), "status": advance(claim.status, "assessed")},
    )
    low, high = assessment.cost_range_usd
    await ctx.services.events.emit(
        actor="damage_assessor",
        surface=ctx.caller.surface,
        kind="message",
        summary=f"Daño {_SEVERITY[assessment.severity]} en {' y '.join(p.name for p in assessment.parts[:2])} · {usd(low)}–{usd(high)}",
        run_id=ctx.caller.timeline_run_id,
        claim_id=claim.claim_id,
        tool="attach_claim_photos",
        data={"severity": assessment.severity, "cost_range_usd": [low, high], "drivable": assessment.drivable,
              "photos": len(loaded), "assessment_seconds": seconds},
    )
    return {**assessment.model_dump(mode="json"), "photos_assessed": len(loaded), "assessment_seconds": seconds}


def summarize(result: dict[str, Any]) -> str:
    low, high = result["cost_range_usd"]
    return f"Evaluación lista: daño {_SEVERITY[result['severity']]}, {usd(low)}–{usd(high)}"


CAPABILITY = Capability(
    name="attach_claim_photos",
    description="Attach photos of the damage to a claim and run the damage assessor. Returns damaged parts, severity, cost range (USD) and whether the car is drivable.",
    Input=Input,
    run=run,
    summarize=summarize,
)
