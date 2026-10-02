"""Per-run isolation: every scenario run gets its own customer; reset removes them all."""

from __future__ import annotations

import httpx
import pytest

from conftest import BASE, make_settings
from escudo.main import create_app
from escudo.repository.repository import CUSTOMERS, DEVICES, EVENTS, POLICIES, RUNS
from escudo.services import build_services
from escudo.test_device import TestDevice

pytestmark = pytest.mark.anyio
ADMIN = {"X-Admin-Token": "test-admin"}


@pytest.fixture
async def app():
    services = build_services(make_settings(mock_auth=False))
    created: dict[str, str] = {}
    deleted: list[str] = []

    async def create_run_user(run_id: str, customer_id: str) -> tuple[str, str]:
        created[f"tok-{run_id}"] = customer_id
        return f"{run_id}@escudo-demo.example", "pw"

    async def delete_run_users(uids: list[str]) -> None:
        deleted.extend(uids)

    async def fake_claims(token: str) -> dict:
        if token == "tok-stage":
            return {"uid": "u-stage", "customer_id": "cus_demo"}
        if token in created:
            return {"uid": "u-" + token, "customer_id": created[token]}
        raise ValueError("bad token")

    services.auth._verified_claims = fake_claims  # type: ignore[method-assign]
    services.auth.create_run_user = create_run_user  # type: ignore[method-assign]
    services.auth.delete_run_users = delete_run_users  # type: ignore[method-assign]
    application = create_app(services)
    application.state.deleted_users = deleted
    services.escudo_agent.client_factory = lambda: httpx.AsyncClient(transport=httpx.ASGITransport(app=application), base_url=BASE, timeout=30)
    async with application.router.lifespan_context(application):
        yield application


def as_(app, token: str | None = None, **headers: str) -> httpx.AsyncClient:  # noqa: ANN001
    h = {**headers, **({"Authorization": f"Bearer {token}"} if token else {})}
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url=BASE, headers=h)


async def test_each_run_is_its_own_customer(app):
    async with as_(app, **ADMIN) as admin:
        assert (await admin.post("/admin/runs", headers={"X-Admin-Token": "wrong"})).status_code == 401
        a, b = (await admin.post("/admin/runs")).json(), (await admin.post("/admin/runs")).json()
    assert a["run_id"] != b["run_id"] and a["customer_id"] == f"cus_{a['run_id']}"

    async with as_(app, f"tok-{a['run_id']}", **{"X-Escudo-Run-Id": a["run_id"]}) as run_a, \
               as_(app, f"tok-{b['run_id']}") as run_b, as_(app, "tok-stage") as stage:
        policy = (await run_a.get("/policy")).json()
        assert policy["holder"]["customer_id"] == a["customer_id"] and policy["deductible_usd"] == 500
        claim = (await run_a.post("/claims", json={"description": "Choque"})).json()
        assert (await run_b.get(f"/claims/{claim['claim_id']}")).status_code == 404  # runs never collide
        assert (await stage.get(f"/claims/{claim['claim_id']}")).status_code == 404  # nor touch the stage customer
        assert (await stage.get("/policy")).json()["policy_id"] == "POL-GT-2027-0042"
        await run_a.post("/devices", json=TestDevice(f"scenario-{a['run_id']}").registration())

    events = await app.state.services.repo.store.where(EVENTS)
    assert {e["run_id"] for e in events if e["claim_id"] == claim["claim_id"]} == {a["run_id"]}  # presenter ?run=


async def test_reset_removes_run_customers_but_keeps_the_stage_phone(app):
    async with as_(app, **ADMIN) as admin, as_(app, "tok-stage") as stage:
        run = (await admin.post("/admin/runs")).json()
        await stage.post("/devices", json=TestDevice("stage-phone").registration())
        async with as_(app, f"tok-{run['run_id']}") as run_client:
            await run_client.post("/devices", json=TestDevice("run-phone").registration())
        assert (await admin.post("/admin/reset")).status_code == 204
    store = app.state.services.repo.store
    assert await store.where(RUNS) == []
    assert {c["customer_id"] for c in await store.where(CUSTOMERS)} == {"cus_demo"}
    assert {p["customer_id"] for p in await store.where(POLICIES)} == {"cus_demo"}
    assert {d["device_key"] for d in await store.where(DEVICES)} == {"stage-phone"}
    assert app.state.deleted_users == [f"run_{run['run_id']}"]
