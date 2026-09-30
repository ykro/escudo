"""Tow partner agent ("Grúas Aliadas"): picks the provider that arrives first.

Reached only over A2A. Input: JSON with the claim location. Output: JSON TowDispatch.
"""

from __future__ import annotations

import math
from typing import Any

from a2a.types import AgentSkill
from google.adk.agents import BaseAgent, LlmAgent
from google.adk.tools import ToolContext
from google.genai import types


from ..config import Settings
from ..models import TowDispatch
from ..repository import Repository
from .hosting import build_card
from .llm import gemini
from .scripted import ScriptedAgent

SKILL_ID = "tow_dispatch"
PREFIX = "/a2a/tow"
DEFAULT_LOCATION = {"lat": 14.6349, "lng": -90.5069}  # Ciudad de Guatemala centro
ROAD_FACTOR = 1.4  # streets are not straight lines

INSTRUCTION = """Eres el agente de despacho de Grúas Aliadas, socio de la aseguradora Escudo.
Recibes un JSON con claim_id y location {lat, lng, label} de un auto accidentado.
1. Llama a find_nearest_tow con lat y lng (si no vienen, usa lat 14.6349, lng -90.5069).
2. El resultado de la herramienta es la respuesta: no inventes datos."""


def _km(a: dict[str, float], b: dict[str, float]) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (a["lat"], a["lng"], b["lat"], b["lng"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(h))


def make_tool(repo: Repository):  # noqa: ANN201
    async def find_nearest_tow(lat: float, lng: float, tool_context: ToolContext | None = None) -> dict[str, Any]:
        """Find the tow provider with the shortest ETA to a location in Guatemala City.

        Args:
            lat: Latitude of the vehicle.
            lng: Longitude of the vehicle.
        """
        if tool_context is not None:
            # The tool result *is* the answer: skip the second model call that would only restate it.
            tool_context.actions.skip_summarization = True
        best: TowDispatch | None = None
        for p in await repo.list_tow_providers():
            minutes = p["dispatch_minutes"] + _km(p["base"], {"lat": lat, "lng": lng}) * ROAD_FACTOR / p["avg_speed_kmh"] * 60
            candidate = TowDispatch(provider_id=p["provider_id"], provider=p["name"], eta_minutes=round(minutes), phone=p["phone"])
            if best is None or candidate.eta_minutes < best.eta_minutes:
                best = candidate
        if best is None:
            return {"error": "no_providers"}
        return best.model_dump()

    return find_nearest_tow


def build_agent(settings: Settings, repo: Repository) -> BaseAgent:
    tool = make_tool(repo)
    if settings.mock_ai:

        async def handle(request: dict[str, Any]) -> Any:
            loc = {**DEFAULT_LOCATION, **{k: v for k, v in (request.get("location") or {}).items() if v is not None}}
            return await tool(float(loc["lat"]), float(loc["lng"]))

        return ScriptedAgent(name="tow_agent", description="Despacho de grúas (modo simulado)", handler=handle)

    return LlmAgent(
        name="tow_agent",
        model=gemini(settings),
        description="Despacha la grúa más cercana y estima su tiempo de llegada.",
        instruction=INSTRUCTION,
        tools=[tool],
        # One model call picks the tool arguments; the tool's JSON goes back over A2A as is
        # (skip_summarization). No output_schema: combined with tools it made the model loop.
        generate_content_config=types.GenerateContentConfig(
            thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.LOW)
        ),
    )


def card(settings: Settings):  # noqa: ANN201
    return build_card(
        name="tow_agent",
        description="Grúas Aliadas: despacha la grúa más cercana en Ciudad de Guatemala y devuelve proveedor y ETA.",
        url=f"{settings.public_base_url}{PREFIX}",
        organization="Grúas Aliadas (ficticio)",
        skills=[
            AgentSkill(
                id=SKILL_ID,
                name="Despacho de grúa",
                description="Input JSON {claim_id, location:{lat,lng,label}}. Output JSON {provider_id, provider, eta_minutes, phone}.",
                tags=["tow", "roadside-assistance", "guatemala"],
                examples=['{"claim_id": "CLM-ABC123", "location": {"lat": 14.6, "lng": -90.51, "label": "zona 10"}}'],
            )
        ],
    )


