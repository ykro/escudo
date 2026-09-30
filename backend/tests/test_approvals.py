"""The server rejects every approval-gated action without a valid approval_id.

Checked for each gated tool, through REST and MCP: no id, a made-up id, an id
still pending, a rejected id, an id for another tool, an id for other
arguments, and a reused id.
"""

from __future__ import annotations

import pytest

from escudo.repository.repository import POLICIES
from escudo.seed import POLICY
from helpers import approve, mcp_error, mcp_payload, ready_claim

pytestmark = pytest.mark.anyio

GATED = ["select_repair_shop", "accept_deductible", "sign_claim", "request_rental_car"]


async def prepare(http, services, tool: str):  # noqa: ANN001, ANN201
    """Claim in the right state for `tool`; returns (rest_path, body_without_approval, claim)."""
    claim = await ready_claim(http)
    cid = claim["claim_id"]
    shop = claim["options"][0]
    if tool == "select_repair_shop":
        return f"/claims/{cid}/repair", {"shop_id": shop["shop_id"], "slot": shop["slots"][0]}, claim
    if tool == "accept_deductible":
        return f"/claims/{cid}/deductible", {}, claim
    if tool == "sign_claim":
        # sign needs repair + deductible first: grant those two properly.
        for path, body in ((f"/claims/{cid}/repair", {"shop_id": shop["shop_id"], "slot": shop["slots"][0]}), (f"/claims/{cid}/deductible", {})):
            pending = (await http.post(path, json=body)).json()
            await approve(http, pending["approval_id"])
            assert (await http.post(path, json={**body, "approval_id": pending["approval_id"]})).status_code == 200
        return f"/claims/{cid}/sign", {}, claim
    if tool == "request_rental_car":
        # Only gated when the policy does not cover a rental car.
        await services.repo.store.update(POLICIES, POLICY["policy_id"], {"coverages": ["own_damage", "towing"]})
        return f"/claims/{cid}/rental", {}, claim
    raise AssertionError(tool)


