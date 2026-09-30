"""Domain records shared by every adapter. Shapes follow contracts/openapi.yaml."""

from __future__ import annotations

import secrets
import string
import uuid
from datetime import datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

Coverage = Literal["own_damage", "third_party", "towing", "rental_car", "theft"]
ClaimStatus = Literal["open", "assessed", "tow_requested", "repair_selected", "deductible_accepted", "signed"]
ApprovalStatus = Literal["pending", "approved", "rejected", "used"]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def new_claim_id() -> str:
    alphabet = string.ascii_uppercase + string.digits
    return "CLM-" + "".join(secrets.choice(alphabet) for _ in range(6))


def new_approval_id() -> str:
    return "apr_" + secrets.token_urlsafe(12)


def new_event_id() -> str:
    return "evt_" + uuid.uuid4().hex


class Record(BaseModel):
    model_config = ConfigDict(extra="allow")


class Location(BaseModel):
    lat: float | None = None
    lng: float | None = None
    label: str | None = None


class Vehicle(BaseModel):
    make: str
    model: str
    year: int
    plate: str


class Holder(BaseModel):
    name: str
    customer_id: str


class Policy(Record):
    policy_id: str
    holder: Holder
    vehicle: Vehicle
    coverages: list[Coverage]
    deductible_usd: float
    rental_days: int
    status: Literal["active", "suspended"]


class DamagedPart(BaseModel):
    name: str = Field(description="Part name in Spanish, e.g. 'parachoques trasero'")
    damage: str = Field(description="Short description of the damage in Spanish")


class DamageAssessment(BaseModel):
    parts: list[DamagedPart]
    severity: Literal["minor", "moderate", "severe"]
    cost_range_usd: list[float] = Field(min_length=2, max_length=2, description="[low, high] repair cost in USD")
    drivable: bool
    summary: str = Field(default="", description="One sentence in Spanish for the customer")


class TowDispatch(BaseModel):
    provider_id: str
    provider: str
    eta_minutes: int
    phone: str | None = None


class RepairOption(BaseModel):
    shop_id: str
    name: str
    address: str
    rating: float
    slots: list[str]
    quote_usd: float


class Claim(Record):
    claim_id: str
    customer_id: str
    policy_id: str
    status: ClaimStatus
    description: str
    location: Location | None = None
    occurred_at: str | None = None
    created_at: str
    photos: list[str] = []
    assessment: DamageAssessment | None = None
    tow: TowDispatch | None = None
    repair_options: list[RepairOption] = []
    repair: dict[str, Any] | None = None
    rental: dict[str, Any] | None = None
    deductible_accepted: bool = False
    signed_at: str | None = None


class Approval(Record):
    approval_id: str
    customer_id: str
    claim_id: str | None = None
    tool: str
    args_hash: str
    reason: str
    summary: str
    amount_usd: float | None = None
    status: ApprovalStatus = "pending"
    created_at: str
    decided_at: str | None = None
    used_at: str | None = None
    device_id: str | None = None


class PendingApproval(BaseModel):
    status: Literal["pending_approval"] = "pending_approval"
    approval_id: str
    tool: str
    reason: str
    args_hash: str
    summary: str
    message: str = "Le pedí tu aprobación en la app de Escudo. Cuando la apruebes, repite la acción con el approval_id."
