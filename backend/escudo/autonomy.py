"""What an agent may do alone, and what needs a human. One table, enforced by the server.

The table is the policy; `enforce()` is the only code that applies it, and
`gateway.call_capability` — the single entry point for REST, MCP, A2A and
voice — calls it before any capability runs. A prompt can ask an agent to
behave; this table makes it impossible to misbehave.

Approving is deliberately *not* a capability: it only exists as a human
endpoint (`POST /approvals/{id}/approve`), never as a tool.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Awaitable, Callable

from pydantic import BaseModel

from . import approvals
from .capabilities import REGISTRY, Context
from .models import Approval, PendingApproval


class Level(StrEnum):
    AUTOMATIC = "automatic"
    NEEDS_APPROVAL = "needs_approval"


Condition = Callable[[BaseModel, Context], Awaitable[bool]]


@dataclass(frozen=True)
class Rule:
    level: Level
    why: str  # Spanish; shown to the customer as the reason for the pause
    runs_alone_if: Condition | None = None  # a gated action that is automatic when this holds


async def rental_is_covered(inp: BaseModel, ctx: Context) -> bool:
    policy = await ctx.repo.get_policy_for_customer(ctx.caller.customer_id)
    return "rental_car" in policy.coverages


AUTO = Level.AUTOMATIC
HUMAN = Level.NEEDS_APPROVAL

AUTONOMY: dict[str, Rule] = {
    "get_policy":          Rule(AUTO, "Solo lectura."),
    "check_coverage":      Rule(AUTO, "Solo lectura."),
    "open_claim":          Rule(AUTO, "Abrir un reclamo no te compromete a nada."),
    "attach_claim_photos": Rule(AUTO, "Adjuntar fotos no te compromete a nada."),
    "request_tow":         Rule(AUTO, "La grúa está incluida en tu póliza."),
    "list_repair_options": Rule(AUTO, "Consultar talleres no reserva nada."),
    "request_rental_car":  Rule(HUMAN, "Tu póliza no incluye auto de reemplazo: tendría costo.", runs_alone_if=rental_is_covered),
    "accept_deductible":   Rule(HUMAN, "Te compromete a pagar el deducible."),
    "select_repair_shop":  Rule(HUMAN, "Reserva un taller y un horario a tu nombre."),
    "sign_claim":          Rule(HUMAN, "Firma y envía el reclamo en tu nombre."),
    "get_claim_status":    Rule(AUTO, "Solo lectura."),
}


@dataclass(frozen=True)
class Decision:
    autonomy: Level  # effective level for this call
    pending: PendingApproval | None = None  # set when the call must stop and wait for the human
    approval: Approval | None = None  # the approval that was redeemed, if any


async def effective_level(tool: str, inp: BaseModel, ctx: Context) -> Level:
    rule = AUTONOMY[tool]
    if rule.level is HUMAN and rule.runs_alone_if and await rule.runs_alone_if(inp, ctx):
        return AUTO
    return rule.level


async def enforce(tool: str, inp: BaseModel, approval_id: str | None, ctx: Context) -> Decision:
    level = await effective_level(tool, inp, ctx)
    if level is AUTO:
        return Decision(level)

    args = inp.model_dump(mode="json")
    if not approval_id:
        # No approval yet: record the request, change nothing, tell the agent to wait.
        describe = REGISTRY[tool].describe_approval
        assert describe is not None, f"{tool} is gated but has no describe_approval"
        request = await describe(inp, ctx)
        pending = await approvals.request(
            ctx.repo, tool=tool, args=args, reason=AUTONOMY[tool].why, request=request, customer_id=ctx.caller.customer_id
        )
        return Decision(level, pending=pending)

    # Raises ApprovalInvalid unless it is approved, unused, same customer, same tool, same args.
    approval = await approvals.redeem(ctx.repo, approval_id, tool=tool, args=args, customer_id=ctx.caller.customer_id)
    return Decision(level, approval=approval)
