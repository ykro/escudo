"""The Escudo agent finds Tow and Repair only through their Agent Cards."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
from a2a.utils.constants import AGENT_CARD_WELL_KNOWN_PATH
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from escudo.agents import repair_agent, tow_agent
from escudo.agents.escudo_agent import EscudoAgent
from escudo.agents.hosting import a2a_routes, build_card
from escudo.agents.peers import PeerUnavailable
from escudo.capabilities import Caller
from escudo.models import Claim, Location
from escudo.seed import POLICY
from escudo.models import Policy
from conftest import BASE, make_settings
from helpers import ready_claim

pytestmark = pytest.mark.anyio

CALLER = Caller(customer_id="cus_demo", uid="t")


async def test_agent_cards_are_served_at_the_well_known_path(http):
    assert AGENT_CARD_WELL_KNOWN_PATH == "/.well-known/agent-card.json"
    for prefix, skill in (("/a2a/tow", "tow_dispatch"), ("/a2a/repair", "repair_quotes"), ("/a2a/escudo", "claims_assistant")):
        r = await http.get(f"{prefix}{AGENT_CARD_WELL_KNOWN_PATH}")
        assert r.status_code == 200, prefix
        card = r.json()
        assert card["supportedInterfaces"][0]["url"] == f"{BASE}{prefix}"
        assert card["supportedInterfaces"][0]["protocolVersion"] == "1.0"
        assert skill in {s["id"] for s in card["skills"]}


async def test_tow_and_repair_go_over_a2a_with_discovered_cards(http, services):
    claim = await ready_claim(http)
    r = await http.post(f"/claims/{claim['claim_id']}/tow")
    assert r.status_code == 200 and r.json()["eta_minutes"] > 0
    events = await services.repo.list_events(claim["claim_id"])
    a2a = [e for e in events if e["surface"] == "a2a"]
    assert {(e["kind"], e["actor"]) for e in a2a} >= {("a2a_request", "escudo_agent"), ("a2a_response", "tow_agent"), ("a2a_response", "repair_agent")}
    assert all(e["data"]["discovered_via"] == "agent_card" for e in a2a if e["kind"] == "a2a_request")


class PartnerRecorder:
    """A fake partner company: card at an odd base path, RPC somewhere else entirely."""

    def __init__(self, settings) -> None:  # noqa: ANN001
        self.card_requests: list[str] = []
        self.rpc_requests: list[str] = []
        agent = tow_agent.build_agent(settings, _FakeRepo())
        card = build_card(name="grua_partner", description="x", url="http://partner.test/rpc/v7/dispatch",
                          organization="Partner", skills=tow_agent.card(settings).skills)
        routes = a2a_routes(agent, card, "/rpc/v7/dispatch")
        card_route = next(r for r in routes if r.path.endswith(AGENT_CARD_WELL_KNOWN_PATH))

        async def card_at_base(request: Request):  # noqa: ANN202
            self.card_requests.append(request.url.path)
            return await card_route.endpoint(request)

        self.app = Starlette(routes=[Route(f"/companies/gruas{AGENT_CARD_WELL_KNOWN_PATH}", card_at_base), *routes])

        async def record(scope, receive, send):  # noqa: ANN001, ANN202
            if scope["type"] == "http" and scope["method"] == "POST":
                self.rpc_requests.append(scope["path"])
            await self.app(scope, receive, send)

        self.asgi = record


class _FakeRepo:
    async def list_tow_providers(self):  # noqa: ANN201
        return [{"provider_id": "p1", "name": "Grúa Test", "phone": "1", "base": {"lat": 14.6, "lng": -90.5},
                 "avg_speed_kmh": 30, "dispatch_minutes": 5}]


def _claim() -> Claim:
    return Claim(claim_id="CLM-TEST01", customer_id="cus_demo", policy_id=POLICY["policy_id"], status="assessed",
                 description="x", location=Location(lat=14.6, lng=-90.51, label="zona 10"), created_at="2026-01-01T00:00:00Z")


async def test_rpc_endpoint_comes_from_the_card_not_from_a_convention(services):
    settings = make_settings(a2a_peers=("http://partner.test/companies/gruas",))
    partner = PartnerRecorder(settings)
    agent = EscudoAgent(settings, services.events)
    agent.client_factory = lambda: httpx.AsyncClient(transport=httpx.ASGITransport(app=partner.asgi), timeout=10)

    tow = await agent.request_tow(_claim(), Policy.model_validate(POLICY), CALLER)

    assert tow.provider == "Grúa Test"
    assert partner.card_requests == [f"/companies/gruas{AGENT_CARD_WELL_KNOWN_PATH}"]
    assert partner.rpc_requests and set(partner.rpc_requests) == {"/rpc/v7/dispatch"}


async def test_no_peer_advertising_the_skill_means_no_call(services):
    settings = make_settings(a2a_peers=("http://nobody.test/x",))
    agent = EscudoAgent(settings, services.events)

    async def down(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    agent.client_factory = lambda: httpx.AsyncClient(transport=httpx.MockTransport(down))
    with pytest.raises(PeerUnavailable):
        await agent.request_tow(_claim(), Policy.model_validate(POLICY), CALLER)


def test_escudo_agent_has_no_hard_coded_partner_paths():
    """Delegation code never names a partner endpoint; only A2A_PEERS + cards."""
    source = (Path(__file__).resolve().parents[1] / "escudo" / "agents" / "escudo_agent.py").read_text()
    for path in (tow_agent.PREFIX, repair_agent.PREFIX, "agent-card.json", "http://", "https://"):
        assert path not in source, path


async def test_partners_can_require_a_shared_secret():
    """A2A_SHARED_SECRET: partner RPC refuses strangers; cards stay public and declare the scheme."""
    from escudo.main import create_app
    from escudo.services import build_services

    services = build_services(make_settings(a2a_shared_secret="s3cret"))
    app = create_app(services)
    services.escudo_agent.client_factory = lambda: httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url=BASE, headers={"X-Escudo-A2A-Key": "s3cret"}, timeout=30
    )
    async with app.router.lifespan_context(app), httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE) as http:
        card = (await http.get(f"/a2a/tow{AGENT_CARD_WELL_KNOWN_PATH}")).json()
        assert card["securitySchemes"]["partnerKey"]["apiKeySecurityScheme"]["name"] == "X-Escudo-A2A-Key"
        stranger = await http.post("/a2a/tow", json={"jsonrpc": "2.0", "id": "1", "method": "SendMessage", "params": {}})
        assert stranger.status_code == 401
        claim = await ready_claim(http)  # repair quotes went through with the key
        assert (await http.post(f"/claims/{claim['claim_id']}/tow")).status_code == 200
