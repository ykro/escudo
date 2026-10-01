"""Damage assessor: photos in, structured assessment out.

Gemini reads the photos and must answer with the `DamageAssessment` schema
(structured output), so the rest of the system never parses free text.
"""

from __future__ import annotations

import io
import logging
from typing import Protocol

from PIL import Image, ImageOps

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
Vehículo asegurado: {vehicle}. Descripción del cliente: {description}"""


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
        retry = genai.types.HttpRetryOptions(attempts=3, initial_delay=1.0, http_status_codes=[429, 500, 502, 503, 504])
        # A stalled call is cut at 45 s and retried: on stage a retry beats an open-ended wait.
        self._client = genai.Client(
            vertexai=True, project=project, location=location,
            http_options=genai.types.HttpOptions(retry_options=retry, timeout=45_000),
        )
        self._model = model

    async def assess(self, photos: list[tuple[bytes, str]], vehicle: str, description: str) -> DamageAssessment:
        types = self._genai.types
        # One multimodal call with every photo, each downscaled first.
        parts = [types.Part.from_bytes(data=for_assessment(data), mime_type="image/jpeg") for data, _ in photos]
        parts.append(types.Part.from_text(text=PROMPT.format(vehicle=vehicle, description=description)))
        response = await self._client.aio.models.generate_content(
            model=self._model,
            contents=[types.Content(role="user", parts=parts)],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                response_schema=DamageAssessment,
                temperature=0.2,
                # Measured: 3 photos took 11-30 s at LOW vs up to 100 s at the default level.
                thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.LOW),
                media_resolution=types.MediaResolution.MEDIA_RESOLUTION_MEDIUM,
            ),
        )
        if isinstance(response.parsed, DamageAssessment):
            assessment = response.parsed
        else:
            assessment = DamageAssessment.model_validate_json(response.text or "")
        low, high = sorted(assessment.cost_range_usd)
        assessment.cost_range_usd = [round(low), round(high)]
        return assessment
