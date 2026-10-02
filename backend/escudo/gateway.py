"""The one door into the domain.

REST, MCP, A2A and the chat assistant all call `call_capability`. It validates
input, runs the capability's precheck, applies the autonomy table, runs the
capability and writes the timeline events. Because there is exactly one door,
there is exactly one place where "needs approval" can be enforced.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from pydantic import ValidationError

from . import autonomy
from .capabilities import REGISTRY, Caller, Context
from .errors import EscudoError, InvalidInput, NotFound

if TYPE_CHECKING:
    from .services import Services

log = logging.getLogger(__name__)


async def call_capability(
    services: "Services",
    caller: Caller,
    name: str,
    args: dict[str, Any] | None = None,
    approval_id: str | None = None,
) -> dict[str, Any]:
    capability = REGISTRY.get(name)
    if capability is None:
        raise NotFound("unknown_capability", f"No existe la capacidad {name}.")
    args = dict(args or {})
    approval_id = approval_id or args.pop("approval_id", None)
    args.pop("approval_id", None)
    if "claim_id" in capability.Input.model_fields and caller.actor != "customer":
        args["claim_id"] = await _agent_claim_id(services, caller, args.get("claim_id"))
    try:
        inp = capability.Input.model_validate(args)
    except ValidationError as exc:
        first = exc.errors()[0]
        field = ".".join(str(p) for p in first["loc"]) or "(entrada)"
        raise InvalidInput("invalid_arguments", f"Dato inválido en {field}: {first['msg']}") from exc

    ctx = Context(services=services, caller=caller)
    events = services.events if _worth_a_timeline_entry(name, caller) else _SILENT
    claim_id = getattr(inp, "claim_id", None)
    run_id = caller.timeline_run_id
    common = {"actor": caller.actor, "surface": caller.surface, "run_id": run_id, "claim_id": claim_id, "tool": name}

    try:
        level = await autonomy.effective_level(name, inp, ctx)
        await events.emit(kind="tool_call", summary=_call_summary(name, approval_id), autonomy=level.value,
                          approval_id=approval_id, **common)
        # Precheck before asking a human: never request approval for something that would fail anyway.
        if capability.precheck:
            await capability.precheck(inp, ctx)
        decision = await autonomy.enforce(name, inp, approval_id, ctx)

        if decision.pending:
            pending = decision.pending
            await events.emit(
                kind="approval_requested",
                summary=f"Esperando aprobación de la clienta: {pending.summary}",
                autonomy=level.value,
                approval_id=pending.approval_id,
                reason=pending.reason,
                **{**common, "actor": "backend"},
            )
            approval = await services.repo.get_approval(pending.approval_id)
            try:
                if approval:
                    await services.push.approval_requested(approval)
            except Exception:  # a failed push must not lose the approval; the app also polls
                log.exception("push failed for %s", pending.approval_id)
            return pending.model_dump()

        result = await capability.run(inp, ctx)
    except EscudoError as exc:
        await events.emit(kind="error", summary=exc.message, data=exc.to_dict(), **common)
        raise

    result_claim = claim_id or result.get("claim_id")
    await events.emit(
        kind="tool_result",
        summary=capability.summarize(result),
        autonomy=decision.autonomy.value,
        approval_id=decision.approval.approval_id if decision.approval else None,
        **{**common, "claim_id": result_claim},
    )
    return result


# Screens poll these to stay fresh; the presenter timeline shows what agents do and every
# human decision, not an app refreshing itself. Agents' reads are still recorded.
_UI_READS = {"get_policy", "check_coverage", "get_claim_status"}


def _worth_a_timeline_entry(name: str, caller: Caller) -> bool:
    return not (name in _UI_READS and caller.actor == "customer")


class _Silent:
    async def emit(self, **_: Any) -> None:
        return None


_SILENT = _Silent()


async def _agent_claim_id(services: "Services", caller: Caller, given: Any) -> Any:
    """Models invent ids ("clm_96051512"). For agents, an unknown or missing claim id means the
    customer's active claim — always looked up within the token's customer, never across customers."""
    if isinstance(given, str) and given:
        try:
            await services.repo.get_claim(given, caller.customer_id)
            return given
        except EscudoError:
            pass
    active = await services.repo.active_claim(caller.customer_id)
    if active is None:
        return given
    if given:
        log.info("agent claim_id %r replaced by the active claim %s", given, active.claim_id)
    return active.claim_id


def _call_summary(name: str, approval_id: str | None) -> str:
    return f"Llama a {name}" + (" con aprobación" if approval_id else "")
