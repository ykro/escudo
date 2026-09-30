"""Every event written matches contracts/events.schema.json."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker

from escudo.errors import InvalidInput
from escudo.repository.repository import EVENTS
from helpers import approve, ready_claim

pytestmark = pytest.mark.anyio

SCHEMA = json.loads((Path(__file__).resolve().parents[2] / "contracts" / "events.schema.json").read_text())


async def test_all_events_of_a_flow_are_valid(http, services):
    claim = await ready_claim(http)
    cid = claim["claim_id"]
    await http.post(f"/claims/{cid}/tow")
    pending = (await http.post(f"/claims/{cid}/deductible", json={})).json()
    await approve(http, pending["approval_id"])
    await http.post(f"/claims/{cid}/deductible", json={"approval_id": pending["approval_id"]})
    await http.post(f"/claims/{cid}/sign", json={"approval_id": "apr_bad"})

    events = await services.repo.store.where(EVENTS)
    validator = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
    for event in events:
        assert not list(validator.iter_errors(event)), event
    kinds = {e["kind"] for e in events}
    assert {"tool_call", "tool_result", "a2a_request", "a2a_response", "approval_requested", "approval_granted", "error", "message"} <= kinds
    paused = next(e for e in events if e["kind"] == "approval_requested")
    assert paused["reason"] and paused["approval_id"] == pending["approval_id"]


async def test_clients_can_post_valid_events(http, services):
    ok = {"run_id": "r1", "actor": "customer", "surface": "android", "kind": "message", "summary": "Abrió la app"}
    assert (await http.post("/events", json=ok)).status_code == 204
    bad = {**ok, "surface": "fax"}
    r = await http.post("/events", json=bad)
    assert r.status_code == 422 and r.json()["error"] == "invalid_event"
    extra = {**ok, "unexpected": 1}
    assert (await http.post("/events", json=extra)).status_code == 422


async def test_writer_refuses_malformed_events(services):
    with pytest.raises(InvalidInput):
        await services.events.emit(actor="robot", surface="mcp", kind="message", summary="x", run_id="r")


async def test_caller_headers_label_the_timeline(http, services):
    """Android sends X-Escudo-Surface/Actor instead of posting duplicate tool events."""
    headers = {"X-Escudo-Surface": "appfunctions", "X-Escudo-Actor": "gemini", "X-Escudo-Run-Id": "run-42"}
    claim = (await http.post("/claims", json={"description": "Choque menor"}, headers=headers)).json()
    await http.get(f"/claims/{claim['claim_id']}", headers={"X-Escudo-Surface": "fax", "X-Escudo-Actor": "robot"})
    events = await services.repo.store.where(EVENTS)
    opened = [e for e in events if e["tool"] == "open_claim"]
    assert {(e["kind"], e["surface"], e["actor"], e["run_id"]) for e in opened} == {
        ("tool_call", "appfunctions", "gemini", "run-42"),
        ("tool_result", "appfunctions", "gemini", "run-42"),
    }
    status = [e for e in events if e["tool"] == "get_claim_status"]
    assert status and all(e["surface"] == "rest" and e["actor"] == "customer" for e in status)  # unknown labels fall back
