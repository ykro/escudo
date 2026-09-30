"""The full scenario over MCP, offline: signed claim, exactly three approvals, well under 3 minutes."""

from __future__ import annotations

import pytest

import io

from escudo.scenario import BUDGET_SECONDS, EXPECTED_APPROVALS, run_scenario
from helpers import photo_b64


pytestmark = pytest.mark.anyio

async def test_scenario_via_mcp(http, mcp, services):
    async def approve(approval_id: str) -> None:
        r = await http.post(f"/approvals/{approval_id}/approve", json={"device_id": "test"})
        assert r.status_code == 200

    out = io.StringIO()
    report = await run_scenario(mcp, approve, [photo_b64(), photo_b64()], out=out)
    assert len(report.approvals) == EXPECTED_APPROVALS
    assert report.total < BUDGET_SECONDS
    assert len(services.push.sent) == EXPECTED_APPROVALS
    tools = [a.tool for a in services.push.sent]
    assert tools == ["select_repair_shop", "accept_deductible", "sign_claim"]
