"""select_repair_shop — book a shop and slot. Needs approval: it commits the customer's time and car."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from ..errors import Conflict
from ..models import Claim, RepairOption
from ._base import ApprovalRequest, Capability, Context, advance, human_slot, usd


class Input(BaseModel):
    claim_id: str
    shop_id: str = Field(description="shop_id from list_repair_options")
    slot: str = Field(description="One of that shop's slots (ISO 8601), from list_repair_options")


def _same_instant(a: str, b: str) -> bool:
    try:
        return datetime.fromisoformat(a.replace("Z", "+00:00")) == datetime.fromisoformat(b.replace("Z", "+00:00"))
    except ValueError:
        return False


def _find(claim: Claim, inp: Input) -> tuple[RepairOption, str]:
    option = next((o for o in claim.repair_options if o.shop_id == inp.shop_id), None)
    if option is None:
        raise Conflict("unknown_shop", "Ese taller no está entre las opciones del reclamo; pide primero las opciones de reparación.")
    slot = next((s for s in option.slots if _same_instant(s, inp.slot)), None)
    if slot is None:
        raise Conflict("unknown_slot", f"Ese horario no está disponible en {option.name}.")
    return option, slot


async def precheck(inp: Input, ctx: Context) -> None:
    claim = await ctx.claim(inp.claim_id)
    _find(claim, inp)
    if claim.repair is not None:
        raise Conflict("already_booked", "Este reclamo ya tiene taller reservado.")


async def describe_approval(inp: Input, ctx: Context) -> ApprovalRequest:
    option, slot = _find(await ctx.claim(inp.claim_id), inp)
    return ApprovalRequest(
        summary=f"Reservar {option.name} el {human_slot(slot)}. Cotización {usd(option.quote_usd)}; tú solo pagas el deducible.",
        amount_usd=None,
        claim_id=inp.claim_id,
    )


async def run(inp: Input, ctx: Context) -> dict[str, Any]:
    claim = await ctx.claim(inp.claim_id)
    option, slot = _find(claim, inp)
    repair = {"shop_id": option.shop_id, "shop_name": option.name, "slot": slot, "quote_usd": option.quote_usd, "address": option.address}
    await ctx.repo.update_claim(claim.claim_id, {"repair": repair, "status": advance(claim.status, "repair_selected")})
    return {"claim_id": claim.claim_id, **repair}


def summarize(result: dict[str, Any]) -> str:
    return f"Taller reservado: {result['shop_name']}, {human_slot(result['slot'])}"


CAPABILITY = Capability(
    name="select_repair_shop",
    description="Book one of the offered repair shops and slots for the claim. Requires the customer's approval: the first call returns pending_approval with an approval_id; retry with the same arguments plus approval_id after the customer approves.",
    Input=Input,
    run=run,
    summarize=summarize,
    precheck=precheck,
    describe_approval=describe_approval,
)
