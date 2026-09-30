"""request_rental_car — replacement car.

Automatic when the policy covers it; if it does not, it would cost the customer
money, so the autonomy table requires their approval (see autonomy.py).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ._base import ApprovalRequest, Capability, Context, usd

UNCOVERED_DAYS = 3
UNCOVERED_DAILY_USD = 35.0
DEFAULT_PICKUP = "Arrendadora Aurora, Aeropuerto La Aurora, zona 13"


class Input(BaseModel):
    claim_id: str


async def precheck(inp: Input, ctx: Context) -> None:
    await ctx.claim(inp.claim_id)


async def describe_approval(inp: Input, ctx: Context) -> ApprovalRequest:
    cost = UNCOVERED_DAYS * UNCOVERED_DAILY_USD
    return ApprovalRequest(
        summary=f"Rentar un auto por {UNCOVERED_DAYS} días por {usd(cost)}: tu póliza no incluye auto de reemplazo.",
        amount_usd=cost,
        claim_id=inp.claim_id,
    )


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    if claim.rental is not None:
        return {"claim_id": claim.claim_id, **claim.rental}
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    pickup = policy.model_extra.get("rental_pickup", DEFAULT_PICKUP) if policy.model_extra else DEFAULT_PICKUP
    if "rental_car" in policy.coverages:
        rental = {"days": policy.rental_days, "pickup": pickup, "covered": True, "cost_usd": 0.0}
    else:
        rental = {"days": UNCOVERED_DAYS, "pickup": pickup, "covered": False, "cost_usd": UNCOVERED_DAYS * UNCOVERED_DAILY_USD}
    await ctx.repo.update_claim(claim.claim_id, {"rental": rental})
    return {"claim_id": claim.claim_id, **rental}


def summarize(result: dict[str, Any]) -> str:
    cost = "sin costo" if result["covered"] else usd(result["cost_usd"])
    return f"Auto de reemplazo por {result['days']} días ({cost}), recógelo en {result['pickup']}"


CAPABILITY = Capability(
    name="request_rental_car",
    description="Reserve a replacement car for the claim. Automatic when the policy covers it; otherwise returns pending_approval (retry with approval_id once the customer approves).",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
    describe_approval=describe_approval,
)
