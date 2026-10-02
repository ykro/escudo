"""Escudo Assistant chat (/assistant/chat): header-only auth, tool calls through the capabilities,
pause on approval and automatic resume once the phone approves. MOCK_AI: deterministic assistant."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from conftest import make_settings, tiny_jpeg
from escudo.adapters import assistant_chat
from escudo.main import create_app
from escudo.services import build_services
from escudo.test_device import TestDevice

TOKENS = {"tok-andrea": {"uid": "u-andrea", "customer_id": "cus_demo"}}
AUTH = {"Authorization": "Bearer tok-andrea"}


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(assistant_chat, "APPROVAL_POLL_SECONDS", 0.05)
    services = build_services(make_settings(mock_auth=False))

    async def fake_claims(token: str) -> dict:
        if token not in TOKENS:
            raise ValueError("bad token")
        return TOKENS[token]

    services.auth._verified_claims = fake_claims  # type: ignore[method-assign]
    with TestClient(create_app(services)) as c:
        yield c


def until(ws, frame_type: str, limit: int = 40) -> list[dict]:  # noqa: ANN001
    frames = []
    for _ in range(limit):
        frames.append(ws.receive_json())
        if frames[-1]["type"] == frame_type:
            return frames
    raise AssertionError(f"no {frame_type} in {frames}")


def claim_with_photos(client) -> str:  # noqa: ANN001
    claim = client.post("/claims", json={"description": "Choque menor"}, headers=AUTH).json()
    client.post(f"/claims/{claim['claim_id']}/photos", files=[("photos", ("a.jpg", tiny_jpeg(), "image/jpeg"))], headers=AUTH)
    return claim["claim_id"]


def test_chat_requires_the_authorization_header(client):
    with client.websocket_connect("/assistant/chat") as ws:
        assert ws.receive_json()["code"] == "unauthenticated"
    with client.websocket_connect("/assistant/chat", headers={"Authorization": "Bearer forged"}) as ws:
        assert ws.receive_json()["code"] == "invalid_token"


def test_chat_answers_through_the_capabilities(client):
    claim_id = claim_with_photos(client)
    with client.websocket_connect("/assistant/chat", headers=AUTH) as ws:
        ready = ws.receive_json()
        assert ready["type"] == "ready" and ready["session_id"].startswith("chat_")
        ws.send_json({"type": "text", "text": "¿Cómo va mi reclamo?"})
        frames = until(ws, "turn_complete")
    calls = [f for f in frames if f["type"] == "tool_call"]
    results = [f for f in frames if f["type"] == "tool_result"]
    assert [c["name"] for c in calls] == ["get_claim_status"]  # each call reported once
    assert calls[0]["call_id"] and [r["call_id"] for r in results] == [calls[0]["call_id"]]  # paired by call_id
    final = [f for f in frames if f["type"] == "text" and f["final"]]
    assert final and claim_id in final[-1]["text"]


def test_chat_pauses_for_approval_and_resumes_by_itself(client):
    claim_id = claim_with_photos(client)
    phone = TestDevice("andrea-phone")
    assert client.post("/devices", json=phone.registration(), headers=AUTH).status_code == 204
    with client.websocket_connect("/assistant/chat", headers=AUTH) as ws:
        ws.receive_json()
        ws.send_json({"type": "text", "text": "Acepta el deducible, por favor."})
        frames = until(ws, "turn_complete")
        pause = next(f for f in frames if f["type"] == "approval_required")
        assert pause["tool"] == "accept_deductible" and pause["args_hash"]
        assert client.get(f"/claims/{claim_id}", headers=AUTH).json()["deductible_accepted"] is False

        # The customer approves on her phone (another request; could be another instance).
        decision = phone.decision(pause["approval_id"], pause["args_hash"])
        assert client.post(f"/approvals/{pause['approval_id']}/approve", json=decision, headers=AUTH).status_code == 200

        resumed = until(ws, "turn_complete")
        assert resumed[0] == {"type": "approval_resolved", "approval_id": pause["approval_id"], "tool": "accept_deductible", "status": "approved"}
        assert any(f["type"] == "tool_call" and f["name"] == "accept_deductible" for f in resumed)
        ids = [f["call_id"] for f in resumed if f["type"] in ("tool_call", "tool_result")]
        assert len(ids) == 2 * len(set(ids))  # every call has exactly one matching result
    assert client.get(f"/claims/{claim_id}", headers=AUTH).json()["deductible_accepted"] is True


def test_conversation_survives_a_reconnect(client):
    with client.websocket_connect("/assistant/chat", headers=AUTH) as ws:
        session_id = ws.receive_json()["session_id"]
        ws.send_json({"type": "text", "text": "Hola"})
        until(ws, "turn_complete")
    with client.websocket_connect("/assistant/chat", headers={**AUTH, "X-Escudo-Chat-Session": session_id}) as ws:
        assert ws.receive_json()["session_id"] == session_id
    stored = client.app.state.services.repo.store._data["chat_sessions"][session_id]  # type: ignore[attr-defined]
    assert [m["role"] for m in stored["messages"]] == ["user", "assistant"]
