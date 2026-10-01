"""Escudo Assistant: the customer's voice agent (WebSocket /assistant/live).

The phone streams microphone audio; ADK runs a bidi-streaming session with
Gemini Live; the assistant acts through Escudo's *MCP tools* (loopback to
/mcp with the customer's own token), so it has exactly the same powers — and
the same approval gates — as any other agent. Wire format: contracts/tools.md.
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from collections import defaultdict
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from google.genai import types

from ..auth import bearer
from ..errors import EscudoError
from ..services import Services

log = logging.getLogger(__name__)
router = APIRouter()

INPUT_RATE = 16000
OUTPUT_RATE = 24000

INSTRUCTION = """Eres el Asistente Escudo, la voz de la aseguradora Escudo en el teléfono de la clienta.
Habla en español neutro de Guatemala, siempre de tú (nunca de usted, nada de «estimada clienta»),
cálido y breve (1-2 oraciones por turno).
Cuando la clienta tuvo un choque, ayúdala con todo usando tus herramientas:
1. get_claim_status para ver si ya abrió un reclamo desde la app (las fotos las sube la app).
   Si no hay reclamo, ábrelo con open_claim.
2. request_tow, list_repair_options (recomienda el taller mejor calificado o más barato y di por qué),
   request_rental_car.
3. select_repair_shop, accept_deductible y sign_claim necesitan su aprobación: cuando una herramienta
   responda pending_approval, dile que le llegó una notificación para aprobar con su huella y espera.
   Cuando recibas el aviso de que aprobó, repite la misma herramienta con los mismos datos y el approval_id.
