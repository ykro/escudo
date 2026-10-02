"""Damage assessor: photos in, structured assessment out.

Gemini reads the photos and must answer with the `DamageAssessment` schema
(structured output), so the rest of the system never parses free text.
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import time
from typing import Any, Awaitable, Callable, Protocol, TypeVar

from PIL import Image, ImageOps

from .errors import EscudoError
from .models import DamageAssessment, DamagedPart

log = logging.getLogger(__name__)

PROMPT = """Eres el perito de daños de Escudo, una aseguradora de autos en Guatemala.
Analiza las fotos de un choque y devuelve:
- parts: cada pieza dañada (nombre en español) y el daño visible.
- severity: minor si es cosmético o de carrocería leve, moderate si hay piezas a reemplazar,
  severe si hay daño estructural, mecánico o bolsas de aire activadas.
- cost_range_usd: [mínimo, máximo] del costo de reparación en USD a precios de talleres
  de Ciudad de Guatemala (repuestos + mano de obra + pintura).
- drivable: si el auto puede circular con seguridad.
- summary: una sola oración en español para la clienta, cálida y clara, en tuteo (tú, nunca usted),
  sin saludos ni fórmulas como «Estimado cliente». Ejemplo: «Tu auto tiene daño leve atrás y puede circular.»
Si una foto no muestra un auto, ignórala. No inventes daños que no se ven.
Vehículo asegurado: {vehicle}. Descripción de la clienta: {description}"""


# Measured 2026-09-30 (3 photos, one call): phone-size JPEGs (~1.5 MB, 4000 px) took 86-90 s;
# the same photos at 1024 px took 6-10 s with the same verdict. The model does not need more.
ASSESS_MAX_PX = 1024


def for_assessment(data: bytes) -> bytes:
    """Downscale + re-encode a photo for the model (originals are kept in Cloud Storage)."""
    image = ImageOps.exif_transpose(Image.open(io.BytesIO(data))).convert("RGB")
    image.thumbnail((ASSESS_MAX_PX, ASSESS_MAX_PX))
    out = io.BytesIO()
    image.save(out, "JPEG", quality=80, optimize=True)
    return out.getvalue()


# Normal answers take 5-10 s; rare ones took 16-115 s (2026-10-01, Vertex `global`, the only region that
# serves the model). A new identical call starts every HEDGE_AFTER_SECONDS (or as soon as one fails), up
# to HEDGE_ATTEMPTS; the first answer wins and the rest are cancelled.
# Measured 2026-10-01 afternoon: ~30% of calls stalled past 60 s while the rest took 4-7 s, independent
# of request size. Hence frequent, independent attempts (each on its own connection).
HEDGE_AFTER_SECONDS = float(os.environ.get("ASSESS_HEDGE_SECONDS", "5"))
HEDGE_ATTEMPTS = int(os.environ.get("ASSESS_HEDGE_ATTEMPTS", "4"))
DEADLINE_SECONDS = float(os.environ.get("ASSESS_DEADLINE_SECONDS", "45"))

T = TypeVar("T")


async def hedged(
    call: Callable[[], Awaitable[T]], hedge_after: float, deadline: float, attempts: int = HEDGE_ATTEMPTS
) -> tuple[T, dict[str, Any]]:
    """Run `call`; start another attempt every `hedge_after` s while none has answered (or right away
    when one fails), up to `attempts`. First success wins, the others are cancelled; nothing after
    `deadline` s. Returns (result, timing info)."""
    started = time.perf_counter()
    tasks: dict[asyncio.Task[T], int] = {}
    launched = 0
    next_launch = 0.0
    last_error: BaseException | None = None

    def launch() -> None:
        nonlocal launched, next_launch
        launched += 1
        tasks[asyncio.ensure_future(call())] = launched
        next_launch = (time.perf_counter() - started) + hedge_after

    try:
        launch()
        while tasks:
            elapsed = time.perf_counter() - started
            if elapsed >= deadline:
                break
            wait_until = min(deadline, next_launch) if launched < attempts else deadline
            done, _ = await asyncio.wait(tasks, timeout=max(wait_until - elapsed, 0), return_when=asyncio.FIRST_COMPLETED)
            failed = False
            for task in done:
                attempt = tasks.pop(task)
                if task.exception() is None:
                    return task.result(), {"seconds": round(time.perf_counter() - started, 2), "hedged": launched > 1, "winner": attempt}
                last_error, failed = task.exception(), True
            if launched < attempts and (failed or time.perf_counter() - started >= next_launch):
                launch()
        raise TimeoutError(f"no answer in {deadline:.0f} s after {launched} attempts") from last_error
    finally:
        for task in tasks:
            task.cancel()


class AssessmentUnavailable(EscudoError):
    status = 503


class Assessor(Protocol):
    async def assess(self, photos: list[tuple[bytes, str]], vehicle: str, description: str) -> DamageAssessment: ...


class MockAssessor:
    """Deterministic assessment for tests and offline rehearsals (MOCK_AI=1)."""

    async def assess(self, photos: list[tuple[bytes, str]], vehicle: str, description: str) -> DamageAssessment:
        return DamageAssessment(
            parts=[
                DamagedPart(name="parachoques trasero", damage="abolladura y pintura raspada"),
                DamagedPart(name="luz trasera izquierda", damage="mica quebrada"),
            ],
            severity="minor",
            cost_range_usd=[650.0, 1100.0],
            drivable=True,
            summary="Daño leve en la parte trasera; el auto puede circular con precaución.",
        )


class GeminiAssessor:
    def __init__(self, project: str, location: str, model: str) -> None:
        from google import genai

        self._genai = genai
        # Transient 429/5xx are retried inside each call; slowness is handled by `hedged`.
        retry = genai.types.HttpRetryOptions(attempts=2, initial_delay=0.5, http_status_codes=[429, 500, 502, 503, 504])
        options = genai.types.HttpOptions(retry_options=retry, timeout=int(DEADLINE_SECONDS * 1000))
        # A small pool of clients = separate connections, so a stalled connection cannot stall the backups.
        self._clients = [genai.Client(vertexai=True, project=project, location=location, http_options=options)
                         for _ in range(HEDGE_ATTEMPTS)]
        self._model = model

    async def assess(self, photos: list[tuple[bytes, str]], vehicle: str, description: str) -> DamageAssessment:
        types = self._genai.types
        # One multimodal call with every photo, each downscaled first.
        parts = [types.Part.from_bytes(data=for_assessment(data), mime_type="image/jpeg") for data, _ in photos]
        parts.append(types.Part.from_text(text=PROMPT.format(vehicle=vehicle, description=description)))
        config = types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=DamageAssessment,
                temperature=0.2,
                # Measured: 3 photos took 11-30 s at LOW vs up to 100 s at the default level.
                thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.LOW),
                media_resolution=types.MediaResolution.MEDIA_RESOLUTION_MEDIUM,
        )

        attempt = iter(self._clients)

        def call():  # noqa: ANN202
            client = next(attempt, self._clients[0])
            return client.aio.models.generate_content(
                model=self._model, contents=[types.Content(role="user", parts=parts)], config=config
            )

        try:
            response, timing = await hedged(call, HEDGE_AFTER_SECONDS, DEADLINE_SECONDS)
        except Exception as exc:
            log.warning("assessment_timing photos=%d failed: %s", len(photos), exc)
            raise AssessmentUnavailable(
                "assessment_unavailable", "No pudimos evaluar las fotos en este momento. Intenta de nuevo."
            ) from exc
        log.info("assessment_timing photos=%d seconds=%.2f hedged=%s winner=%d",
                 len(photos), timing["seconds"], timing["hedged"], timing["winner"])
        if isinstance(response.parsed, DamageAssessment):
            assessment = response.parsed
        else:
            assessment = DamageAssessment.model_validate_json(response.text or "")
        low, high = sorted(assessment.cost_range_usd)
        assessment.cost_range_usd = [round(low), round(high)]
        return assessment
