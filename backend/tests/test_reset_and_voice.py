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


def _real_auth_app():
    services = build_services(make_settings(mock_auth=False))

    async def fake_claims(token: str) -> dict:
        if token != "tok-andrea":
            raise ValueError("bad token")
        return {"uid": "u-andrea", "customer_id": "cus_demo"}

    services.auth._verified_claims = fake_claims  # type: ignore[method-assign]
    return create_app(services)


def test_voice_websocket_requires_auth():
    with TestClient(_real_auth_app()) as client, client.websocket_connect("/assistant/live") as ws:
        ws.send_json({"type": "text", "text": "hola"})  # not an auth frame
        assert ws.receive_json()["code"] == "unauthenticated"


def test_voice_token_in_the_url_is_ignored():
    with TestClient(_real_auth_app()) as client, client.websocket_connect("/assistant/live?token=tok-andrea") as ws:
        ws.send_json({"type": "end"})
        assert ws.receive_json()["code"] == "unauthenticated"


def test_voice_accepts_header_or_first_auth_frame():
    app = _real_auth_app()
    with TestClient(app) as client:
        with client.websocket_connect("/assistant/live", headers={"Authorization": "Bearer tok-andrea"}) as ws:
            assert ws.receive_json()["type"] == "ready"
        with client.websocket_connect("/assistant/live") as ws:
            ws.send_json({"type": "auth", "token": "tok-andrea"})
            assert ws.receive_json()["type"] == "ready"


async def test_reset_drops_keyless_devices_but_keeps_the_phone(http, services):
    from escudo.repository.repository import DEVICES
    from helpers import register_phone

    await register_phone(http)  # a real, keyed device
    await services.repo.register_device("probe", {"device_key": "probe", "customer_id": "cus_demo", "fcm_token": "x"})
    assert (await http.post("/admin/reset", headers={"X-Admin-Token": "test-admin"})).status_code == 204
    assert {d["device_key"] for d in await services.repo.store.where(DEVICES)} == {"test-phone"}
