"""escudo-approval:v1 — only the customer's registered device can approve, never a token alone.

Real auth path (MOCK_AUTH off) with two customers: Andrea (owns the claim) and Bruno.
"""

from __future__ import annotations

import json

import httpx
import pytest

from conftest import BASE, make_settings
from escudo.main import create_app
from escudo.repository.repository import DEVICE_AUDIT
from escudo.services import build_services
from escudo.test_device import TestDevice
from helpers import tiny_jpeg

pytestmark = pytest.mark.anyio

TOKENS = {"tok-andrea": {"uid": "u-andrea", "customer_id": "cus_demo"}, "tok-bruno": {"uid": "u-bruno", "customer_id": "cus_bruno"}}


@pytest.fixture
async def app():
    services = build_services(make_settings(mock_auth=False))

    async def fake_claims(token: str) -> dict:
        if token not in TOKENS:
            raise ValueError("bad token")
        return TOKENS[token]

    services.auth._verified_claims = fake_claims  # type: ignore[method-assign]
    application = create_app(services)
    services.escudo_agent.client_factory = lambda: httpx.AsyncClient(transport=httpx.ASGITransport(app=application), base_url=BASE, timeout=30)
    async with application.router.lifespan_context(application):
        yield application


def client(app, token: str) -> httpx.AsyncClient:  # noqa: ANN001
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE, headers={"Authorization": f"Bearer {token}"})


async def pending_deductible(andrea: httpx.AsyncClient) -> dict:
    claim = (await andrea.post("/claims", json={"description": "Choque menor"})).json()
    await andrea.post(f"/claims/{claim['claim_id']}/photos", files=[("photos", ("a.jpg", tiny_jpeg(), "image/jpeg"))])
    pending = (await andrea.post(f"/claims/{claim['claim_id']}/deductible", json={})).json()
    assert pending["status"] == "pending_approval"
    return {**pending, "claim_id": claim["claim_id"]}


async def status_of(andrea: httpx.AsyncClient, approval_id: str) -> str | None:
    return next((a["status"] for a in (await andrea.get("/approvals")).json() if a["approval_id"] == approval_id), None)


async def test_only_a_signature_from_the_customers_registered_device_approves(app):
    phone = TestDevice("andrea-phone")
    stranger = TestDevice("andrea-phone")  # same id, different key: what an attacker would try
    bruno_phone = TestDevice("bruno-phone")
    async with client(app, "tok-andrea") as andrea, client(app, "tok-bruno") as bruno:
        assert (await andrea.post("/devices", json=phone.registration())).status_code == 204
        assert (await bruno.post("/devices", json=bruno_phone.registration())).status_code == 204
        p = await pending_deductible(andrea)
        apr, args_hash = p["approval_id"], p["args_hash"]
        listed = (await andrea.get("/approvals")).json()
        assert listed[0]["args_hash"] == args_hash  # what the phone signs comes from the list

        async def attempt(http: httpx.AsyncClient, body: dict | None, approval_id: str = apr):  # noqa: ANN202
            return await http.post(f"/approvals/{approval_id}/approve", json=body)

        cases = {
            "token only, no device": (andrea, None, "no_device"),
            "unknown device": (andrea, TestDevice("ghost").decision(apr, args_hash), "unknown_device"),
            "another customer's device": (andrea, bruno_phone.decision(apr, args_hash), "unknown_device"),
            "bad signature": (andrea, {"device_id": "andrea-phone", "signature": "bm90LWEtc2lnbmF0dXJl"}, "bad_signature"),
            "key the device does not have": (andrea, stranger.decision(apr, args_hash), "bad_signature"),
            "signed for other args": (andrea, phone.decision(apr, "sha256:otherargs"), "bad_signature"),
            "signed for another approval": (andrea, phone.decision("apr_other", args_hash), "bad_signature"),
            "signed reject, sent approve": (andrea, phone.decision(apr, args_hash, "reject"), "bad_signature"),
            "another customer's approval": (bruno, bruno_phone.decision(apr, args_hash), "not_your_approval"),
        }
        for name, (http, body, detail) in cases.items():
            r = await attempt(http, body)
            assert r.status_code == 403, name
            assert r.json() == {**r.json(), "error": "approval_forbidden", "detail": detail}, name
            assert await status_of(andrea, apr) == "pending", f"{name} changed state"

        assert (await attempt(andrea, phone.decision(apr, args_hash))).status_code == 200  # the real phone
        assert (await attempt(andrea, phone.decision(apr, args_hash))).status_code == 409  # replay of the decision
        done = await andrea.post(f"/claims/{p['claim_id']}/deductible", json={"approval_id": apr})
        assert done.status_code == 200
        again = await andrea.post(f"/claims/{p['claim_id']}/deductible", json={"approval_id": apr})
        assert again.status_code in (403, 409)  # used approval never works twice


