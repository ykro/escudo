"""accept_deductible — the customer agrees to pay the deductible. Needs approval: it is their money."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ..errors import Conflict
from ._base import ApprovalRequest, Capability, Context, advance, require_assessment, usd


class Input(BaseModel):
    claim_id: str


async def precheck(inp: Input, ctx: Context) -> None:
    claim = await ctx.claim(inp.claim_id)
    require_assessment(claim)
    if claim.deductible_accepted:
        raise Conflict("already_accepted", "Ya aceptaste el deducible de este reclamo.")


async def describe_approval(inp: Input, ctx: Context) -> ApprovalRequest:
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    return ApprovalRequest(
        summary=f"Aceptar pagar el deducible de {usd(policy.deductible_usd)} del reclamo {inp.claim_id}.",
        amount_usd=policy.deductible_usd,
        claim_id=inp.claim_id,
    )


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    await ctx.repo.update_claim(
        claim.claim_id, {"deductible_accepted": True, "status": advance(claim.status, "deductible_accepted")}
    )
    return {"claim_id": claim.claim_id, "deductible_accepted": True, "amount_usd": policy.deductible_usd}


def summarize(result: dict[str, Any]) -> str:
    return f"Deducible de {usd(result['amount_usd'])} aceptado"


CAPABILITY = Capability(
    name="accept_deductible",
    description="Agree, on the customer's behalf, to pay the policy deductible for this claim. Requires the customer's approval: the first call returns pending_approval with an approval_id; retry with that approval_id after the customer approves in the Escudo app.",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
    describe_approval=describe_approval,
)
