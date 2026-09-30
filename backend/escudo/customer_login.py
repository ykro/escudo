"""The seeded customer's Firebase Auth account (email + password), for the Android app.

`make seed` creates or updates it with the Admin SDK and links it to the policy
through a `customer_id` custom claim. Credentials go to the gitignored
`escudo/.env.local`, from where they are copied into `android/local.properties`.
"""

from __future__ import annotations

import secrets
from pathlib import Path

from .seed import CUSTOMER

ENV_LOCAL = Path(__file__).resolve().parents[2] / ".env.local"
UID = "cus_demo_login"


def _read_env_local() -> dict[str, str]:
    if not ENV_LOCAL.exists():
        return {}
    pairs = (line.split("=", 1) for line in ENV_LOCAL.read_text().splitlines() if "=" in line and not line.startswith("#"))
    return {k.strip(): v.strip() for k, v in pairs}


def ensure_customer_login(project_id: str, api_url: str) -> tuple[str, str]:
    import firebase_admin
    from firebase_admin import auth

    if not firebase_admin._apps:
        firebase_admin.initialize_app(options={"projectId": project_id})
    email = CUSTOMER["email"]
    # Keep the same password across `make seed` runs so the installed app keeps working.
    password = _read_env_local().get("ESCUDO_CUSTOMER_PASSWORD") or secrets.token_urlsafe(12)
    try:
        auth.get_user(UID)
        auth.update_user(UID, email=email, password=password, display_name=CUSTOMER["name"], email_verified=True, disabled=False)
    except auth.UserNotFoundError:
        try:  # an account with this email but another uid (created by hand): reuse it
            existing = auth.get_user_by_email(email)
            auth.delete_user(existing.uid)
        except auth.UserNotFoundError:
            pass
        auth.create_user(uid=UID, email=email, password=password, display_name=CUSTOMER["name"], email_verified=True)
    auth.set_custom_user_claims(UID, {"customer_id": CUSTOMER["customer_id"]})

    ENV_LOCAL.write_text(
        "# Seeded customer login, written by `make seed`. Gitignored: never commit.\n"
        f"ESCUDO_CUSTOMER_EMAIL={email}\n"
        f"ESCUDO_CUSTOMER_PASSWORD={password}\n"
        "#\n"
        "# Copy into android/local.properties:\n"
        f"#   escudo.apiUrl={api_url}\n"
        f"#   escudo.customerEmail={email}\n"
        f"#   escudo.customerPassword={password}\n"
    )
    return email, password
