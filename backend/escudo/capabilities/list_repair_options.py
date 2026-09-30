"""list_repair_options — partner shops with slots and quotes. Automatic: nothing is booked.

Quotes come from the repair network's agent over A2A, based on the damage assessment.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ._base import Capability, Context, require_assessment, usd


class Input(BaseModel):
    claim_id: str


async def precheck(inp: Input, ctx: Context) -> None:
    require_assessment(await ctx.claim(inp.claim_id))


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    options = await ctx.services.escudo_agent.repair_options(claim, policy, ctx.caller)
    # Remembered on the claim so select_repair_shop can only book what was offered.
    await ctx.repo.update_claim(claim.claim_id, {"repair_options": [o.model_dump(mode="json") for o in options]})
    return {"claim_id": claim.claim_id, "options": [o.model_dump(mode="json") for o in options]}


def summarize(result: dict[str, Any]) -> str:
    options = result["options"]
    if not options:
        return "Sin talleres disponibles"
    cheapest = min(options, key=lambda o: o["quote_usd"])
    return f"{len(options)} talleres disponibles; desde {usd(cheapest['quote_usd'])} ({cheapest['name']})"


CAPABILITY = Capability(
    name="list_repair_options",
    description="List partner repair shops for a claim with open slots and a quote for this damage (gathered from the repair network agent over A2A).",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
)
