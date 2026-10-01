"""Repair network agent ("Red de Talleres"): quotes and open slots for a damage assessment.

Reached only over A2A. Input: JSON with the assessment. Output: JSON list of RepairOption.
"""

from __future__ import annotations

from typing import Any

from a2a.types import AgentSkill
from google.adk.agents import BaseAgent
from pydantic import BaseModel

from ..config import Settings
from ..models import RepairOption
from ..repository import Repository
from .hosting import build_card
from .scripted import ScriptedAgent

SKILL_ID = "repair_quotes"
PREFIX = "/a2a/repair"
SLOTS_PER_SHOP = 4



class RepairQuotes(BaseModel):
    options: list[RepairOption]


def make_tool(repo: Repository):  # noqa: ANN201
    async def quote_repairs(severity: str, cost_low: float, cost_high: float, parts: list[str]) -> dict[str, Any]:
        """Quote the repair at every partner shop and return their next open slots.

        Args:
            severity: minor, moderate or severe.
            cost_low: Low end of the assessed repair cost in USD.
            cost_high: High end of the assessed repair cost in USD.
            parts: Names of the damaged parts.
        """
        base = (cost_low + cost_high) / 2 + 25 * len(parts)
        options = [
            RepairOption(
                shop_id=s["shop_id"],
                name=s["name"],
                address=s["address"],
                rating=s["rating"],
                slots=s["slots"][:SLOTS_PER_SHOP],
                quote_usd=round(base * s["labor_factor"] / 10) * 10,
            )
            for s in await repo.list_shops()
        ]
        options.sort(key=lambda o: (o.quote_usd, -o.rating))
        return RepairQuotes(options=options).model_dump()

    return quote_repairs


def build_agent(settings: Settings, repo: Repository) -> BaseAgent:
    """A deterministic ADK agent: the answer is computed, never generated — fast (~0.1 s), testable and
    the same on stage every time. A Gemini-written note was measured (2026-10-01): only 5 of 31 arrived
    within a 1.5 s budget, so every call paid 1.5 s for a template; it was removed."""
    tool = make_tool(repo)

    async def handle(request: dict[str, Any]) -> Any:
        a = request.get("assessment") or {}
        low, high = (a.get("cost_range_usd") or [600, 1000])[:2]
        result = await tool(a.get("severity", "minor"), float(low), float(high), [p.get("name", "") for p in a.get("parts", [])])
        return result

    return ScriptedAgent(name="repair_agent", description="Cotiza la reparación en los talleres de la red y ofrece horarios disponibles.", handler=handle)


def card(settings: Settings):  # noqa: ANN201
    return build_card(
        name="repair_agent",
        description="Red de Talleres: cotiza reparaciones de carrocería y ofrece horarios en talleres de Ciudad de Guatemala.",
        url=f"{settings.public_base_url}{PREFIX}",
        requires_partner_key=bool(settings.a2a_shared_secret),
        organization="Red de Talleres (ficticio)",
        skills=[
            AgentSkill(
                id=SKILL_ID,
                name="Cotización de reparación",
                description="Input JSON {claim_id, vehicle, assessment:{parts, severity, cost_range_usd}}. Output JSON {options:[{shop_id, name, address, rating, slots, quote_usd}]}.",
                tags=["repair", "body-shop", "quote", "guatemala"],
                examples=['{"claim_id": "CLM-ABC123", "assessment": {"severity": "minor", "cost_range_usd": [600, 1000], "parts": []}}'],
            )
        ],
    )
