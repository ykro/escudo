"""MCP adapter: Escudo's capabilities as MCP tools (streamable HTTP at /mcp).

Any MCP client — Gemini, Claude, the Escudo Assistant — gets the same 11
tools, authenticated with the customer's Firebase ID token. There is no
`approve` tool: gated tools return `pending_approval` and the human approves
on the phone.
"""

from __future__ import annotations

import logging
from typing import Any

from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken
from mcp.server.auth.settings import AuthSettings
from mcp.server.mcpserver import Context, MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import Field

from ..auth import bearer
from ..capabilities import REGISTRY, Caller
from ..errors import ApprovalInvalid, EscudoError
from ..gateway import call_capability
from ..models import Location
from ..services import Services

log = logging.getLogger(__name__)

INSTRUCTIONS = """Escudo is a car insurer. These tools act for the signed-in customer.
Typical flow after a crash: get_policy -> open_claim -> attach_claim_photos -> request_tow ->
list_repair_options -> request_rental_car -> select_repair_shop -> accept_deductible -> sign_claim.
Gated tools (accept_deductible, select_repair_shop, sign_claim, and request_rental_car when not covered)
first return {"status": "pending_approval", "approval_id": ...}: the customer approves on their phone,
then call the same tool again with the SAME arguments plus approval_id. Approvals are single-use.
You can never approve on the customer's behalf. Talk to the customer in Spanish."""

APPROVAL_ID = Field(default=None, description="Leave empty on the first call. After the customer approves on their phone, retry with the approval_id you received.")


class FirebaseTokenVerifier:
    """MCP SDK TokenVerifier backed by Firebase Auth."""

    def __init__(self, services: Services) -> None:
        self._services = services

    async def verify_token(self, token: str) -> AccessToken | None:
        try:
            identity = await self._services.auth.verify(token)
        except EscudoError:
            return None
        return AccessToken(
            token=token, client_id="escudo-customer", scopes=[], subject=identity.uid,
            claims={"customer_id": identity.customer_id},
        )


