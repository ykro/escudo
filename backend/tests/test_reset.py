"""make reset restores the seed and keeps only keyed devices."""

from __future__ import annotations

import pytest


from escudo.repository.repository import APPROVALS, CLAIMS, EVENTS, POLICIES
from escudo.seed import POLICY
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


async def test_reset_drops_keyless_devices_but_keeps_the_phone(http, services):
    from escudo.repository.repository import DEVICES
    from helpers import register_phone

    await register_phone(http)  # a real, keyed device
    await services.repo.register_device("probe", {"device_key": "probe", "customer_id": "cus_demo", "fcm_token": "x"})
    assert (await http.post("/admin/reset", headers={"X-Admin-Token": "test-admin"})).status_code == 204
    assert {d["device_key"] for d in await services.repo.store.where(DEVICES)} == {"test-phone"}
