"""get_claim_status — current claim and its timeline. Automatic: read-only."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from ._base import Capability, Context, public_claim

_STATUS = {
    "open": "abierto",
    "assessed": "evaluado",
    "tow_requested": "grúa solicitada",
    "repair_selected": "taller reservado",
    "deductible_accepted": "deducible aceptado",
    "signed": "firmado y enviado",
}


class Input(BaseModel):
    claim_id: str | None = Field(default=None, description="Claim id; the latest claim when omitted")


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await (ctx.claim(inp.claim_id) if inp.claim_id else ctx.repo.latest_claim(ctx.caller.customer_id))
    events = await ctx.repo.list_events(claim.claim_id)
    timeline = [
        {k: e.get(k) for k in ("ts", "actor", "surface", "kind", "tool", "summary")} for e in events[-30:]
    ]
    return {**public_claim(claim), "status_label": _STATUS[claim.status], "timeline": timeline}


def summarize(result: dict[str, Any]) -> str:
    return f"Reclamo {result['claim_id']}: {result['status_label']}"


CAPABILITY = Capability(
    name="get_claim_status",
    description="Get the current status of a claim (the latest one if claim_id is omitted), including tow, repair, rental and a timeline of steps.",
    Input=Input,
    run=run,
    summarize=summarize,
)
