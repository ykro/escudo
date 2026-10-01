"""Every surface acts as the customer in the verified token; nobody reads claims anonymously.

Real auth path (MOCK_AUTH off) with Firebase verification replaced by a table of fake tokens.
"""

from __future__ import annotations

import json

import httpx
import httpx2
import pytest
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from conftest import BASE, make_settings
from escudo.main import create_app
from escudo.services import build_services

pytestmark = pytest.mark.anyio

TOKENS = {
    "tok-andrea": {"uid": "u-andrea", "customer_id": "cus_demo"},  # seeded login: custom claim
    "tok-stranger": {"uid": "u-stranger"},  # verified, but no customer claim
}
A2A_MESSAGE = {"jsonrpc": "2.0", "id": "1", "method": "SendMessage",
               "params": {"message": {"messageId": "m1", "role": "ROLE_USER", "parts": [{"text": "¿Cómo va mi reclamo?"}]}}}


@pytest.fixture
async def real_auth_app():
    services = build_services(make_settings(mock_auth=False, a2a_shared_secret="partner-secret"))

    async def fake_claims(token: str) -> dict:
        if token not in TOKENS:
            raise ValueError("bad token")
        return TOKENS[token]

    services.auth._verified_claims = fake_claims  # type: ignore[method-assign]
    app = create_app(services)
    services.escudo_agent.client_factory = lambda: httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=BASE, headers={"X-Escudo-A2A-Key": "partner-secret"}, timeout=30
    )
    async with app.router.lifespan_context(app):
        yield app


def http_as(app, token: str | None = None) -> httpx.AsyncClient:  # noqa: ANN001
    headers = {"A2A-Version": "1.0", **({"Authorization": f"Bearer {token}"} if token else {})}
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE, headers=headers)


async def mcp_tool(app, token: str, tool: str, args: dict | None = None):  # noqa: ANN001, ANN201
    transport = httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), headers={"Authorization": f"Bearer {token}"})
    async with Client(streamable_http_client(f"{BASE}/mcp", http_client=transport)) as client:
        return await client.call_tool(tool, args or {})


async def test_mcp_acts_as_the_customer_in_the_token(real_auth_app):
    andrea = await mcp_tool(real_auth_app, "tok-andrea", "get_policy")
    assert not andrea.is_error and json.loads(andrea.content[0].text)["holder"]["customer_id"] == "cus_demo"

    stranger = await mcp_tool(real_auth_app, "tok-stranger", "get_policy")
    assert stranger.is_error and "policy_not_found" in stranger.content[0].text  # not the demo customer's policy


async def test_mcp_without_a_valid_token_is_refused(real_auth_app):
    async with http_as(real_auth_app) as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 401
    async with http_as(real_auth_app, "tok-forged") as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 401


async def test_rest_is_scoped_to_the_token_customer(real_auth_app):
    async with http_as(real_auth_app, "tok-andrea") as andrea, http_as(real_auth_app, "tok-stranger") as stranger:
        claim = (await andrea.post("/claims", json={"description": "Choque menor"})).json()
        assert (await stranger.get(f"/claims/{claim['claim_id']}")).status_code == 404
        assert (await stranger.get("/policy")).status_code == 404
        assert (await andrea.get(f"/claims/{claim['claim_id']}")).status_code == 200


async def test_escudo_agent_refuses_anonymous_callers(real_auth_app):
    async with http_as(real_auth_app) as http:
        assert (await http.get("/a2a/escudo/.well-known/agent-card.json")).status_code == 200  # discovery stays open
        card = (await http.get("/a2a/escudo/.well-known/agent-card.json")).json()
        assert set(card["securitySchemes"]) == {"customerToken", "partnerKey"}
        assert (await http.post("/a2a/escudo", json=A2A_MESSAGE)).status_code == 401
        assert (await http.post("/a2a/escudo", json=A2A_MESSAGE, headers={"Authorization": "Bearer tok-forged"})).status_code == 401
        # the partner key alone does not say whose claims to read
        assert (await http.post("/a2a/escudo", json=A2A_MESSAGE, headers={"X-Escudo-A2A-Key": "partner-secret"})).status_code == 401
        assert (await http.post("/a2a/escudo", json=A2A_MESSAGE,
                                headers={"X-Escudo-A2A-Key": "wrong", "X-Escudo-Customer-Id": "cus_demo"})).status_code == 401


async def test_escudo_agent_answers_as_the_authenticated_customer(real_auth_app):
    async with http_as(real_auth_app, "tok-andrea") as andrea:
        claim = (await andrea.post("/claims", json={"description": "Choque menor"})).json()

    def answer(response: httpx.Response) -> dict:
        assert response.status_code == 200, response.text
        return json.loads(json.dumps(response.json())).get("result", {})

    async with http_as(real_auth_app, "tok-andrea") as andrea:
        assert claim["claim_id"] in json.dumps(answer(await andrea.post("/a2a/escudo", json=A2A_MESSAGE)))
    async with http_as(real_auth_app, "tok-stranger") as stranger:
        text = json.dumps(answer(await stranger.post("/a2a/escudo", json=A2A_MESSAGE)), ensure_ascii=False)
        assert claim["claim_id"] not in text and "no_claims" in text
    async with http_as(real_auth_app) as partner:
        r = await partner.post("/a2a/escudo", json=A2A_MESSAGE,
                               headers={"X-Escudo-A2A-Key": "partner-secret", "X-Escudo-Customer-Id": "cus_demo"})
        assert claim["claim_id"] in json.dumps(answer(r))
