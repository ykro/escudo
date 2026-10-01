"""Typed queries over the document store. One place for every collection name."""

from __future__ import annotations

from typing import Any

from ..errors import NotFound
from ..models import Approval, Claim, Policy
from .documents import DocumentStore, Mutation

CUSTOMERS = "customers"
POLICIES = "policies"
SHOPS = "repair_shops"
TOW_PROVIDERS = "tow_providers"
CLAIMS = "claims"
APPROVALS = "approvals"
EVENTS = "events"
DEVICES = "devices"
DEVICE_AUDIT = "device_audit"

# What `make reset` wipes. Devices survive a reset on purpose: the presenter's
# phone registers its FCM token once, before the talk.
RESETTABLE = (CLAIMS, APPROVALS, EVENTS, POLICIES, CUSTOMERS, SHOPS, TOW_PROVIDERS)


class Repository:
    def __init__(self, store: DocumentStore) -> None:
        self.store = store

    # --- customers and policies -------------------------------------------------
    async def get_policy_for_customer(self, customer_id: str) -> Policy:
        docs = await self.store.where(POLICIES, customer_id=customer_id)
        if not docs:
            raise NotFound("policy_not_found", "No encontramos una póliza activa para este cliente.")
        return Policy.model_validate(docs[0])

    async def list_shops(self) -> list[dict[str, Any]]:
        return sorted(await self.store.where(SHOPS), key=lambda s: s["shop_id"])

    async def list_tow_providers(self) -> list[dict[str, Any]]:
        return sorted(await self.store.where(TOW_PROVIDERS), key=lambda p: p["provider_id"])

    # --- claims -------------------------------------------------------------------
    async def create_claim(self, claim: Claim) -> Claim:
        await self.store.set(CLAIMS, claim.claim_id, claim.model_dump(mode="json"))
        return claim

    async def get_claim(self, claim_id: str, customer_id: str) -> Claim:
        doc = await self.store.get(CLAIMS, claim_id)
        # Another customer's claim is reported as missing, not as forbidden.
        if not doc or doc.get("customer_id") != customer_id:
            raise NotFound("claim_not_found", f"No encontramos el reclamo {claim_id}.")
        return Claim.model_validate(doc)

    async def latest_claim(self, customer_id: str) -> Claim:
        docs = await self.store.where(CLAIMS, customer_id=customer_id)
        if not docs:
            raise NotFound("no_claims", "Todavía no tienes reclamos.")
        return Claim.model_validate(max(docs, key=lambda d: d["created_at"]))

    async def update_claim(self, claim_id: str, patch: dict[str, Any]) -> None:
        await self.store.update(CLAIMS, claim_id, patch)

    # --- approvals ----------------------------------------------------------------
    async def create_approval(self, approval: Approval) -> Approval:
        await self.store.set(APPROVALS, approval.approval_id, approval.model_dump(mode="json"))
        return approval

    async def get_approval(self, approval_id: str) -> Approval | None:
        doc = await self.store.get(APPROVALS, approval_id)
        return Approval.model_validate(doc) if doc else None

    async def list_approvals(self, customer_id: str, status: str | None = None) -> list[Approval]:
        filters: dict[str, Any] = {"customer_id": customer_id}
        if status:
            filters["status"] = status
        docs = await self.store.where(APPROVALS, **filters)
        return sorted((Approval.model_validate(d) for d in docs), key=lambda a: a.created_at)

    async def mutate_approval(self, approval_id: str, mutation: Mutation) -> Approval:
        return Approval.model_validate(await self.store.atomic_update(APPROVALS, approval_id, mutation))

    # --- events and devices -------------------------------------------------------
    async def add_event(self, event: dict[str, Any]) -> None:
        await self.store.set(EVENTS, event["id"], event)

    async def list_events(self, claim_id: str) -> list[dict[str, Any]]:
        return sorted(await self.store.where(EVENTS, claim_id=claim_id), key=lambda e: e["ts"])

    async def register_device(self, device_key: str, data: dict[str, Any]) -> None:
        await self.store.set(DEVICES, device_key, data)

    async def get_device(self, device_id: str) -> dict[str, Any] | None:
        return await self.store.get(DEVICES, device_id)

    async def add_device_audit(self, record: dict[str, Any]) -> None:
        from ..models import new_event_id

        await self.store.set(DEVICE_AUDIT, new_event_id().replace("evt_", "aud_"), record)

    async def list_devices(self, customer_id: str) -> list[dict[str, Any]]:
        return await self.store.where(DEVICES, customer_id=customer_id)

    async def remove_device(self, device_key: str) -> None:
        await self.store.update(DEVICES, device_key, {"disabled": True})