@pytest.mark.parametrize("tool", GATED)
async def test_rest_gated_action_needs_valid_approval(http, services, tool):
    path, body, claim = await prepare(http, services, tool)
    before = (await http.get(f"/claims/{claim['claim_id']}")).json()

    # 1. No approval_id: 202 pending_approval, nothing changes, a push goes out.
    r = await http.post(path, json=body)
    assert r.status_code == 202, r.text
    pending = r.json()
    assert pending["status"] == "pending_approval" and pending["tool"] == tool and pending["approval_id"].startswith("apr_")
    assert services.push.sent[-1].approval_id == pending["approval_id"]
    after = (await http.get(f"/claims/{claim['claim_id']}")).json()
    for key in ("status", "repair", "deductible_accepted", "signed_at", "rental"):
        assert after.get(key) == before.get(key), key

    # 2. Made-up id.
    r = await http.post(path, json={**body, "approval_id": "apr_made_up"})
    assert r.status_code == 403 and r.json()["detail"] == "unknown"

    # 3. Real id, but the customer has not approved yet.
    r = await http.post(path, json={**body, "approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "not_approved"

    # 4. Approved -> works once.
    await approve(http, pending["approval_id"])
    r = await http.post(path, json={**body, "approval_id": pending["approval_id"]})
    assert r.status_code == 200, r.text

    # 5. Reused id -> rejected (single use).
    r = await http.post(path, json={**body, "approval_id": pending["approval_id"]})
    assert r.status_code in (403, 409), r.text
    if r.status_code == 403:
        assert r.json()["detail"] == "already_used"


async def test_approval_for_another_tool_is_rejected(http, services):
    claim = await ready_claim(http)
    cid = claim["claim_id"]
    pending = (await http.post(f"/claims/{cid}/deductible", json={})).json()
    await approve(http, pending["approval_id"])
    shop = claim["options"][0]
    r = await http.post(f"/claims/{cid}/repair", json={"shop_id": shop["shop_id"], "slot": shop["slots"][0], "approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "wrong_tool"
    # The misuse did not burn it: the right action can still use it.
    assert (await http.post(f"/claims/{cid}/deductible", json={"approval_id": pending["approval_id"]})).status_code == 200


async def test_approval_for_other_arguments_is_rejected(http, services):
    claim = await ready_claim(http)
    cid = claim["claim_id"]
    a, b = claim["options"][0], claim["options"][1]
    pending = (await http.post(f"/claims/{cid}/repair", json={"shop_id": a["shop_id"], "slot": a["slots"][0]})).json()
    await approve(http, pending["approval_id"])
    r = await http.post(f"/claims/{cid}/repair", json={"shop_id": b["shop_id"], "slot": b["slots"][0], "approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "wrong_args"
    r = await http.post(f"/claims/{cid}/repair", json={"shop_id": a["shop_id"], "slot": a["slots"][1], "approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "wrong_args"
    # Same claim, other claim: an approval is bound to its claim too.
    other = await ready_claim(http)
    r = await http.post(f"/claims/{other['claim_id']}/repair", json={"shop_id": a["shop_id"], "slot": a["slots"][0], "approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "wrong_args"


async def test_rejected_approval_cannot_be_used(http):
    claim = await ready_claim(http)
    path = f"/claims/{claim['claim_id']}/deductible"
    pending = (await http.post(path, json={})).json()
    assert (await http.post(f"/approvals/{pending['approval_id']}/reject")).status_code == 200
    r = await http.post(path, json={"approval_id": pending["approval_id"]})
    assert r.status_code == 403 and r.json()["detail"] == "rejected"


async def test_approve_endpoint_status_codes(http):
    assert (await http.post("/approvals/apr_nope/approve")).status_code == 404
    claim = await ready_claim(http)
    pending = (await http.post(f"/claims/{claim['claim_id']}/deductible", json={})).json()
    listed = (await http.get("/approvals")).json()
    assert [a["approval_id"] for a in listed] == [pending["approval_id"]]
    assert listed[0]["summary"].startswith("Aceptar pagar el deducible")
    await approve(http, pending["approval_id"])
    assert (await http.post(f"/approvals/{pending['approval_id']}/approve")).status_code == 409
    assert (await http.get("/approvals")).json() == []


async def test_mcp_gated_tools_reject_invalid_approvals(http, mcp):
    claim = await ready_claim(http)
    cid = claim["claim_id"]
    shop = claim["options"][0]
    repair_args = {"claim_id": cid, "shop_id": shop["shop_id"], "slot": shop["slots"][0]}

    first = mcp_payload(await mcp.call_tool("select_repair_shop", repair_args))
    assert first["status"] == "pending_approval"
    apr = first["approval_id"]

    assert "approval_invalid (unknown)" in mcp_error(await mcp.call_tool("select_repair_shop", {**repair_args, "approval_id": "apr_fake"}))
    assert "approval_invalid (not_approved)" in mcp_error(await mcp.call_tool("select_repair_shop", {**repair_args, "approval_id": apr}))
    await approve(http, apr)
    assert "approval_invalid (wrong_tool)" in mcp_error(await mcp.call_tool("accept_deductible", {"claim_id": cid, "approval_id": apr}))
    other_slot = {**repair_args, "slot": shop["slots"][1], "approval_id": apr}
    assert "approval_invalid (wrong_args)" in mcp_error(await mcp.call_tool("select_repair_shop", other_slot))
    booked = mcp_payload(await mcp.call_tool("select_repair_shop", {**repair_args, "approval_id": apr}))
    assert booked["shop_id"] == shop["shop_id"]
    # used: the precheck now reports the booking, or the approval reports reuse — either way, refused.
    assert mcp_error(await mcp.call_tool("select_repair_shop", {**repair_args, "approval_id": apr}))

    for tool in ("accept_deductible", "sign_claim"):
        result = await mcp.call_tool(tool, {"claim_id": cid, "approval_id": "apr_fake"})
        assert result.is_error


async def test_no_approve_tool_anywhere(mcp):
    names = {t.name for t in (await mcp.list_tools()).tools}
    assert not any("approv" in n for n in names)