async def test_reject_uses_the_same_signature_scheme(app):
    phone = TestDevice("andrea-phone")
    async with client(app, "tok-andrea") as andrea:
        await andrea.post("/devices", json=phone.registration())
        p = await pending_deductible(andrea)
        wrong = await andrea.post(f"/approvals/{p['approval_id']}/reject", json=phone.decision(p["approval_id"], p["args_hash"], "approve"))
        assert wrong.status_code == 403
        ok = await andrea.post(f"/approvals/{p['approval_id']}/reject", json=phone.decision(p["approval_id"], p["args_hash"], "reject"))
        assert ok.status_code == 200 and ok.json()["status"] == "rejected"


async def test_disabled_device_cannot_approve(app):
    phone = TestDevice("andrea-phone")
    async with client(app, "tok-andrea") as andrea:
        await andrea.post("/devices", json=phone.registration())
        await app.state.services.repo.remove_device("andrea-phone")  # what an FCM "unregistered" does
        p = await pending_deductible(andrea)
        r = await andrea.post(f"/approvals/{p['approval_id']}/approve", json=phone.decision(p["approval_id"], p["args_hash"]))
        assert r.status_code == 403 and r.json()["detail"] == "device_disabled"


async def test_device_registration_rules(app):
    phone = TestDevice("shared-id")
    async with client(app, "tok-andrea") as andrea, client(app, "tok-bruno") as bruno:
        assert (await andrea.post("/devices", json=phone.registration())).status_code == 204
        # another customer cannot take over the device id
        assert (await bruno.post("/devices", json=TestDevice("shared-id").registration())).status_code == 403
        # not an EC P-256 SubjectPublicKeyInfo
        bad = {**phone.registration(), "public_key": "aGVsbG8="}
        assert (await andrea.post("/devices", json=bad)).status_code == 422
        missing = {k: v for k, v in phone.registration().items() if k != "public_key"}
        assert (await andrea.post("/devices", json=missing)).status_code == 422
        # same device, new key: replaced and audited
        rotated = TestDevice("shared-id")
        assert (await andrea.post("/devices", json=rotated.registration())).status_code == 204
        audit = await app.state.services.repo.store.where(DEVICE_AUDIT)
        assert len(audit) == 1 and audit[0]["event"] == "public_key_replaced" and audit[0]["device_id"] == "shared-id"
        assert (await app.state.services.repo.get_device("shared-id"))["public_key"] == rotated.public_key
        # requires the customer's token
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE) as anonymous:
            assert (await anonymous.post("/devices", json=TestDevice("x").registration())).status_code == 401


async def test_client_events_are_messages_about_own_claims(app):
    async with client(app, "tok-andrea") as andrea, client(app, "tok-bruno") as bruno:
        claim = (await andrea.post("/claims", json={"description": "Choque menor"})).json()
        msg = {"run_id": "r", "actor": "customer", "surface": "android", "kind": "message", "summary": "Abrió la app",
               "claim_id": claim["claim_id"], "id": "evt_forged", "ts": "2001-01-01T00:00:00Z"}
        assert (await andrea.post("/events", json=msg)).status_code == 204
        stored = [e for e in await app.state.services.repo.list_events(claim["claim_id"]) if e["summary"] == "Abrió la app"]
        assert stored and stored[0]["id"] != "evt_forged" and not stored[0]["ts"].startswith("2001")  # server owns id/ts
        assert (await bruno.post("/events", json=msg)).status_code == 403  # not Bruno's claim
        forged = {**msg, "kind": "approval_granted", "approval_id": "apr_x"}
        r = await andrea.post("/events", json=forged)
        assert r.status_code == 422 and r.json()["error"] == "event_kind_not_allowed"
        assert json.dumps(await app.state.services.repo.list_events(claim["claim_id"])).count("approval_granted") == 0
