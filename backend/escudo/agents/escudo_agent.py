"""The Escudo agent: the insurer's own agent.

Two roles:
1. Delegation. `request_tow` and `list_repair_options` need partners. The
   Escudo agent finds them by Agent Card (peers.py) and calls them with ADK's
   `RemoteA2aAgent`, over A2A, like any other company's agent.
2. A2A endpoint (`/a2a/escudo`). Other agents can talk to Escudo over A2A;
   its tools go through the same gateway + autonomy table as every surface.
"""

from __future__ import annotations

import json
import logging
import re
from typing import TYPE_CHECKING, Any, Callable

import httpx
from a2a.types import AgentCard, AgentSkill
from google.adk.a2a.agent import RemoteA2aAgent
from google.adk.agents import BaseAgent, LlmAgent
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.events import Event
from google.genai import types
from google.protobuf.json_format import ParseDict
from pydantic import ValidationError

from ..capabilities import Caller
from ..config import Settings
from ..errors import EscudoError
from ..events import EventWriter
from ..models import Claim, Policy, RepairOption, TowDispatch
from . import repair_agent, tow_agent
from .hosting import PARTNER_KEY_HEADER, build_card
from .llm import gemini
from .peers import PeerDirectory, PeerUnavailable

if TYPE_CHECKING:
    from ..services import Services

log = logging.getLogger(__name__)
PREFIX = "/a2a/escudo"
A2A_TIMEOUT_SECONDS = 25  # typical partner answer: 2-5 s; a stuck model call is abandoned and retried
ACTOR_BY_SKILL = {tow_agent.SKILL_ID: "tow_agent", repair_agent.SKILL_ID: "repair_agent"}


