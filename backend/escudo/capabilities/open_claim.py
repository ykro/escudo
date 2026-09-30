"""open_claim — start a claim. Automatic: opening a claim commits the customer to nothing."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from ..models import Claim, Location, new_claim_id, now_iso
from ._base import Capability, Context, public_claim


class Input(BaseModel):
    description: str = Field(min_length=3, description="What happened, in the customer's words")
    location: Location | None = Field(default=None, description="Where it happened: {lat, lng, label}")
    occurred_at: str | None = Field(default=None, description="When it happened (ISO 8601). Defaults to now.")


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    claim = Claim(
        claim_id=new_claim_id(),
        customer_id=ctx.caller.customer_id,
        policy_id=policy.policy_id,
        status="open",
        description=inp.description,
        location=inp.location,
        occurred_at=inp.occurred_at or now_iso(),
        created_at=now_iso(),
    )
    await ctx.repo.create_claim(claim)
    return public_claim(claim)


def summarize(result: dict[str, Any]) -> str:
    where = (result.get("location") or {}).get("label")
    return f"Reclamo {result['claim_id']} abierto" + (f" en {where}" if where else "")


CAPABILITY = Capability(
    name="open_claim",
    description="Open a new car insurance claim with a description of the incident, its location {lat, lng, label} and when it happened.",
    Input=Input,
    run=run,
    summarize=summarize,
)
