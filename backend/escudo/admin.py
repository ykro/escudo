"""Presenter tools against the configured store (Firestore unless STORE=memory).

    uv run python -m escudo.admin seed     # upsert policy, shops, tow providers + the customer's Firebase login
    uv run python -m escudo.admin reset    # clear claims/approvals/events + seed (< 10 s)

For a running MOCK_AI server (in-memory store) use `make reset`, which calls POST /admin/reset.
"""

from __future__ import annotations

import asyncio
import os
import sys

from .config import Settings
from .repository import FirestoreDocumentStore, Repository
from .customer_login import ENV_LOCAL, ensure_customer_login
from .seed import reset, write_seed

DEPLOYED_URL = "https://escudo-api-611681112050.us-central1.run.app"


async def main(command: str) -> None:
    settings = Settings.from_env()
    # Local user ADC needs a quota project for the Identity Toolkit (Firebase Auth admin) API.
    os.environ.setdefault("GOOGLE_CLOUD_QUOTA_PROJECT", settings.project_id)
    repo = Repository(FirestoreDocumentStore(settings.project_id, settings.firestore_database))
    if command == "seed":
        await write_seed(repo)
        print(f"seed escrito en Firestore db '{settings.firestore_database}'")
        email, _ = ensure_customer_login(settings.project_id, DEPLOYED_URL)
        print(f"cliente en Firebase Auth: {email} (contraseña en {ENV_LOCAL})")
    elif command == "reset":
        seconds = await reset(repo)
        print(f"reset listo en {seconds:.1f} s (db '{settings.firestore_database}')")
    else:
        raise SystemExit("uso: python -m escudo.admin seed|reset")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else ""))
