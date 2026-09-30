"""request_tow — dispatch a tow truck. Automatic: towing is included in the policy.

The Escudo agent does not pick the truck itself: it delegates to the tow
company's agent over A2A (see agents/escudo_agent.py).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ..errors import Conflict
from ._base import Capability, Context, advance


class Input(BaseModel):
    claim_id: str


async def precheck(inp: Input, ctx: Context) -> None:
    await ctx.claim(inp.claim_id)
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    if "towing" not in policy.coverages:
        raise Conflict("towing_not_covered", "Tu póliza no incluye servicio de grúa.")


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    if claim.tow is not None:  # idempotent: a retried call does not send a second truck
        return {"claim_id": claim.claim_id, **claim.tow.model_dump(mode="json")}
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    tow = await ctx.services.escudo_agent.request_tow(claim, policy, ctx.caller)
    await ctx.repo.update_claim(
        claim.claim_id, {"tow": tow.model_dump(mode="json"), "status": advance(claim.status, "tow_requested")}
    )
    return {"claim_id": claim.claim_id, **tow.model_dump(mode="json")}


def summarize(result: dict[str, Any]) -> str:
    return f"Grúa en camino: {result['provider']}, llega en {result['eta_minutes']} min"


CAPABILITY = Capability(
    name="request_tow",
    description="Request a tow truck for the claim's location. Escudo delegates to a tow partner agent over A2A and returns provider and ETA in minutes.",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
)
