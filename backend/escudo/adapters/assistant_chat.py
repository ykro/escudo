"""Escudo Assistant: the customer's typed chat (WebSocket /assistant/chat, contracts/tools.md).

An ADK agent on GEMINI_MODEL that acts only through Escudo's *MCP tools* (loopback to /mcp with the
customer's own token), so it has exactly the same powers — and the same approval gates — as any
other agent. Nothing lives in this process between turns: the transcript is in Firestore
(`chat_sessions`), and a pending approval is watched in Firestore, so the conversation resumes after
the phone approves no matter which Cloud Run instance served the approval.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import secrets
from typing import Any, AsyncGenerator

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from google.adk.agents import BaseAgent
from google.adk.agents.invocation_context import InvocationContext
from google.adk.events import Event
from google.genai import types

from ..auth import bearer
from ..capabilities import Caller
from ..errors import EscudoError
from ..models import now_iso
from ..services import Services

log = logging.getLogger(__name__)
router = APIRouter()

APP_NAME = "escudo_assistant"
CHAT_SESSIONS = "chat_sessions"
HISTORY_TURNS = 30  # messages replayed into the model each turn
APPROVAL_POLL_SECONDS = 1.0
APPROVAL_WATCH_SECONDS = 15 * 60

INSTRUCTION = """Eres el Asistente Escudo, el chat de la aseguradora Escudo en la app de la clienta.
Escribe en español neutro de Guatemala, siempre de tú (nunca de usted), cálido y breve (1-3 oraciones).
Cuando la clienta tuvo un choque, ayúdala con todo usando tus herramientas:
1. get_claim_status para ver si ya abrió un reclamo desde la app (las fotos las sube la app).
   Si no hay reclamo, ábrelo con open_claim.
2. request_tow, list_repair_options (elige el taller mejor calificado y su primer horario, y di por qué),
   request_rental_car.
3. select_repair_shop, accept_deductible y sign_claim necesitan su aprobación: cuando una herramienta
   responda pending_approval, dile en una oración que le llegó una notificación para aprobar con su huella
   y detente: no llames más herramientas en ese turno.
4. Cuando recibas «[Aviso de Escudo] La clienta aprobó …», repite esa misma herramienta con los mismos
   datos y el approval_id, y sigue con el siguiente paso pendiente sin preguntar. Si rechazó, no lo hagas.
