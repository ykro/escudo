"""Customer identity: Firebase ID tokens (MOCK_AUTH=1 accepts anything, for local tests).

Every surface acts as the customer named in the verified token: the seeded login
(`make seed`) carries a `customer_id` custom claim; a user without that claim is
their own customer (uid), so they see no policy and no claims. Only MOCK_AUTH=1
(local tests) maps every caller to the demo customer. The token still matters — it is what makes
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
            try:
                claims = await self._verified_claims(token)
            except Exception as exc:  # expired, revoked, wrong project, malformed...
                raise Unauthorized("invalid_token", "Tu sesión expiró o no es válida. Vuelve a iniciar sesión.") from exc
            uid = claims["uid"]
            customer_id = claims.get("customer_id") or uid
            self._cache[token] = (min(claims.get("exp", 0), time.time() + 300), f"{uid}|{customer_id}")
        uid, _, customer_id = self._cache[token][1].partition("|")
        return Identity(uid=uid, customer_id=customer_id)

    async def _verified_claims(self, token: str) -> dict:
        from firebase_admin import auth

        return await asyncio.to_thread(auth.verify_id_token, token)


    # --- per-run customers (POST /admin/runs) ----------------------------------------------
    async def create_run_user(self, run_id: str, customer_id: str) -> tuple[str, str]:
        """A Firebase email/password user whose token acts as the run's own customer."""
        import secrets

        email, password = f"{run_id}@escudo-demo.example", secrets.token_urlsafe(14)
        if self._settings.mock_auth:
            return email, password
        from firebase_admin import auth

        def create() -> None:
            uid = f"run_{run_id}"
            auth.create_user(uid=uid, email=email, password=password, email_verified=True, display_name=f"Corrida {run_id}")
            auth.set_custom_user_claims(uid, {"customer_id": customer_id})

        await asyncio.to_thread(create)
        return email, password

    async def delete_run_users(self, uids: list[str]) -> None:
        if self._settings.mock_auth or not uids:
            return
        from firebase_admin import auth

        # Only ids we created (run_…): the Firebase project is shared with other demos.
        await asyncio.to_thread(auth.delete_users, [u for u in uids if u.startswith("run_")])


def bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip() or None
    return None
