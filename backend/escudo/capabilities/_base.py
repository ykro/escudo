"""What every capability module provides, and the context it runs with.

A capability is plain domain code: typed input, an async `run`, and a Spanish
one-liner for the timeline. It knows nothing about HTTP, MCP or A2A, and it
never checks approvals itself — `gateway.py` + `autonomy.py` do that, once,
for every surface.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import TYPE_CHECKING, Any, Awaitable, Callable

from pydantic import BaseModel

from ..errors import Conflict
from ..models import Claim, ClaimStatus

if TYPE_CHECKING:
    from ..services import Services


@dataclass(frozen=True)
class Caller:
    """Who is acting, on whose behalf, through which surface (for events and ownership)."""

    customer_id: str
    uid: str
    actor: str = "customer"
    surface: str = "rest"
    run_id: str | None = None

    @property
    def timeline_run_id(self) -> str:
        """The presenter run this call belongs to: X-Escudo-Run-Id if sent, else the customer's run
        (a run customer's own run id, cus_run-ab12cd -> run-ab12cd; else the customer id), so asides
        without a claim (getCoverage, get_policy) stay on the same timeline as the claim."""
        if self.run_id:
            return self.run_id
        return self.customer_id[4:] if self.customer_id.startswith("cus_run-") else self.customer_id


@dataclass(frozen=True)
class Context:
    services: "Services"
    caller: Caller

    @property
    def repo(self):  # noqa: ANN201 - shortcut used by every capability
        return self.services.repo

    async def claim(self, claim_id: str) -> Claim:
        return await self.repo.get_claim(claim_id, self.caller.customer_id)


@dataclass(frozen=True)
class ApprovalRequest:
    """What the customer sees in the approval prompt."""

    summary: str
    amount_usd: float | None = None
    claim_id: str | None = None


@dataclass(frozen=True)
class Capability:
    name: str
    description: str  # agent-facing (MCP tool description)
    Input: type[BaseModel]
    run: Callable[[Any, Context], Awaitable[dict[str, Any]]]
    summarize: Callable[[dict[str, Any]], str]
    precheck: Callable[[Any, Context], Awaitable[None]] | None = None
    describe_approval: Callable[[Any, Context], Awaitable[ApprovalRequest]] | None = None


STATUS_ORDER: list[ClaimStatus] = ["open", "assessed", "tow_requested", "repair_selected", "deductible_accepted", "signed"]


def advance(current: ClaimStatus, new: ClaimStatus) -> ClaimStatus:
    """Claim status only moves forward (the milestone reached so far)."""
    return new if STATUS_ORDER.index(new) > STATUS_ORDER.index(current) else current


def public_claim(claim: Claim) -> dict[str, Any]:
    return claim.model_dump(mode="json", exclude={"customer_id"})


def require_assessment(claim: Claim) -> None:
    if claim.assessment is None:
        raise Conflict("assessment_missing", "Primero adjunta fotos del daño para evaluar el reclamo.")


_DAYS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
_MONTHS = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"]


def human_slot(slot: str) -> str:
    """'2026-10-06T08:00:00-06:00' -> 'martes 6 de octubre, 8:00'."""
    dt = datetime.fromisoformat(slot)
    return f"{_DAYS[dt.weekday()]} {dt.day} de {_MONTHS[dt.month - 1]}, {dt.hour}:{dt.minute:02d}"


def usd(amount: float) -> str:
    return f"USD {amount:,.0f}"