Nunca digas que aprobaste por ella: no puedes aprobar. No inventes montos, horarios ni identificadores:
usa el claim_id, shop_id, slot y approval_id exactamente como los devuelven las herramientas. Montos en dólares, fechas en palabras."""


# --- transcript (Firestore) -------------------------------------------------------------

async def load_session(services: Services, customer_id: str, session_id: str | None) -> dict[str, Any]:
    if session_id:
        doc = await services.repo.store.get(CHAT_SESSIONS, session_id)
        if doc and doc.get("customer_id") == customer_id:
            return doc
    doc = {"session_id": "chat_" + secrets.token_urlsafe(9), "customer_id": customer_id, "messages": [],
           "pending": None, "created_at": now_iso(), "updated_at": now_iso()}
    await services.repo.store.set(CHAT_SESSIONS, doc["session_id"], doc)
    return doc


async def save_session(services: Services, session: dict[str, Any]) -> None:
    session["updated_at"] = now_iso()
    await services.repo.store.set(CHAT_SESSIONS, session["session_id"], session)


# --- the agent ----------------------------------------------------------------------------

def build_agent(services: Services, token: str | None, run_id: str | None) -> BaseAgent:
    if services.settings.mock_ai:
        return ScriptedAssistant(name=APP_NAME, description="Asistente Escudo (simulado)", services=services, run_id=run_id)
    from google.adk.agents import LlmAgent
    from google.adk.tools.mcp_tool import McpToolset, StreamableHTTPConnectionParams

    from ..agents.llm import gemini

    headers = {"X-Escudo-Actor": "escudo_assistant"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if run_id:
        headers["X-Escudo-Run-Id"] = run_id
    tools = McpToolset(connection_params=StreamableHTTPConnectionParams(
        url=f"http://127.0.0.1:{services.settings.port}/mcp", headers=headers, timeout=60))
    return LlmAgent(name=APP_NAME, model=gemini(services.settings), instruction=INSTRUCTION, tools=[tools],
                    generate_content_config=types.GenerateContentConfig(
                        thinking_config=types.ThinkingConfig(thinking_level=types.ThinkingLevel.LOW)))


class ScriptedAssistant(BaseAgent):
    """MOCK_AI=1: a deterministic assistant over the same capabilities (no model), same frames."""

    model_config = {"arbitrary_types_allowed": True}
    services: Any
    run_id: Any = None

    async def _run_async_impl(self, ctx: InvocationContext) -> AsyncGenerator[Event, None]:
        from ..gateway import call_capability

        raw = "".join(p.text or "" for p in (ctx.user_content.parts if ctx.user_content else []))
        text = raw.lower()
        caller = Caller(customer_id=ctx.user_id, uid=ctx.user_id, actor="escudo_assistant", surface="mcp", run_id=self.run_id)

        async def tool(name: str, args: dict[str, Any], approval_id: str | None = None) -> dict[str, Any]:
            call_id = f"call_{len(events) // 2 + 1}_{ctx.invocation_id[-6:]}"
            yield_call = types.Part(function_call=types.FunctionCall(id=call_id, name=name, args=args))
            events.append(Event(author=self.name, invocation_id=ctx.invocation_id, content=types.Content(role="model", parts=[yield_call])))
            try:
                result = await call_capability(self.services, caller, name, args, approval_id)
            except EscudoError as exc:
                result = exc.to_dict()
            events.append(Event(author=self.name, invocation_id=ctx.invocation_id, content=types.Content(
                role="user", parts=[types.Part(function_response=types.FunctionResponse(id=call_id, name=name, response=result))])))
            return result

        events: list[Event] = []
        reply = "¿En qué te ayudo con tu póliza o tu reclamo?"
        if match := re.search(r"aprob[oó] (\w+).*approval_id=(\S+?)\.?(\s|$)", raw):
            tool_name, approval_id = match.group(1), match.group(2)
            approval = await self.services.repo.get_approval(approval_id)
            args = dict(getattr(approval, "args", None) or (approval.model_extra or {}).get("args", {})) if approval else {}
            result = await tool(tool_name, args, approval_id)
            reply = "Listo, quedó hecho." if "error" not in result else f"No se pudo: {result.get('message')}"
        else:
            claim = await tool("get_claim_status", {})
            claim_id = claim.get("claim_id")
            if "taller" in text and claim_id:
                options = (await tool("list_repair_options", {"claim_id": claim_id})).get("options", [])
                if options:
                    best = max(options, key=lambda o: o["rating"])
                    result = await tool("select_repair_shop", {"claim_id": claim_id, "shop_id": best["shop_id"], "slot": best["slots"][0]})
                    reply = _pending_reply(result) or f"Reservé {best['name']}."
            elif "deducible" in text and claim_id:
                reply = _pending_reply(await tool("accept_deductible", {"claim_id": claim_id})) or "Deducible aceptado."
            elif "firma" in text and claim_id:
                reply = _pending_reply(await tool("sign_claim", {"claim_id": claim_id})) or "Reclamo firmado."
            elif claim_id:
                reply = f"Tu reclamo {claim_id} está {claim.get('status_label', 'en proceso')}."
        for event in events:
            yield event
        yield Event(author=self.name, invocation_id=ctx.invocation_id,
                    content=types.Content(role="model", parts=[types.Part(text=reply)]))


def _pending_reply(result: dict[str, Any]) -> str | None:
    if result.get("status") == "pending_approval":
        return "Te llegó una notificación para aprobar con tu huella."
    return None


# --- one turn -------------------------------------------------------------------------------

async def run_turn(ws: WebSocket, services: Services, session: dict[str, Any], identity: Any, token: str | None,
                   run_id: str | None, user_text: str) -> None:
    from google.adk.agents.run_config import RunConfig, StreamingMode
    from google.adk.runners import Runner
    from google.adk.sessions import InMemorySessionService

    agent = build_agent(services, token, run_id)
    sessions = InMemorySessionService()
    runner = Runner(app_name=APP_NAME, agent=agent, session_service=sessions)
    adk_session = await sessions.create_session(app_name=APP_NAME, user_id=identity.customer_id, session_id=session["session_id"])
    # Rebuild the conversation from Firestore: this instance has never seen it before.
    for message in session["messages"][-HISTORY_TURNS:]:
        author = "user" if message["role"] == "user" else APP_NAME
        role = "user" if message["role"] == "user" else "model"
        await sessions.append_event(adk_session, Event(author=author, content=types.Content(role=role, parts=[types.Part(text=message["text"])])))

    session["messages"].append({"role": "user", "text": user_text, "ts": now_iso()})
    full_text, pending = "", None
    async for event in runner.run_async(
        user_id=identity.customer_id, session_id=session["session_id"],
        new_message=types.Content(role="user", parts=[types.Part(text=user_text)]),
        run_config=RunConfig(streaming_mode=StreamingMode.SSE),
    ):
        for part in (event.content.parts if event.content else []):
            if part.text and not part.thought:
                if event.partial:
                    await _send(ws, {"type": "text", "text": part.text, "final": False})
                else:
                    full_text = (full_text + "\n" + part.text).strip() if full_text and part.text not in full_text else part.text
            # With SSE streaming ADK repeats a call in the partial and the final event: report it once.
            if part.function_call and not event.partial:
                await _send(ws, {"type": "tool_call", "name": part.function_call.name, "call_id": part.function_call.id or ""})
            if part.function_response and not event.partial:
                await _send(ws, {"type": "tool_result", "name": part.function_response.name, "call_id": part.function_response.id or ""})
                found = find_pending(part.function_response.response or {})
                if found:
                    pending = found
                    await _send(ws, {"type": "approval_required", **found})
    if full_text:
        await _send(ws, {"type": "text", "text": full_text, "final": True})
        session["messages"].append({"role": "assistant", "text": full_text, "ts": now_iso()})
    session["pending"] = {"approval_id": pending["approval_id"], "tool": pending["tool"]} if pending else session.get("pending")
    await save_session(services, session)
    await _send(ws, {"type": "turn_complete"})


async def _send(ws: WebSocket, frame: dict[str, Any]) -> None:
    try:
        await ws.send_json(frame)
    except (RuntimeError, WebSocketDisconnect):
        pass  # the client left; the transcript is saved anyway


def find_pending(response: Any) -> dict[str, Any] | None:
    """MCP tool results arrive as text content; look for our pending_approval JSON inside."""
    for chunk in _json_objects(response):
        if chunk.get("status") == "pending_approval":
            return {k: chunk.get(k) for k in ("approval_id", "tool", "summary", "reason", "args_hash")}
    return None


def _json_objects(value: Any):  # noqa: ANN202
    if isinstance(value, dict):
        yield value
        for v in value.values():
            yield from _json_objects(v)
    elif isinstance(value, list):
        for v in value:
            yield from _json_objects(v)
    elif isinstance(value, str) and value.lstrip().startswith("{"):
        try:
            yield from _json_objects(json.loads(value))
        except json.JSONDecodeError:
            return


# --- the socket -----------------------------------------------------------------------------

@router.websocket("/assistant/chat")
async def assistant_chat(ws: WebSocket) -> None:
    services: Services = ws.app.state.services
    token = bearer(ws.headers.get("authorization"))  # header only: tokens never go in URLs or frames
    await ws.accept()
    try:
        identity = await services.auth.verify(token)
    except EscudoError as exc:
        await ws.send_json({"type": "error", "code": exc.code, "message": exc.message})
        await ws.close(code=4401)
        return

    run_id = ws.headers.get("x-escudo-run-id")
    session = await load_session(services, identity.customer_id, ws.headers.get("x-escudo-chat-session"))
    lock = asyncio.Lock()
    background: set[asyncio.Task] = set()

    async def turn(text: str) -> None:
        async with lock:
            try:
                await run_turn(ws, services, session, identity, token, run_id, text)
            except Exception:
                log.exception("chat turn failed")
                await _send(ws, {"type": "error", "code": "assistant_failed", "message": "El asistente tuvo un problema. Intenta de nuevo."})
                await _send(ws, {"type": "turn_complete"})
        if session.get("pending"):
            spawn(watch(session["pending"]))

    async def watch(pending: dict[str, Any]) -> None:
        """Waits for the phone's decision (on any instance) and lets the assistant continue by itself."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + APPROVAL_WATCH_SECONDS
        while loop.time() < deadline:
            approval = await services.repo.get_approval(pending["approval_id"])
            if approval is None:
                return
            if approval.status != "pending":
                if session.get("pending", {}) and session["pending"]["approval_id"] == pending["approval_id"]:
                    session["pending"] = None
                approved = approval.status in ("approved", "used")
                await _send(ws, {"type": "approval_resolved", "approval_id": approval.approval_id, "tool": approval.tool,
                                 "status": "approved" if approved else "rejected"})
                verb = "aprobó" if approved else "rechazó"
                follow = "Continúa." if approved else "No lo hagas y pregúntale qué prefiere."
                await turn(f"[Aviso de Escudo] La clienta {verb} {approval.tool} en su teléfono. approval_id={approval.approval_id}. {follow}")
                return
            await asyncio.sleep(APPROVAL_POLL_SECONDS)

    def spawn(coro) -> None:  # noqa: ANN001
        task = asyncio.create_task(coro)
        background.add(task)
        task.add_done_callback(background.discard)

    await ws.send_json({"type": "ready", "session_id": session["session_id"], "mock": services.settings.mock_ai})
    if session.get("pending"):  # reconnected while an approval was waiting
        spawn(watch(session["pending"]))
    try:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                break
            try:
                data = json.loads(message.get("text") or "{}")
            except json.JSONDecodeError:
                continue
            if data.get("type") == "end":
                break
            if data.get("type") == "text" and str(data.get("text", "")).strip():
                spawn(turn(str(data["text"]).strip()))
    except WebSocketDisconnect:
        pass
    finally:
        for task in background:
            task.cancel()
        try:
            await ws.close()
        except Exception:
            pass