Nunca digas que aprobaste por ella: no puedes aprobar. No inventes montos ni horarios: usa lo que
devuelven las herramientas. Di los montos en dólares y las fechas en palabras."""


class AssistantHub:
    """Live sessions per customer, so a phone approval can nudge the running conversation."""

    def __init__(self) -> None:
        self._queues: dict[str, set[Any]] = defaultdict(set)

    def add(self, customer_id: str, queue: Any) -> None:
        self._queues[customer_id].add(queue)

    def remove(self, customer_id: str, queue: Any) -> None:
        self._queues[customer_id].discard(queue)

    def notify(self, customer_id: str, text: str) -> int:
        queues = list(self._queues.get(customer_id, ()))
        for q in queues:
            q.send_content(types.Content(role="user", parts=[types.Part(text=text)]))
        return len(queues)


def build_assistant_agent(services: Services, token: str | None):  # noqa: ANN201
    from google.adk.agents import LlmAgent
    from google.adk.models import Gemini
    from google.adk.tools.mcp_tool import McpToolset, StreamableHTTPConnectionParams

    s = services.settings
    headers = {"X-Escudo-Actor": "escudo_assistant"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    tools = McpToolset(
        connection_params=StreamableHTTPConnectionParams(url=f"http://127.0.0.1:{s.port}/mcp", headers=headers, timeout=30),
    )
    model = Gemini(
        model=s.gemini_live_model,
        client_kwargs={"vertexai": True, "project": s.project_id, "location": s.gemini_live_location},
    )
    return LlmAgent(name="escudo_assistant", model=model, instruction=INSTRUCTION, tools=[tools])


def ready_message(mock: bool) -> dict[str, Any]:
    return {
        "type": "ready",
        "mock": mock,
        "input_audio": {"encoding": "pcm_s16le", "sample_rate": INPUT_RATE, "channels": 1},
        "output_audio": {"encoding": "pcm_s16le", "sample_rate": OUTPUT_RATE, "channels": 1},
    }


@router.websocket("/assistant/live")
async def assistant_live(ws: WebSocket) -> None:
    services: Services = ws.app.state.services
    # Tokens never travel in the URL (they end up in logs): header, or a first {"type":"auth"} frame.
    token = bearer(ws.headers.get("authorization"))
    await ws.accept()
    if not token and not services.auth.mock:
        token = await _first_frame_token(ws)
    try:
        identity = await services.auth.verify(token)
    except EscudoError as exc:
        await ws.send_json({"type": "error", "code": exc.code, "message": exc.message})
        await ws.close(code=4401)
        return

    if services.settings.mock_ai:
        await _mock_session(ws)
        return

    from google.adk.agents import LiveRequestQueue
    from google.adk.agents.run_config import RunConfig, StreamingMode
    from google.adk.runners import Runner
    from google.adk.sessions import InMemorySessionService

    session_id = f"voice-{uuid.uuid4().hex[:8]}"
    runner = Runner(
        app_name="escudo_assistant",
        agent=build_assistant_agent(services, token),
        session_service=InMemorySessionService(),
        auto_create_session=True,
    )
    queue = LiveRequestQueue()
    run_config = RunConfig(
        streaming_mode=StreamingMode.BIDI,
        response_modalities=[types.Modality.AUDIO],
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
    )
    hub: AssistantHub = ws.app.state.assistant_hub
    hub.add(identity.customer_id, queue)
    await ws.send_json(ready_message(False) | {"session_id": session_id})

    async def upstream() -> None:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                return
            if message.get("bytes"):
                queue.send_realtime(types.Blob(data=message["bytes"], mime_type=f"audio/pcm;rate={INPUT_RATE}"))
            elif message.get("text"):
                data = json.loads(message["text"])
                if data.get("type") == "text" and data.get("text"):
                    queue.send_content(types.Content(role="user", parts=[types.Part(text=data["text"])]))
                elif data.get("type") == "end":
                    return

    async def downstream() -> None:
        async for event in runner.run_live(user_id=identity.uid, session_id=session_id, live_request_queue=queue, run_config=run_config):
            for out in _to_client(event):
                if isinstance(out, bytes):
                    await ws.send_bytes(out)
                else:
                    await ws.send_json(out)

    tasks = [asyncio.create_task(upstream()), asyncio.create_task(downstream())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            if task.exception() and not isinstance(task.exception(), WebSocketDisconnect):
                raise task.exception()  # type: ignore[misc]
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # report, then close cleanly
        log.exception("assistant session failed")
        try:
            await ws.send_json({"type": "error", "code": "assistant_failed", "message": "El asistente tuvo un problema. Intenta de nuevo."})
        except Exception:
            pass
        del exc
    finally:
        hub.remove(identity.customer_id, queue)
        queue.close()
        for task in tasks:
            task.cancel()
        try:
            await ws.close()
        except Exception:
            pass


AUTH_FRAME_TIMEOUT_SECONDS = 10


async def _first_frame_token(ws: WebSocket) -> str | None:
    """For clients that cannot set headers on the upgrade: {"type":"auth","token":"<Firebase ID token>"}."""
    try:
        message = await asyncio.wait_for(ws.receive(), timeout=AUTH_FRAME_TIMEOUT_SECONDS)
    except (TimeoutError, WebSocketDisconnect):
        return None
    try:
        data = json.loads(message.get("text") or "{}")
    except json.JSONDecodeError:
        return None
    return data.get("token") if isinstance(data, dict) and data.get("type") == "auth" else None


def _to_client(event: Any) -> list[bytes | dict[str, Any]]:
    out: list[bytes | dict[str, Any]] = []
    if event.content and event.content.parts:
        for part in event.content.parts:
            if part.inline_data and part.inline_data.data and (part.inline_data.mime_type or "").startswith("audio/"):
                out.append(part.inline_data.data)
            if part.function_call:
                out.append({"type": "tool_call", "name": part.function_call.name})
            if part.function_response:
                response = part.function_response.response or {}
                out.append({"type": "tool_result", "name": part.function_response.name})
                pending = _find_pending(response)
                if pending:
                    out.append({"type": "approval_required", **pending})
    if event.input_transcription and event.input_transcription.text:
        out.append({"type": "transcript", "role": "user", "text": event.input_transcription.text,
                    "final": bool(event.input_transcription.finished)})
    if event.output_transcription and event.output_transcription.text:
        out.append({"type": "transcript", "role": "assistant", "text": event.output_transcription.text,
                    "final": bool(event.output_transcription.finished)})
    if event.interrupted:
        out.append({"type": "interrupted"})
    if event.turn_complete:
        out.append({"type": "turn_complete"})
    return out


def _find_pending(response: Any) -> dict[str, Any] | None:
    """MCP tool results arrive as text content; look for our pending_approval JSON inside."""
    text = json.dumps(response, ensure_ascii=False)
    if "pending_approval" not in text:
        return None
    for chunk in _json_objects(response):
        if chunk.get("status") == "pending_approval":
            return {k: chunk.get(k) for k in ("approval_id", "tool", "summary", "reason")}
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


async def _mock_session(ws: WebSocket) -> None:
    """MOCK_AI=1: same wire protocol, no model. Echoes text so clients can be tested offline."""
    await ws.send_json(ready_message(True) | {"session_id": "voice-mock"})
    received = 0
    try:
        while True:
            message = await ws.receive()
            if message["type"] == "websocket.disconnect":
                return
            if message.get("bytes"):
                received += len(message["bytes"])
                continue
            data = json.loads(message.get("text") or "{}")
            if data.get("type") == "end":
                break
            if data.get("type") == "text":
                await ws.send_json({"type": "transcript", "role": "user", "text": data.get("text", ""), "final": True})
                await ws.send_json({"type": "transcript", "role": "assistant", "final": True,
                                    "text": f"(simulado) Te escuché. Audio recibido: {received} bytes."})
                await ws.send_bytes(b"\x00\x00" * (OUTPUT_RATE // 10))  # 100 ms of silence
                await ws.send_json({"type": "turn_complete"})
    except WebSocketDisconnect:
        return
    await ws.close()
