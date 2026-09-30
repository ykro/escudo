"""sign_claim — sign and submit the claim. Needs approval: it is a legal declaration."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel

from ..errors import Conflict
from ..models import now_iso
from ._base import ApprovalRequest, Capability, Context, advance, public_claim


class Input(BaseModel):
    claim_id: str


async def precheck(inp: Input, ctx: Context) -> None:
    claim = await ctx.claim(inp.claim_id)
    if claim.signed_at:
        raise Conflict("already_signed", "Este reclamo ya fue firmado y enviado.")
    missing = []
    if claim.assessment is None:
        missing.append("las fotos del daño")
    if claim.repair is None:
        missing.append("el taller")
    if not claim.deductible_accepted:
        missing.append("aceptar el deducible")
    if missing:
        raise Conflict("claim_incomplete", "Antes de firmar falta: " + ", ".join(missing) + ".")


async def describe_approval(inp: Input, ctx: Context) -> ApprovalRequest:
    claim = await ctx.claim(inp.claim_id)
    shop = claim.repair["shop_name"] if claim.repair else "el taller elegido"
    return ApprovalRequest(
        summary=f"Firmar y enviar el reclamo {claim.claim_id} (reparación en {shop}). Declaras que la información es verdadera.",
        amount_usd=None,
        claim_id=claim.claim_id,
    )


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    signed_at = now_iso()
    await ctx.repo.update_claim(claim.claim_id, {"signed_at": signed_at, "status": advance(claim.status, "signed")})
    return public_claim(await ctx.claim(inp.claim_id))


def summarize(result: dict[str, Any]) -> str:
    return f"Reclamo {result['claim_id']} firmado y enviado"


CAPABILITY = Capability(
    name="sign_claim",
    description="Sign and submit the claim on the customer's behalf. Requires the customer's approval: the first call returns pending_approval with an approval_id; retry with approval_id after the customer approves.",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
    describe_approval=describe_approval,
)