class EscudoAgent:
    def __init__(self, settings: Settings, events: EventWriter) -> None:
        self._settings = settings
        self._events = events
        # Replaceable so tests can route A2A calls to the in-process app.
        headers = {PARTNER_KEY_HEADER: settings.a2a_shared_secret} if settings.a2a_shared_secret else {}
        self.client_factory: Callable[[], httpx.AsyncClient] = lambda: httpx.AsyncClient(
            timeout=A2A_TIMEOUT_SECONDS, headers=headers
        )
        self.peers = PeerDirectory(settings.a2a_peers, lambda: self.client_factory())

    # --- delegation -------------------------------------------------------------
    async def request_tow(self, claim: Claim, policy: Policy, caller: Caller) -> TowDispatch:
        payload = {
            "claim_id": claim.claim_id,
            "location": claim.location.model_dump() if claim.location else None,
            "vehicle": policy.vehicle.model_dump(),
            "drivable": claim.assessment.drivable if claim.assessment else None,
        }
        data = await self._ask(tow_agent.SKILL_ID, payload, claim, caller, "Pide una grúa")
        try:
            tow = TowDispatch.model_validate(data.get("dispatch", data))
        except ValidationError as exc:
            raise PeerUnavailable("peer_bad_response", "El socio de grúas respondió algo que no entendimos.") from exc
        await self._emit_response(tow_agent.SKILL_ID, claim, caller, f"{tow.provider}: llega en {tow.eta_minutes} min",
                                  {"provider": tow.provider, "eta_minutes": tow.eta_minutes})
        return tow

    async def repair_options(self, claim: Claim, policy: Policy, caller: Caller) -> list[RepairOption]:
        payload = {
            "claim_id": claim.claim_id,
            "vehicle": policy.vehicle.model_dump(),
            "assessment": claim.assessment.model_dump(mode="json") if claim.assessment else None,
        }
        data = await self._ask(repair_agent.SKILL_ID, payload, claim, caller, "Pide cotizaciones")
        try:
            options = [RepairOption.model_validate(o) for o in data.get("options", [])]
        except ValidationError as exc:
            raise PeerUnavailable("peer_bad_response", "La red de talleres respondió algo que no entendimos.") from exc
        cheapest = min((o.quote_usd for o in options), default=0)
        await self._emit_response(repair_agent.SKILL_ID, claim, caller, f"{len(options)} talleres cotizados, desde USD {cheapest:,.0f}",
                                  {"shops": [o.name for o in options]})
        return options

    async def _ask(self, skill_id: str, payload: dict[str, Any], claim: Claim, caller: Caller, summary: str) -> dict[str, Any]:
        card = await self.peers.find(skill_id)
        url = card.supported_interfaces[0].url if card.supported_interfaces else ""
        partner = _partner_name(card)
        await self._events.emit(
            actor="escudo_agent", surface="a2a", kind="a2a_request", tool=_tool_for(skill_id),
            summary=f"{summary} a {partner} (vía su Agent Card)", run_id=caller.run_id or claim.claim_id, claim_id=claim.claim_id,
            data={"peer": card.name, "partner": partner, "skill": skill_id, "rpc_url": url, "discovered_via": "agent_card"},
        )
        last_error: Exception | None = None
        for _ in range(3):  # retries: a model call can (rarely) stall or answer with malformed JSON
            try:
                text = await self._send(card, json.dumps(payload, ensure_ascii=False))
                return _parse_json(text)
            except (ValueError, httpx.HTTPError) as exc:
                last_error = exc
                log.warning("A2A call to %s failed: %s", card.name, exc)
        self.peers.forget()
        raise PeerUnavailable("peer_unavailable", f"{card.name} no respondió. Intenta de nuevo en un momento.") from last_error

    async def _send(self, card: AgentCard, text: str) -> str:
        async with self.client_factory() as client:
            remote = RemoteA2aAgent(name=_identifier(card.name), agent_card=card, httpx_client=client)
            runner = Runner(app_name="escudo_agent", agent=remote, session_service=InMemorySessionService(), auto_create_session=True)
            final = ""
            async for event in runner.run_async(
                user_id="escudo", session_id=f"a2a-{id(remote)}",
                new_message=types.Content(role="user", parts=[types.Part(text=text)]),
            ):
                if event.error_message:
                    raise ValueError(event.error_message)
                if event.content and event.content.parts and not event.partial:
                    for part in event.content.parts:
                        # A partner answers either with text or, when its tool result is the answer
                        # (skip_summarization), with that function response carried over A2A as data.
                        if part.function_response and part.function_response.response is not None:
                            final = json.dumps(part.function_response.response, ensure_ascii=False)
                        elif part.text:
                            final = part.text
            return final

    async def _emit_response(self, skill_id: str, claim: Claim, caller: Caller, summary: str, data: dict[str, Any]) -> None:
        await self._events.emit(
            actor=ACTOR_BY_SKILL[skill_id], surface="a2a", kind="a2a_response", tool=_tool_for(skill_id),
            summary=summary, run_id=caller.run_id or claim.claim_id, claim_id=claim.claim_id, data=data,
        )


def _tool_for(skill_id: str) -> str:
    return "request_tow" if skill_id == tow_agent.SKILL_ID else "list_repair_options"


def _partner_name(card: AgentCard) -> str:
    """The company behind the card, for humans: 'Grúas Aliadas (ficticio)' -> 'Grúas Aliadas'."""
    organization = card.provider.organization if card.HasField("provider") else ""
    return re.sub(r"\s*\(.*\)$", "", organization) or card.name


def _identifier(name: str) -> str:
    return re.sub(r"\W", "_", name) or "peer"


def _parse_json(text: str) -> dict[str, Any]:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError(f"no JSON in partner response: {text[:120]!r}")
    return json.loads(match.group(0))


# --- the Escudo agent as an A2A server ------------------------------------------

INSTRUCTION = """Eres el agente de Escudo, una aseguradora de autos en Guatemala. Hablas con otros agentes
que actúan en nombre de la clienta. Responde en español, breve y preciso; cuando escribas algo
para ella, trátala de tú (nunca de usted).
Puedes consultar coberturas, el estado del reclamo y opciones de taller. Nunca apruebes nada:
las acciones que comprometen a la clienta (deducible, taller, firma) solo las aprueba ella en su teléfono."""


