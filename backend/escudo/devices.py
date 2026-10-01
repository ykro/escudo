"""Device-bound approvals (`escudo-approval:v1`, contracts/tools.md).

The customer's token says *who*; a signature from a key that lives in her phone's Keystore (unlocked
by BiometricPrompt) says *she, on her phone, just now*. An agent that holds the token — MCP, voice,
A2A — has no such key, so it cannot approve on her behalf.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import logging
from typing import Any, Literal

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

from .errors import EscudoError, InvalidInput
from .models import now_iso
from .repository import Repository

log = logging.getLogger(__name__)

PROTOCOL = "escudo-approval:v1"
Decision = Literal["approve", "reject"]


class ApprovalForbidden(EscudoError):
    """The decision did not come from the customer's registered device. Nothing changed."""

    status = 403

    def __init__(self, detail: str, message: str) -> None:
        super().__init__("approval_forbidden", message)
        self.detail = detail

    def to_dict(self) -> dict[str, str]:
        return {"error": self.code, "detail": self.detail, "message": self.message}


class DeviceOwnedByAnother(EscudoError):
    status = 403


def signed_message(approval_id: str, args_hash: str, decision: Decision) -> bytes:
    """Exactly what the device signs: binds approval, arguments and the decision itself."""
    return f"{PROTOCOL}:{approval_id}:{args_hash}:{decision}".encode()


def load_public_key(public_key_b64: str) -> ec.EllipticCurvePublicKey:
    try:
        key = load_der_public_key(base64.b64decode(public_key_b64, validate=True))
    except (ValueError, binascii.Error, TypeError) as exc:
        raise InvalidInput("invalid_public_key", "La llave pública debe ser SubjectPublicKeyInfo DER en base64.") from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise InvalidInput("invalid_public_key", "La llave del dispositivo debe ser EC P-256.")
    return key


def fingerprint(public_key_b64: str) -> str:
    return "sha256:" + hashlib.sha256(base64.b64decode(public_key_b64)).hexdigest()[:32]


def verify(public_key_b64: str, signature_b64: str, message: bytes) -> bool:
    try:
        load_public_key(public_key_b64).verify(base64.b64decode(signature_b64, validate=True), message, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError, binascii.Error, TypeError, InvalidInput):
        return False


async def register(repo: Repository, *, customer_id: str, uid: str, device_id: str, fcm_token: str,
                   platform: str | None, public_key: str) -> None:
    load_public_key(public_key)  # 422 before touching anything
    existing = await repo.get_device(device_id)
    if existing and existing.get("customer_id") != customer_id:
        raise DeviceOwnedByAnother("device_owned_by_another", "Ese dispositivo está registrado a otra cuenta.")
    if existing and existing.get("public_key") and existing["public_key"] != public_key:
        # A new key for a known device (reinstall, wiped Keystore… or an attacker): replace, but leave a trace.
        old, new = fingerprint(existing["public_key"]), fingerprint(public_key)
        log.warning("device %s of %s changed approval key %s -> %s", device_id, customer_id, old, new)
        await repo.add_device_audit({"device_id": device_id, "customer_id": customer_id, "uid": uid,
                                     "event": "public_key_replaced", "old_fingerprint": old, "new_fingerprint": new,
                                     "at": now_iso()})
    await repo.register_device(device_id, {
        "device_key": device_id, "device_id": device_id, "customer_id": customer_id, "uid": uid,
        "fcm_token": fcm_token, "platform": platform, "public_key": public_key,
        "key_fingerprint": fingerprint(public_key), "updated_at": now_iso(), "disabled": False,
    })


async def check_decision(repo: Repository, approval: Any, *, customer_id: str, device_id: str | None,
                         signature: str | None, decision: Decision) -> dict[str, Any]:
    """Raises ApprovalForbidden unless a registered, enabled device of this customer signed this decision."""
    if approval is None or approval.customer_id != customer_id:
        raise ApprovalForbidden("not_your_approval", "Esa aprobación no es tuya.")
    if not device_id:
        raise ApprovalForbidden("no_device", "Aprueba desde la app de Escudo en tu teléfono.")
    device = await repo.get_device(device_id)
    if device is None or device.get("customer_id") != customer_id or not device.get("public_key"):
        raise ApprovalForbidden("unknown_device", "Ese dispositivo no está registrado en tu cuenta.")
    if device.get("disabled"):
        raise ApprovalForbidden("device_disabled", "Ese dispositivo está desactivado. Vuelve a abrir la app para registrarlo.")
    if not signature or not verify(device["public_key"], signature, signed_message(approval.approval_id, approval.args_hash, decision)):
        raise ApprovalForbidden("bad_signature", "La firma del dispositivo no es válida para esta aprobación.")
    return device
