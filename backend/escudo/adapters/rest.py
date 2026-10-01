"""REST adapter (contracts/openapi.yaml), used by the Android app and the web.

Thin on purpose: parse HTTP, build a Caller, call the gateway, map the result
to a status code. The approval endpoints live here and only here — they are
the human surface, never a tool.
"""

from __future__ import annotations

import base64
import hmac
from typing import Any

from fastapi import APIRouter, Body, Depends, File, Header, Request, Response, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .. import approvals, devices, seed
from ..auth import bearer
from ..capabilities import Caller
from ..errors import EscudoError, Forbidden, InvalidInput, Unauthorized
from ..gateway import call_capability
from ..models import Location
from ..services import Services

router = APIRouter()


def services_of(request: Request) -> Services:
    return request.app.state.services


async def current_caller(
    request: Request,
    authorization: str | None = Header(default=None),
    x_escudo_surface: str | None = Header(default=None),
    x_escudo_actor: str | None = Header(default=None),
    x_escudo_run_id: str | None = Header(default=None),
) -> Caller:
    services = services_of(request)
    identity = await services.auth.verify(bearer(authorization))
    enum = services.events.enum
    # Optional labels for the presenter timeline (e.g. Android AppFunctions calls send surface=appfunctions).
    surface = x_escudo_surface if x_escudo_surface in enum["surface"] else "rest"
    actor = x_escudo_actor if x_escudo_actor in enum["actor"] else "customer"
    return Caller(customer_id=identity.customer_id, uid=identity.uid, actor=actor, surface=surface, run_id=x_escudo_run_id)


def gated_response(result: dict[str, Any]) -> JSONResponse:
    status = 202 if result.get("status") == "pending_approval" else 200
    return JSONResponse(result, status_code=status)


# --- capabilities -------------------------------------------------------------------

@router.get("/policy")
async def get_policy(request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "get_policy")


@router.get("/coverage/check")
async def check_coverage(incident_type: str, request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "check_coverage", {"incident_type": incident_type})


class OpenClaimBody(BaseModel):
    description: str
    location: Location | None = None
    occurred_at: str | None = None


@router.post("/claims", status_code=201)
async def open_claim(body: OpenClaimBody, request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "open_claim", body.model_dump(exclude_none=True))


@router.get("/claims/latest")
async def get_latest_claim(request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "get_claim_status", {})


@router.get("/claims/{claim_id}")
async def get_claim_status(claim_id: str, request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "get_claim_status", {"claim_id": claim_id})


@router.post("/claims/{claim_id}/photos")
async def attach_claim_photos(
    claim_id: str, request: Request, photos: list[UploadFile] = File(...), caller: Caller = Depends(current_caller)
) -> dict[str, Any]:
    encoded = [base64.b64encode(await p.read()).decode() for p in photos]
    return await call_capability(services_of(request), caller, "attach_claim_photos", {"claim_id": claim_id, "photos": encoded})


@router.post("/claims/{claim_id}/tow")
async def request_tow(claim_id: str, request: Request, caller: Caller = Depends(current_caller)) -> dict[str, Any]:
    return await call_capability(services_of(request), caller, "request_tow", {"claim_id": claim_id})


@router.get("/claims/{claim_id}/repair-options")
async def list_repair_options(claim_id: str, request: Request, caller: Caller = Depends(current_caller)) -> list[dict[str, Any]]:
    result = await call_capability(services_of(request), caller, "list_repair_options", {"claim_id": claim_id})
    return result["options"]


class ApprovalBody(BaseModel):
    approval_id: str | None = None


@router.post("/claims/{claim_id}/rental")
async def request_rental_car(
    claim_id: str, request: Request, body: ApprovalBody | None = Body(default=None), caller: Caller = Depends(current_caller)
) -> JSONResponse:
    approval_id = body.approval_id if body else None
    return gated_response(await call_capability(services_of(request), caller, "request_rental_car", {"claim_id": claim_id}, approval_id))


@router.post("/claims/{claim_id}/deductible")
async def accept_deductible(
    claim_id: str, request: Request, body: ApprovalBody | None = Body(default=None), caller: Caller = Depends(current_caller)
) -> JSONResponse:
    approval_id = body.approval_id if body else None
    return gated_response(await call_capability(services_of(request), caller, "accept_deductible", {"claim_id": claim_id}, approval_id))


class SelectShopBody(BaseModel):
    shop_id: str
    slot: str
    approval_id: str | None = None


@router.post("/claims/{claim_id}/repair")
async def select_repair_shop(claim_id: str, body: SelectShopBody, request: Request, caller: Caller = Depends(current_caller)) -> JSONResponse:
    args = {"claim_id": claim_id, "shop_id": body.shop_id, "slot": body.slot}
    return gated_response(await call_capability(services_of(request), caller, "select_repair_shop", args, body.approval_id))


@router.post("/claims/{claim_id}/sign")
async def sign_claim(
    claim_id: str, request: Request, body: ApprovalBody | None = Body(default=None), caller: Caller = Depends(current_caller)
) -> JSONResponse:
    approval_id = body.approval_id if body else None
    return gated_response(await call_capability(services_of(request), caller, "sign_claim", {"claim_id": claim_id}, approval_id))