def build_a2a_agent(services: "Services") -> BaseAgent:
    from google.adk.tools import ToolContext

    from ..gateway import call_capability

    async def _call(tool_context: ToolContext, name: str, **args: Any) -> dict[str, Any]:
        # A2A_GUARD (main.py) authenticated the caller; A2A passes that customer to ADK as user_id.
        caller = Caller(customer_id=tool_context.user_id, uid=tool_context.user_id, actor="escudo_agent", surface="a2a")
        try:
            return await call_capability(services, caller, name, args)
        except EscudoError as exc:
            return exc.to_dict()

    async def check_coverage(incident_type: str, tool_context: ToolContext) -> dict[str, Any]:
        """Check if an incident type (collision, theft, towing, rental_car, third_party) is covered."""
        return await _call(tool_context, "check_coverage", incident_type=incident_type)

    async def get_claim_status(tool_context: ToolContext, claim_id: str | None = None) -> dict[str, Any]:
        """Get a claim's status; the latest claim when claim_id is omitted."""
        return await _call(tool_context, "get_claim_status", claim_id=claim_id)

    async def list_repair_options(claim_id: str, tool_context: ToolContext) -> dict[str, Any]:
        """List partner repair shops with slots and quotes for a claim."""
        return await _call(tool_context, "list_repair_options", claim_id=claim_id)

    if services.settings.mock_ai:
        return _ScriptedEscudoAgent(name="escudo_agent", description="Agente de Escudo (modo simulado)", services=services)

    return LlmAgent(
        name="escudo_agent",
        model=gemini(services.settings),
        description="Agente de la aseguradora Escudo: coberturas, estado de reclamos y talleres.",
        instruction=INSTRUCTION,
        tools=[check_coverage, get_claim_status, list_repair_options],
    )


class _ScriptedEscudoAgent(BaseAgent):
    """MOCK_AI=1: answers with the authenticated customer's latest claim, no model involved."""

    model_config = {"arbitrary_types_allowed": True}
    services: Any

    async def _run_async_impl(self, ctx):  # noqa: ANN001, ANN202
        from ..gateway import call_capability

        caller = Caller(customer_id=ctx.user_id, uid=ctx.user_id, actor="escudo_agent", surface="a2a")
        try:
            claim = await call_capability(self.services, caller, "get_claim_status", {})
            answer = {"claim_id": claim["claim_id"], "status": claim["status"]}
        except EscudoError as exc:
            answer = exc.to_dict()
        yield Event(author=self.name, invocation_id=ctx.invocation_id,
                    content=types.Content(role="model", parts=[types.Part(text=json.dumps(answer, ensure_ascii=False))]))


def card(settings: Settings) -> AgentCard:
    card = build_card(
        name="escudo_agent",
        description="Escudo, aseguradora de autos: coberturas, estado de reclamos y talleres de la red. Las acciones que comprometen al cliente requieren su aprobación en la app.",
        url=f"{settings.public_base_url}{PREFIX}",
        organization="Escudo Seguros (ficticio)",
        skills=[
            AgentSkill(
                id="claims_assistant",
                name="Reclamos de auto",
                description="Consulta coberturas, estado de un reclamo y opciones de taller.",
                tags=["insurance", "claims", "guatemala"],
                examples=["¿Mi póliza cubre un choque menor?", "¿Cómo va mi reclamo?"],
            )
        ],
    )
    # Declared so a caller learns from discovery how to authenticate (enforced by EscudoAgentGuard).
    schemes: dict[str, Any] = {"customerToken": {"httpAuthSecurityScheme": {
        "scheme": "bearer", "bearerFormat": "Firebase ID token", "description": "The customer's own session"}}}
    requirements: list[dict[str, Any]] = [{"schemes": {"customerToken": {"list": []}}}]
    if settings.a2a_shared_secret:
        schemes["partnerKey"] = {"apiKeySecurityScheme": {"location": "header", "name": PARTNER_KEY_HEADER,
                                 "description": "Partner secret; send X-Escudo-Customer-Id with the customer you act for"}}
        requirements.append({"schemes": {"partnerKey": {"list": []}}})
    ParseDict({"securitySchemes": schemes, "securityRequirements": requirements}, card)
    return card
