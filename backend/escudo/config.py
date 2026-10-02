"""Runtime settings, read once from the environment.

Model names are never hard-coded: they come from env vars documented in
`.env.example`, so swapping a model is a config change, not a code change.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _flag(name: str, default: bool = False) -> bool:
    return os.environ.get(name, "1" if default else "0").strip().lower() in {"1", "true", "yes", "on"}


def _find_contracts_dir() -> Path:
    """contracts/ lives at the repo root; in the container it is copied next to the package."""
    if env := os.environ.get("ESCUDO_CONTRACTS_DIR"):
        return Path(env)
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "contracts"
        if (candidate / "events.schema.json").exists():
            return candidate
    raise RuntimeError("contracts/events.schema.json not found; set ESCUDO_CONTRACTS_DIR")


@dataclass(frozen=True)
class Settings:
    project_id: str = "ai-experiments-487722"
    location: str = "global"
    gemini_model: str = ""
    firestore_database: str = "escudo"
    claims_bucket: str = "ai-experiments-487722-escudo-claims"
    mock_ai: bool = False
    mock_auth: bool = False
    store: str = "firestore"  # "firestore" | "memory"
    admin_token: str = ""
    public_base_url: str = "http://127.0.0.1:8080"
    a2a_peers: tuple[str, ...] = ()
    demo_customer_id: str = "cus_demo"
    a2a_shared_secret: str = ""  # optional: partners require it in X-Escudo-A2A-Key
    port: int = 8080
    contracts_dir: Path = field(default_factory=_find_contracts_dir)

    @classmethod
    def from_env(cls) -> "Settings":
        mock_ai = _flag("MOCK_AI")
        public = os.environ.get("PUBLIC_BASE_URL", "http://127.0.0.1:8080").rstrip("/")
        peers_raw = os.environ.get("A2A_PEERS", f"{public}/a2a/tow,{public}/a2a/repair")
        return cls(
            project_id=os.environ.get("GOOGLE_CLOUD_PROJECT", cls.project_id),
            location=os.environ.get("GOOGLE_CLOUD_LOCATION", cls.location),
            gemini_model=os.environ.get("GEMINI_MODEL", ""),
            firestore_database=os.environ.get("FIRESTORE_DATABASE", cls.firestore_database),
            claims_bucket=os.environ.get("CLAIMS_BUCKET", cls.claims_bucket),
            mock_ai=mock_ai,
            mock_auth=_flag("MOCK_AUTH"),
            store=os.environ.get("STORE", "memory" if mock_ai else "firestore"),
            admin_token=os.environ.get("ADMIN_TOKEN", ""),
            public_base_url=public,
            a2a_peers=tuple(p.strip().rstrip("/") for p in peers_raw.split(",") if p.strip()),
            demo_customer_id=os.environ.get("DEMO_CUSTOMER_ID", cls.demo_customer_id),
            a2a_shared_secret=os.environ.get("A2A_SHARED_SECRET", ""),
            port=int(os.environ.get("PORT", "8080")),
        )

    def require_models(self) -> None:
        if not self.mock_ai and not self.gemini_model:
            raise RuntimeError("GEMINI_MODEL is not set (or run with MOCK_AI=1)")
