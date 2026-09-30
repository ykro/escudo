"""FCM push to the customer's phone when an agent needs their approval."""

from __future__ import annotations

import asyncio
import logging
from typing import Protocol

from .models import Approval
from .repository import Repository

log = logging.getLogger(__name__)


class Push(Protocol):
    async def approval_requested(self, approval: Approval) -> int: ...


class RecordingPush:
    """Test/mock implementation: remembers what would have been sent."""

    def __init__(self) -> None:
        self.sent: list[Approval] = []

    async def approval_requested(self, approval: Approval) -> int:
        self.sent.append(approval)
        return 0


class FcmPush:
    def __init__(self, repo: Repository, project_id: str) -> None:
        import firebase_admin
        from firebase_admin import messaging

        if not firebase_admin._apps:
            firebase_admin.initialize_app(options={"projectId": project_id})
        self._messaging = messaging
        self._repo = repo

    async def approval_requested(self, approval: Approval) -> int:
        devices = [d for d in await self._repo.list_devices(approval.customer_id) if not d.get("disabled")]
        tokens = [d["fcm_token"] for d in devices]
        if not tokens:
            log.info("no devices registered for %s; approval %s waits in the app", approval.customer_id, approval.approval_id)
            return 0
        m = self._messaging
        message = m.MulticastMessage(
            tokens=tokens,
            notification=m.Notification(title="Escudo necesita tu aprobación", body=approval.summary),
            data={
                "type": "approval_request",
                "approval_id": approval.approval_id,
                "tool": approval.tool,
                "claim_id": approval.claim_id or "",
                "reason": approval.reason,
            },
            android=m.AndroidConfig(priority="high", notification=m.AndroidNotification(channel_id="approvals")),
        )
        response = await asyncio.to_thread(m.send_each_for_multicast, message)
        for device, result in zip(devices, response.responses):
            if not result.success:
                log.warning("FCM send failed for %s: %s", device.get("device_key"), result.exception)
                if isinstance(result.exception, m.UnregisteredError):
                    await self._repo.remove_device(device["device_key"])
        return response.success_count
