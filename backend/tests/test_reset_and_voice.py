"""make reset restores the seed; the voice WebSocket speaks the documented protocol."""

from __future__ import annotations

import pytest

from fastapi.testclient import TestClient

from escudo.main import create_app
from escudo.repository.repository import APPROVALS, CLAIMS, EVENTS, POLICIES
from escudo.seed import POLICY
from escudo.services import build_services
from conftest import make_settings
from helpers import ready_claim


pytestmark = pytest.mark.anyio

async def test_reset_clears_claims_and_restores_seed(http, services):
    await ready_claim(http)
    await services.repo.store.update(POLICIES, POLICY["policy_id"], {"deductible_usd": 9999})
    assert (await http.post("/admin/reset")).status_code == 401
    r = await http.post("/admin/reset", headers={"X-Admin-Token": "test-admin"})
    assert r.status_code == 204 and float(r.headers["X-Reset-Seconds"]) < 10
    for collection in (CLAIMS, APPROVALS, EVENTS):
        assert await services.repo.store.where(collection) == []
    assert (await http.get("/policy")).json()["deductible_usd"] == 500
    assert (await http.get("/claims/latest")).status_code == 404


def test_voice_websocket_mock_protocol():
    app = create_app(build_services(make_settings()))
    with TestClient(app) as client, client.websocket_connect("/assistant/live") as ws:
        ready = ws.receive_json()
        assert ready["type"] == "ready"
        assert ready["input_audio"] == {"encoding": "pcm_s16le", "sample_rate": 16000, "channels": 1}
        assert ready["output_audio"]["sample_rate"] == 24000
        ws.send_bytes(b"\x00\x00" * 1600)
        ws.send_json({"type": "text", "text": "Hola"})
        assert ws.receive_json()["role"] == "user"
        assert "3200 bytes" in ws.receive_json()["text"]
        assert len(ws.receive_bytes()) == 4800
        assert ws.receive_json()["type"] == "turn_complete"
        ws.send_json({"type": "end"})


def test_voice_websocket_requires_auth():
    app = create_app(build_services(make_settings(mock_auth=False)))
    with TestClient(app) as client, client.websocket_connect("/assistant/live") as ws:
        assert ws.receive_json()["code"] == "unauthenticated"
