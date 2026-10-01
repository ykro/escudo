from __future__ import annotations

import base64
import json
from typing import Any

from conftest import tiny_jpeg
from escudo.test_device import TestDevice


async def ready_claim(http) -> dict[str, Any]:  # noqa: ANN001
    """A claim with photos assessed and repair options offered (ready for the gated actions)."""
    r = await http.post("/claims", json={"description": "Choque menor atrás", "location": {"lat": 14.6, "lng": -90.51, "label": "zona 10"}})
    assert r.status_code == 201, r.text
    claim = r.json()
    r = await http.post(f"/claims/{claim['claim_id']}/photos", files=[("photos", ("a.jpg", tiny_jpeg(), "image/jpeg"))])
    assert r.status_code == 200, r.text
    r = await http.get(f"/claims/{claim['claim_id']}/repair-options")
    assert r.status_code == 200, r.text
    claim["options"] = r.json()
    return claim


PHONE = TestDevice("test-phone")  # plays the customer's registered phone (escudo-approval:v1)


async def register_phone(http, device: TestDevice = PHONE) -> TestDevice:  # noqa: ANN001
    r = await http.post("/devices", json=device.registration())
    assert r.status_code == 204, r.text
    return device


async def args_hash_of(http, approval_id: str) -> str:  # noqa: ANN001
    pending = (await http.get("/approvals")).json()
    return next(a["args_hash"] for a in pending if a["approval_id"] == approval_id)


async def decide(http, approval_id: str, decision: str = "approve"):  # noqa: ANN001, ANN201
    """The customer decides on her phone: registered device + signature over approval_id and args_hash."""
    device = await register_phone(http)
    body = device.decision(approval_id, await args_hash_of(http, approval_id), decision)  # type: ignore[arg-type]
    return await http.post(f"/approvals/{approval_id}/{decision}", json=body)


async def approve(http, approval_id: str) -> None:  # noqa: ANN001
    r = await decide(http, approval_id)
    assert r.status_code == 200, r.text


def photo_b64() -> str:
    return base64.b64encode(tiny_jpeg()).decode()


def mcp_payload(result) -> dict[str, Any]:  # noqa: ANN001
    assert not result.is_error, result.content
    return json.loads(result.content[0].text)


def mcp_error(result) -> str:  # noqa: ANN001
    assert result.is_error, result.content
    return result.content[0].text
