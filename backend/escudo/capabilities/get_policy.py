"""get_policy — coverage, deductible and vehicle. Automatic: read-only."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ._base import Capability, Context, usd


class Input(BaseModel):
    pass


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    return policy.model_dump(mode="json", exclude={"customer_id", "origin", "rental_pickup"})


def summarize(result: dict[str, Any]) -> str:
    return f"Póliza {result['policy_id']}: deducible {usd(result['deductible_usd'])}, {len(result['coverages'])} coberturas"


CAPABILITY = Capability(
    name="get_policy",
    description="Get the customer's car insurance policy: coverages, deductible (USD), insured vehicle and replacement-car days.",
    Input=Input,
    run=run,
    summarize=summarize,
)
