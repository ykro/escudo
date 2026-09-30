"""Repair network agent ("Red de Talleres"): quotes and open slots for a damage assessment.

Reached only over A2A. Input: JSON with the assessment. Output: JSON list of RepairOption.
"""

from __future__ import annotations

from typing import Any

from a2a.types import AgentSkill
from google.adk.agents import BaseAgent, LlmAgent
from google.adk.tools import ToolContext
from google.genai import types
from pydantic import BaseModel

from ..config import Settings
from ..models import RepairOption
from ..repository import Repository
from .hosting import build_card
from .llm import gemini
from .scripted import ScriptedAgent

SKILL_ID = "repair_quotes"
PREFIX = "/a2a/repair"
SLOTS_PER_SHOP = 4

INSTRUCTION = """Eres el agente de la Red de Talleres, socio de la aseguradora Escudo.
Recibes un JSON con claim_id, vehicle y assessment (parts, severity, cost_range_usd).
1. Llama a quote_repairs con severity, cost_low y cost_high (de cost_range_usd) y los nombres de las piezas.
2. El resultado de la herramienta es la respuesta: no cambies precios ni horarios."""


class RepairQuotes(BaseModel):
    options: list[RepairOption]


def make_tool(repo: Repository):  # noqa: ANN201
    async def quote_repairs(severity: str, cost_low: float, cost_high: float, parts: list[str], tool_context: ToolContext | None = None) -> dict[str, Any]:
        """Quote the repair at every partner shop and return their next open slots.

        Args:
            severity: minor, moderate or severe.
            cost_low: Low end of the assessed repair cost in USD.
            cost_high: High end of the assessed repair cost in USD.
            parts: Names of the damaged parts.
        """
        if tool_context is not None:
            # The tool result *is* the answer: skip the second model call that would only restate it.
            tool_context.actions.skip_summarization = True
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
    tool = make_tool(repo)
    if settings.mock_ai:

        async def handle(request: dict[str, Any]) -> Any:
            a = request.get("assessment") or {}
            low, high = (a.get("cost_range_usd") or [600, 1000])[:2]
            return await tool(a.get("severity", "minor"), float(low), float(high), [p.get("name", "") for p in a.get("parts", [])])

        return ScriptedAgent(name="repair_agent", description="Cotización de talleres (modo simulado)", handler=handle)

    return LlmAgent(
        name="repair_agent",
        model=gemini(settings),
        description="Cotiza la reparación en los talleres de la red y ofrece horarios disponibles.",
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
        name="repair_agent",
        description="Red de Talleres: cotiza reparaciones de carrocería y ofrece horarios en talleres de Ciudad de Guatemala.",
        url=f"{settings.public_base_url}{PREFIX}",
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
