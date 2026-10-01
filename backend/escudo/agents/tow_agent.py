"""Tow partner agent ("Grúas Aliadas"): picks the provider that arrives first.

Reached only over A2A. Input: JSON with the claim location. Output: JSON TowDispatch.
"""

from __future__ import annotations

import math
from typing import Any

from a2a.types import AgentSkill
from google.adk.agents import BaseAgent


from ..config import Settings
from ..models import TowDispatch
from ..repository import Repository
from .hosting import build_card
from .scripted import ScriptedAgent

SKILL_ID = "tow_dispatch"
PREFIX = "/a2a/tow"
DEFAULT_LOCATION = {"lat": 14.6349, "lng": -90.5069}  # Ciudad de Guatemala centro
ROAD_FACTOR = 1.4  # streets are not straight lines



def _km(a: dict[str, float], b: dict[str, float]) -> float:
    lat1, lng1, lat2, lng2 = map(math.radians, (a["lat"], a["lng"], b["lat"], b["lng"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(h))


def make_tool(repo: Repository):  # noqa: ANN201
    async def find_nearest_tow(lat: float, lng: float) -> dict[str, Any]:
        """Find the tow provider with the shortest ETA to a location in Guatemala City.

        Args:
            lat: Latitude of the vehicle.
            lng: Longitude of the vehicle.
        """
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
    """A deterministic ADK agent: the answer is computed, never generated — fast (~0.1 s), testable and
    the same on stage every time. A Gemini-written note was measured (2026-10-01): only 5 of 31 arrived
    within a 1.5 s budget, so every call paid 1.5 s for a template; it was removed."""
    tool = make_tool(repo)

    async def handle(request: dict[str, Any]) -> Any:
        loc = {**DEFAULT_LOCATION, **{k: v for k, v in (request.get("location") or {}).items() if v is not None}}
        result = await tool(float(loc["lat"]), float(loc["lng"]))
        return result

    return ScriptedAgent(name="tow_agent", description="Despacha la grúa más cercana y estima su tiempo de llegada.", handler=handle)


def card(settings: Settings):  # noqa: ANN201
    return build_card(
        name="tow_agent",
        description="Grúas Aliadas: despacha la grúa más cercana en Ciudad de Guatemala y devuelve proveedor y ETA.",
        url=f"{settings.public_base_url}{PREFIX}",
        requires_partner_key=bool(settings.a2a_shared_secret),
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


