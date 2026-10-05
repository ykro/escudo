"""A software device that plays the customer's phone in tests and rehearsals (escudo-approval:v1).

The real app keeps its P-256 key in the Android Keystore behind BiometricPrompt. This one keeps it in
memory (a fresh key per run), registers with `platform: "test"` (never pushed to), and
signs exactly the same string — so the server checks it exactly like the phone.
"""

from __future__ import annotations

import base64
from typing import Any

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from .devices import Decision, signed_message


class TestDevice:
    __test__ = False  # not a pytest class

    def __init__(self, device_id: str = "scenario-runner", key: ec.EllipticCurvePrivateKey | None = None) -> None:
        self.device_id = device_id
        self.key = key or ec.generate_private_key(ec.SECP256R1())

    @property
    def public_key(self) -> str:
        der = self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        return base64.b64encode(der).decode()

    def registration(self) -> dict[str, Any]:
        return {"device_id": self.device_id, "fcm_token": f"{self.device_id}-no-push", "platform": "test",
                "public_key": self.public_key}

    def sign(self, approval_id: str, args_hash: str, decision: Decision = "approve") -> str:
        signature = self.key.sign(signed_message(approval_id, args_hash, decision), ec.ECDSA(hashes.SHA256()))
        return base64.b64encode(signature).decode()

    def decision(self, approval_id: str, args_hash: str, decision: Decision = "approve") -> dict[str, str]:
        return {"device_id": self.device_id, "signature": self.sign(approval_id, args_hash, decision)}
