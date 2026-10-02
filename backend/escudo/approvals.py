"""Human approvals: single-use, bound to one tool and one exact set of arguments.

Lifecycle: pending -> approved | rejected -> used.
- `request`  creates a pending approval for (tool, args_hash).
- `approve` / `reject` are called only by the human endpoint (Android + biometrics).
- `redeem`   atomically checks everything and marks it used, so an approval can
             never run twice or authorise a different action than the one shown.
"""

from __future__ import annotations

import hashlib
import json
from typing import TYPE_CHECKING, Any

from .errors import ApprovalInvalid, Conflict
from .models import Approval, PendingApproval, new_approval_id, now_iso
from .repository import Repository

if TYPE_CHECKING:
    from .capabilities._base import ApprovalRequest


def args_hash(tool: str, args: dict[str, Any]) -> str:
    """Canonical hash of the tool name + arguments (approval_id and nulls excluded)."""
    clean = {k: v for k, v in sorted(args.items()) if v is not None and k != "approval_id"}
    canonical = json.dumps({"tool": tool, "args": clean}, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode()).hexdigest()


async def request(
    repo: Repository, *, tool: str, args: dict[str, Any], reason: str, request: "ApprovalRequest", customer_id: str
) -> PendingApproval:
    approval = Approval(
        approval_id=new_approval_id(),
        customer_id=customer_id,
        claim_id=request.claim_id,
        tool=tool,
        args_hash=args_hash(tool, args),
        reason=reason,
        summary=request.summary,
        amount_usd=request.amount_usd,
        created_at=now_iso(),
        args=args,  # kept for the approval screen; never used for the check
    )
    await repo.create_approval(approval)
    return PendingApproval(
        approval_id=approval.approval_id,
        tool=tool,
        reason=reason,
        args_hash=approval.args_hash,
        summary=approval.summary,
    )


async def redeem(repo: Repository, approval_id: str, *, tool: str, args: dict[str, Any], customer_id: str) -> Approval:
    expected_hash = args_hash(tool, args)

    def check_and_use(doc: dict[str, Any] | None) -> dict[str, Any]:
        if doc is None or doc.get("customer_id") != customer_id:
            raise ApprovalInvalid("unknown", "Esa aprobación no existe.")
        if doc["tool"] != tool:
            raise ApprovalInvalid("wrong_tool", "Esa aprobación es para otra acción.")
        if doc["args_hash"] != expected_hash:
            raise ApprovalInvalid("wrong_args", "Esa aprobación es para otros datos; pide una nueva.")
        status = doc["status"]
        if status == "used":
            raise ApprovalInvalid("already_used", "Esa aprobación ya se usó. Cada aprobación sirve una sola vez.")
        if status == "rejected":
            raise ApprovalInvalid("rejected", "La clienta rechazó esta acción.")
        if status != "approved":
            raise ApprovalInvalid("not_approved", "La clienta todavía no ha aprobado esta acción en su teléfono.")
        return {"status": "used", "used_at": now_iso()}

    return await repo.mutate_approval(approval_id, check_and_use)


async def decide(
    repo: Repository, approval_id: str, *, customer_id: str, approve: bool, device_id: str | None, signature: str | None
) -> Approval:
    """Human decision, device-bound (escudo-approval:v1): 403 unless the customer's registered device
    signed exactly this approval + args_hash + decision; 409 if it is not pending anymore."""
    from .devices import check_decision

    approval = await repo.get_approval(approval_id)
    await check_decision(repo, approval, customer_id=customer_id, device_id=device_id, signature=signature,
                         decision="approve" if approve else "reject")

    def transition(doc: dict[str, Any] | None) -> dict[str, Any]:
        if doc is None or doc["status"] != "pending":
            raise Conflict("approval_not_pending", "Esa aprobación ya fue decidida.")
        return {"status": "approved" if approve else "rejected", "decided_at": now_iso(), "device_id": device_id}

    return await repo.mutate_approval(approval_id, transition)
