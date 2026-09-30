from __future__ import annotations

import base64
import json
from typing import Any

from conftest import tiny_jpeg


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


async def approve(http, approval_id: str) -> None:  # noqa: ANN001
    r = await http.post(f"/approvals/{approval_id}/approve", json={"device_id": "test-phone"})
    assert r.status_code == 200, r.text


def photo_b64() -> str:
    return base64.b64encode(tiny_jpeg()).decode()


def mcp_payload(result) -> dict[str, Any]:  # noqa: ANN001
    assert not result.is_error, result.content
    return json.loads(result.content[0].text)


def mcp_error(result) -> str:  # noqa: ANN001
    assert result.is_error, result.content
    return result.content[0].text
