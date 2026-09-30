"""One autonomy table covers every capability, and approving is never a tool."""

from __future__ import annotations

import pytest

import re
from pathlib import Path

from escudo import approvals
from escudo.autonomy import AUTONOMY, Level
from escudo.capabilities import REGISTRY
from escudo.errors import ApprovalInvalid
from escudo.repository.repository import POLICIES
from escudo.seed import POLICY
from helpers import approve, ready_claim

pytestmark = pytest.mark.anyio

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def test_table_covers_exactly_the_capabilities():
    assert set(AUTONOMY) == set(REGISTRY)
    tools_md = (CONTRACTS / "tools.md").read_text().split("## Approval protocol")[0]  # the capabilities table
    documented = set(re.findall(r"^\| `(\w+)` \|", tools_md, re.MULTILINE))
    assert documented == set(REGISTRY)


def test_gated_capabilities_match_the_contract():
    gated = {name for name, rule in AUTONOMY.items() if rule.level is Level.NEEDS_APPROVAL}
    assert gated == {"accept_deductible", "select_repair_shop", "sign_claim", "request_rental_car"}
    assert AUTONOMY["request_rental_car"].runs_alone_if is not None  # automatic if covered
    for name in gated:
        assert REGISTRY[name].describe_approval is not None, name


def test_approving_is_not_a_capability():
    assert not any("approv" in name for name in REGISTRY)


async def test_mcp_exposes_exactly_the_registry(mcp):
    tools = {t.name: t for t in (await mcp.list_tools()).tools}
    assert set(tools) == set(REGISTRY)
    for name, rule in AUTONOMY.items():
        has_approval_arg = "approval_id" in tools[name].input_schema.get("properties", {})
        assert has_approval_arg == (rule.level is Level.NEEDS_APPROVAL), name


async def test_rental_is_automatic_when_covered_and_gated_when_not(http, services):
    claim = await ready_claim(http)
    r = await http.post(f"/claims/{claim['claim_id']}/rental")
    assert r.status_code == 200 and r.json()["covered"] is True

    await services.repo.store.update(POLICIES, POLICY["policy_id"], {"coverages": ["own_damage", "towing"]})
    other = await ready_claim(http)
    r = await http.post(f"/claims/{other['claim_id']}/rental")
    assert r.status_code == 202 and r.json()["tool"] == "request_rental_car"


async def test_redeem_is_single_use(http, services):
    claim = await ready_claim(http)
    pending = (await http.post(f"/claims/{claim['claim_id']}/deductible", json={})).json()
    await approve(http, pending["approval_id"])
    args = {"claim_id": claim["claim_id"]}
    used = await approvals.redeem(services.repo, pending["approval_id"], tool="accept_deductible", args=args, customer_id="cus_demo")
    assert used.status == "used"
    try:
        await approvals.redeem(services.repo, pending["approval_id"], tool="accept_deductible", args=args, customer_id="cus_demo")
    except ApprovalInvalid as exc:
        assert exc.detail == "already_used"
    else:
        raise AssertionError("an approval was redeemed twice")


def test_args_hash_is_canonical():
    h = approvals.args_hash
    assert h("t", {"a": 1, "b": 2}) == h("t", {"b": 2, "a": 1}) == h("t", {"a": 1, "b": 2, "approval_id": "x", "c": None})
    assert h("t", {"a": 1}) != h("u", {"a": 1}) != h("t", {"a": 2})
