"""Customer identity: Firebase ID tokens (MOCK_AUTH=1 accepts anything, for local tests).

Escudo is a one-customer demo. The seeded login (`make seed`) carries a
`customer_id` custom claim; any other verified Firebase user (anonymous too)
acts as the seeded policy holder (`DEMO_CUSTOMER_ID`). The token still matters — it is what makes
an MCP client, the assistant or the app act *as the customer*.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass

from .config import Settings
from .errors import Unauthorized

MOCK_UID = "mock-user"


@dataclass(frozen=True)
class Identity:
    uid: str
    customer_id: str


class Authenticator:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._cache: dict[str, tuple[float, str]] = {}
        if not settings.mock_auth:
            import firebase_admin

            if not firebase_admin._apps:
                firebase_admin.initialize_app(options={"projectId": settings.project_id})

    @property
    def mock(self) -> bool:
        return self._settings.mock_auth

    async def verify(self, token: str | None) -> Identity:
        if self._settings.mock_auth:
            return Identity(uid=MOCK_UID, customer_id=self._settings.demo_customer_id)
        if not token:
            raise Unauthorized("unauthenticated", "Inicia sesión en Escudo para continuar.")
        cached = self._cache.get(token)
        if not (cached and cached[0] > time.time()):
            from firebase_admin import auth

            try:
                claims = await asyncio.to_thread(auth.verify_id_token, token)
            except Exception as exc:  # expired, revoked, wrong project, malformed...
                raise Unauthorized("invalid_token", "Tu sesión expiró o no es válida. Vuelve a iniciar sesión.") from exc
            uid = claims["uid"]
            # The seeded login carries a customer_id claim (make seed); any other user
            # (e.g. anonymous, for the scenario runner) acts as the demo customer.
            customer_id = claims.get("customer_id") or self._settings.demo_customer_id
            self._cache[token] = (min(claims.get("exp", 0), time.time() + 300), f"{uid}|{customer_id}")
        uid, _, customer_id = self._cache[token][1].partition("|")
        return Identity(uid=uid, customer_id=customer_id)


def bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip() or None
    return None