def build_mcp(services: Services) -> MCPServer:
    settings = services.settings
    auth_kwargs: dict[str, Any] = {}
    if not settings.mock_auth:
        auth_kwargs = {
            "token_verifier": FirebaseTokenVerifier(services),
            "auth": AuthSettings(
                issuer_url=f"https://securetoken.google.com/{settings.project_id}",
                resource_server_url=f"{settings.public_base_url}/mcp",
                validate_token_resource=False,  # Firebase tokens are checked for our project by the verifier
            ),
        }
    mcp = MCPServer("Escudo", instructions=INSTRUCTIONS, version="1.0.0", **auth_kwargs)

    async def caller_for(ctx: Context) -> Caller:
        return await caller_from_headers(ctx.headers)

    async def caller_from_headers(raw_headers: Any) -> Caller:
        headers = {k.lower(): v for k, v in (raw_headers or {}).items()}
        token = get_access_token()
        if token is not None:
            # The verified Firebase token says who this is; the agent acts as that customer.
            uid, customer_id = token.subject or "", (token.claims or {})["customer_id"]
        elif settings.mock_auth:
            # Same rule as REST under MOCK_AUTH: a run's mock token is that run's customer, else the demo one.
            identity = await services.auth.verify(bearer(headers.get("authorization")))
            uid, customer_id = identity.uid, identity.customer_id
        else:  # unreachable: without MOCK_AUTH the SDK's bearer middleware rejects the request first
            raise ToolError("unauthenticated: inicia sesión en Escudo para continuar.")
        actor = headers.get("x-escudo-actor", "external_agent")  # we cannot tell which model drives it
        return Caller(
            customer_id=customer_id,
            uid=uid,
            actor=actor if actor in services.events.enum["actor"] else "external_agent",
            surface="mcp",
            run_id=headers.get("x-escudo-run-id"),
        )

    # The discovery moment, on the presenter: an agent asking Escudo what it can do (once per run and actor;
    # the chat lists tools on every turn).
    announced: set[tuple[str, str]] = set()

    async def record_discovery(ctx, call_next):  # noqa: ANN001, ANN202
        result = await call_next(ctx)
        if ctx.method == "tools/list":
            try:
                caller = await caller_from_headers(getattr(ctx.request, "headers", None))
                key = (caller.timeline_run_id, caller.actor)
                if key not in announced:
                    announced.add(key)
                    await services.events.emit(
                        actor=caller.actor, surface="mcp", kind="message", run_id=caller.timeline_run_id,
                        summary=f"Descubre las {len(REGISTRY)} herramientas de Escudo (MCP tools/list)",
                        data={"tools": len(REGISTRY)},
                    )
            except Exception:  # the timeline must never break discovery
                log.exception("could not record tools/list")
        return result

    mcp.middleware.append(record_discovery)

    async def invoke(ctx: Context, name: str, args: dict[str, Any], approval_id: str | None = None) -> dict[str, Any]:
        try:
            return await call_capability(services, await caller_for(ctx), name, args, approval_id)
        except ApprovalInvalid as exc:
            raise ToolError(f"approval_invalid ({exc.detail}): {exc.message}") from exc
        except EscudoError as exc:
            raise ToolError(f"{exc.code}: {exc.message}") from exc

    def describe(name: str) -> str:
        return REGISTRY[name].description

    @mcp.tool(description=describe("get_policy"))
    async def get_policy(ctx: Context) -> dict[str, Any]:
        return await invoke(ctx, "get_policy", {})

    @mcp.tool(description=describe("check_coverage"))
    async def check_coverage(
        ctx: Context,
        incident_type: str = Field(description="collision, theft, towing, rental_car or third_party (Spanish words work too)"),
    ) -> dict[str, Any]:
        return await invoke(ctx, "check_coverage", {"incident_type": incident_type})

    @mcp.tool(description=describe("open_claim"))
    async def open_claim(
        ctx: Context,
        description: str = Field(description="What happened, in the customer's words"),
        location: Location | None = Field(default=None, description="{lat, lng, label}"),
        occurred_at: str | None = Field(default=None, description="ISO 8601; defaults to now"),
    ) -> dict[str, Any]:
        args = {"description": description, "location": location.model_dump() if location else None, "occurred_at": occurred_at}
        return await invoke(ctx, "open_claim", {k: v for k, v in args.items() if v is not None})

    @mcp.tool(description=describe("attach_claim_photos"))
    async def attach_claim_photos(
        ctx: Context,
        claim_id: str,
        photos: list[str] = Field(description="Base64 JPEG/PNG/WebP images or gs:// URIs in the claims bucket (1-6)"),
    ) -> dict[str, Any]:
        return await invoke(ctx, "attach_claim_photos", {"claim_id": claim_id, "photos": photos})

    @mcp.tool(description=describe("request_tow"))
    async def request_tow(ctx: Context, claim_id: str) -> dict[str, Any]:
        return await invoke(ctx, "request_tow", {"claim_id": claim_id})

    @mcp.tool(description=describe("list_repair_options"))
    async def list_repair_options(ctx: Context, claim_id: str) -> dict[str, Any]:
        return await invoke(ctx, "list_repair_options", {"claim_id": claim_id})

    @mcp.tool(description=describe("request_rental_car"))
    async def request_rental_car(ctx: Context, claim_id: str, approval_id: str | None = APPROVAL_ID) -> dict[str, Any]:
        return await invoke(ctx, "request_rental_car", {"claim_id": claim_id}, approval_id)

    @mcp.tool(description=describe("accept_deductible"))
    async def accept_deductible(ctx: Context, claim_id: str, approval_id: str | None = APPROVAL_ID) -> dict[str, Any]:
        return await invoke(ctx, "accept_deductible", {"claim_id": claim_id}, approval_id)

    @mcp.tool(description=describe("select_repair_shop"))
    async def select_repair_shop(
        ctx: Context,
        claim_id: str,
        shop_id: str = Field(description="shop_id from list_repair_options"),
        slot: str = Field(description="One of that shop's slots, exactly as returned by list_repair_options"),
        approval_id: str | None = APPROVAL_ID,
    ) -> dict[str, Any]:
        return await invoke(ctx, "select_repair_shop", {"claim_id": claim_id, "shop_id": shop_id, "slot": slot}, approval_id)

    @mcp.tool(description=describe("sign_claim"))
    async def sign_claim(ctx: Context, claim_id: str, approval_id: str | None = APPROVAL_ID) -> dict[str, Any]:
        return await invoke(ctx, "sign_claim", {"claim_id": claim_id}, approval_id)

    @mcp.tool(description=describe("get_claim_status"))
    async def get_claim_status(
        ctx: Context, claim_id: str | None = Field(default=None, description="Omit for the latest claim")
    ) -> dict[str, Any]:
        return await invoke(ctx, "get_claim_status", {"claim_id": claim_id} if claim_id else {})

    return mcp