# --- the human surface: approvals ----------------------------------------------------

APPROVAL_FIELDS = ("approval_id", "claim_id", "tool", "args_hash", "reason", "summary", "amount_usd", "status", "created_at")


@router.get("/approvals")
async def list_pending_approvals(request: Request, caller: Caller = Depends(current_caller)) -> list[dict[str, Any]]:
    pending = await services_of(request).repo.list_approvals(caller.customer_id, status="pending")
    return [a.model_dump(mode="json", include=set(APPROVAL_FIELDS)) for a in pending]


class DecisionBody(BaseModel):
    """escudo-approval:v1: the registered device's signature over approval_id + args_hash + decision."""

    device_id: str | None = None
    signature: str | None = None


async def _decide(approval_id: str, approve: bool, request: Request, caller: Caller, body: DecisionBody | None) -> dict[str, Any]:
    services = services_of(request)
    device_id = body.device_id if body else None
    approval = await approvals.decide(
        services.repo, approval_id, customer_id=caller.customer_id, approve=approve,
        device_id=device_id, signature=body.signature if body else None,
    )
    surface = caller.surface if caller.surface != "rest" else "android"
    await services.events.emit(
        actor="customer", surface=surface, kind="approval_granted" if approve else "approval_rejected",
        summary=("Aprobado por el cliente: " if approve else "Rechazado por el cliente: ") + approval.summary,
        run_id=caller.run_id or approval.claim_id or "demo", claim_id=approval.claim_id, tool=approval.tool,
        autonomy="needs_approval", approval_id=approval.approval_id, data={"device_id": device_id} if device_id else {},
    )
    if approve:
        # If the customer is talking to the voice assistant, let it continue on its own.
        request.app.state.assistant_hub.notify(
            caller.customer_id,
            f"[Aviso de Escudo] La clienta aprobó {approval.tool} en su teléfono. approval_id={approval.approval_id}. Continúa.",
        )
    return approval.model_dump(mode="json", include=set(APPROVAL_FIELDS))


@router.post("/approvals/{approval_id}/approve")
async def approve(
    approval_id: str, request: Request, body: DecisionBody | None = Body(default=None), caller: Caller = Depends(current_caller)
) -> dict[str, Any]:
    """Human-only: the Android app, after BiometricPrompt unlocks the device key that signs. Never a tool."""
    return await _decide(approval_id, True, request, caller, body)


@router.post("/approvals/{approval_id}/reject")
async def reject(
    approval_id: str, request: Request, body: DecisionBody | None = Body(default=None), caller: Caller = Depends(current_caller)
) -> dict[str, Any]:
    return await _decide(approval_id, False, request, caller, body)


# --- devices, events, admin ------------------------------------------------------------

class DeviceBody(BaseModel):
    device_id: str
    fcm_token: str
    public_key: str  # base64 DER SubjectPublicKeyInfo, EC P-256 (Android Keystore)
    platform: str | None = None


@router.post("/devices", status_code=204)
async def register_device(body: DeviceBody, request: Request, caller: Caller = Depends(current_caller)) -> Response:
    await devices.register(
        services_of(request).repo, customer_id=caller.customer_id, uid=caller.uid, device_id=body.device_id,
        fcm_token=body.fcm_token, platform=body.platform, public_key=body.public_key,
    )
    return Response(status_code=204)


@router.post("/events", status_code=204)
async def post_event(event: dict[str, Any], request: Request, caller: Caller = Depends(current_caller)) -> Response:
    """Narrative events from the apps (kind=message only); capability calls are recorded by the backend."""
    services = services_of(request)
    if event.get("claim_id"):
        try:
            await services.repo.get_claim(event["claim_id"], caller.customer_id)
        except EscudoError as exc:
            raise Forbidden("not_your_claim", "Ese reclamo no es tuyo.") from exc
    await services.events.append_external(event)
    return Response(status_code=204)


@router.post("/admin/reset", status_code=204)
async def reset(
    request: Request, x_admin_token: str | None = Header(default=None), authorization: str | None = Header(default=None)
) -> Response:
    services = services_of(request)
    expected = services.settings.admin_token
    given = x_admin_token or bearer(authorization) or ""
    if expected:
        if not hmac.compare_digest(given, expected):
            raise Unauthorized("admin_only", "Solo el presentador puede reiniciar la demo.")
    elif not services.settings.mock_auth:
        raise Unauthorized("admin_disabled", "ADMIN_TOKEN no está configurado.")
    seconds = await seed.reset(services.repo)
    return Response(status_code=204, headers={"X-Reset-Seconds": f"{seconds:.2f}"})


@router.get("/health", include_in_schema=False)  # not /healthz: Cloud Run's front end reserves that path
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


async def escudo_error_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, EscudoError)
    return JSONResponse(exc.to_dict(), status_code=exc.status)


__all__ = ["router", "escudo_error_handler", "InvalidInput"]
