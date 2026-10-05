"""`make preflight`: everything the talk needs, checked in one pass. Exit 1 if anything fails.

    uv run python -m escudo.preflight          # from backend/ (the Makefile passes ../.env)

Three groups, each line says what to run when it fails:
  - Cloud: escudo-api and escudo-web with min-instances=1 and answering fast (warm).
  - Firestore: the seed is in place (policy, 3 shops with slots, 2 tow providers) and the stage
    customer has no claims or approvals left over (state right after `make reset CLOUD=1`).
  - Emulator Pixel_9_API_36_1 (the stage device): booted and unlocked, live app installed,
    notification and gallery permissions, an enrolled fingerprint, the three crash photos in the
    gallery and this install's device registered in Firestore (FCM token + approval key).

The parsers below are pure functions over adb/gcloud output, tested in tests/test_preflight.py.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

from .repository.repository import APPROVALS, CLAIMS, DEVICES, POLICIES, SHOPS, TOW_PROVIDERS
from .seed import CUSTOMER, POLICY, SHOPS_SEED, TOW_SEED

PROJECT = "ai-experiments-487722"
REGION = "us-central1"
API_URL = os.environ.get("ESCUDO_API_URL", "https://escudo-api-611681112050.us-central1.run.app")
WEB_URL = os.environ.get("ESCUDO_WEB_URL", "https://escudo-web-611681112050.us-central1.run.app")
SERVICES = ("escudo-api", "escudo-web")
WARM_BUDGET_S = 2.0  # a warm Cloud Run instance answers /health well under this; a cold start takes longer
AVD = os.environ.get("ANDROID_AVD", "Pixel_9_API_36_1")
PACKAGE = "app.escudo.android"
PERMISSIONS = ("android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES")
PHOTOS = ("escudo_crash_1.jpg", "escudo_crash_2.jpg", "escudo_crash_3.jpg")
REPO_ROOT = Path(__file__).resolve().parents[2]
PREP = "make android-prep"


class Report:
    def __init__(self) -> None:
        self.failures = 0

    def ok(self, text: str) -> None:
        print(f"  [ok]    {text}", flush=True)

    def fail(self, text: str, fix: str) -> None:
        self.failures += 1
        print(f"  [FALLA] {text}\n          → {fix}", flush=True)

    def check(self, passed: bool, text: str, fix: str) -> bool:
        (self.ok(text) if passed else self.fail(text, fix))
        return passed


# --- parsers (pure) ---------------------------------------------------------------------------

def granted_permissions(dumpsys_package: str) -> set[str]:
    """Runtime permissions marked granted=true in `dumpsys package <pkg>`."""
    return set(re.findall(r"(android\.permission\.[A-Z_]+): granted=true", dumpsys_package))


def enrolled_fingerprints(dumpsys_fingerprint: str) -> int:
    """Enrolled prints in `dumpsys fingerprint` (its JSON line: "prints":[{"id":0,"count":N,...}])."""
    for line in dumpsys_fingerprint.splitlines():
        line = line.strip()
        if line.startswith("{") and '"prints"' in line:
            try:
                return sum(int(p.get("count", 0)) for p in json.loads(line).get("prints", []))
            except (ValueError, TypeError, AttributeError):
                return 0
    return 0


def device_id_from_prefs(shared_prefs_xml: str) -> str | None:
    """The app's per-install id (AppContainer.deviceId) from shared_prefs/escudo.xml."""
    m = re.search(r'<string name="device_id">([^<]+)</string>', shared_prefs_xml)
    return m.group(1).strip() if m else None


def gallery_names(content_query: str) -> set[str]:
    """`_display_name`s from `content query --uri content://media/external/images/media`."""
    return set(re.findall(r"_display_name=([^,\n]+)", content_query))


def device_locked(dumpsys_trust: str) -> bool:
    m = re.search(r"deviceLocked=([01])", dumpsys_trust)
    return m is None or m.group(1) == "1"


def device_problem(device: dict[str, Any] | None, customer_id: str) -> str | None:
    """Why this registered device could not receive the approval push or sign; None if it can."""
    if device is None:
        return "no está registrado en Firestore"
    if device.get("customer_id") != customer_id:
        return f"está registrado a {device.get('customer_id')}, no a {customer_id}"
    if device.get("disabled"):
        return "está desactivado"
    if not device.get("public_key"):
        return "no tiene llave de aprobación"
    if not device.get("fcm_token"):
        return "no tiene token de FCM"
    return None


# --- cloud ------------------------------------------------------------------------------------

def check_cloud(r: Report) -> None:
    print("Nube (Cloud Run)", flush=True)
    for svc in SERVICES:
        out = subprocess.run(
            ["gcloud", "run", "services", "describe", svc, f"--region={REGION}", f"--project={PROJECT}",
             "--format=value(spec.template.metadata.annotations['autoscaling.knative.dev/minScale'])"],
            capture_output=True, text=True)
        scale = out.stdout.strip()
        r.check(out.returncode == 0 and scale == "1", f"{svc}: min-instances={scale or '0'}", "make stage-on")
    for url in (f"{API_URL}/health", f"{API_URL}/a2a/escudo/.well-known/agent-card.json", f"{WEB_URL}/presenter"):
        started = time.perf_counter()
        try:
            code = httpx.get(url, timeout=30).status_code
        except httpx.HTTPError as exc:
            r.fail(f"{url}: {type(exc).__name__}", "make stage-on && make warmup")
            continue
        secs = time.perf_counter() - started
        r.check(code == 200 and secs < WARM_BUDGET_S, f"{url}: HTTP {code} en {secs:.2f} s",
                "make warmup (si sigue lento: make stage-on)")


# --- Firestore --------------------------------------------------------------------------------

async def check_seed(r: Report, repo) -> None:  # noqa: ANN001
    print("Firestore (estado inicial)", flush=True)
    customer_id = CUSTOMER["customer_id"]
    policy = await repo.store.get(POLICIES, POLICY["policy_id"])
    r.check(bool(policy) and policy.get("status") == "active" and policy.get("customer_id") == customer_id,
            f"póliza {POLICY['policy_id']} activa de {customer_id}", "make seed")
    shops = await repo.store.where(SHOPS)
    with_slots = [s for s in shops if s.get("slots")]
    r.check(len(with_slots) == len(SHOPS_SEED), f"{len(with_slots)} de {len(SHOPS_SEED)} talleres con horarios",
            "make reset CLOUD=1")
    tows = await repo.store.where(TOW_PROVIDERS)
    r.check(len(tows) == len(TOW_SEED), f"{len(tows)} de {len(TOW_SEED)} grúas", "make reset CLOUD=1")
    claims = await repo.store.where(CLAIMS, customer_id=customer_id)
    approvals = await repo.store.where(APPROVALS, customer_id=customer_id)
    r.check(not claims and not approvals,
            f"{customer_id}: {len(claims)} reclamos y {len(approvals)} aprobaciones (deben ser 0)", "make reset CLOUD=1")


async def check_registered_device(r: Report, repo, device_id: str | None) -> None:  # noqa: ANN001
    print("Registro del dispositivo", flush=True)
    if not device_id:
        r.fail("la app no tiene device_id (no se ha abierto con sesión)", PREP)
        return
    problem = device_problem(await repo.store.get(DEVICES, device_id), CUSTOMER["customer_id"])
    r.check(problem is None, f"dispositivo {device_id[:8]}… " + (problem or "registrado (token FCM y llave de aprobación)"),
            PREP + " (abre la app con sesión y registra su token)")


# --- emulator ---------------------------------------------------------------------------------

def _adb_bin() -> str:
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    return os.environ.get("ADB") or shutil.which("adb") or str(sdk / "platform-tools/adb")


def emulator_serial() -> str | None:
    out = subprocess.run([str(REPO_ROOT / "android/scripts/emulator_serial.sh"), AVD], capture_output=True, text=True,
                         env={**os.environ, "ADB": _adb_bin()})
    return out.stdout.strip() or None


def adb(serial: str, *args: str) -> str:
    out = subprocess.run([_adb_bin(), "-s", serial, *args], capture_output=True, text=True, timeout=60)
    return out.stdout


def check_emulator(r: Report) -> str | None:
    """Returns the app's device_id (for the Firestore check), or None."""
    print(f"Emulador {AVD}", flush=True)
    serial = emulator_serial()
    if not r.check(serial is not None, f"emulador {AVD} corriendo" + (f" ({serial})" if serial else ""),
                   "make android-emulator && " + PREP):
        return None
    r.check(adb(serial, "shell", "getprop", "sys.boot_completed").strip() == "1", "arranque completo", "espera y repite")
    r.check(not device_locked(adb(serial, "shell", "dumpsys", "trust")), "desbloqueado", PREP)
    package = adb(serial, "shell", "dumpsys", "package", PACKAGE)
    if not r.check("versionName=" in package, f"app {PACKAGE} instalada", PREP):
        return None
    granted = granted_permissions(package)
    for perm in PERMISSIONS:
        r.check(perm in granted, f"permiso {perm.rsplit('.', 1)[1]}", PREP)
    prints = enrolled_fingerprints(adb(serial, "shell", "dumpsys", "fingerprint"))
    r.check(prints > 0, f"{prints} huella(s) registrada(s)", PREP)
    names = gallery_names(adb(serial, "shell", "content", "query", "--uri", "content://media/external/images/media",
                              "--projection", "_display_name"))
    missing = [p for p in PHOTOS if p not in names]
    r.check(not missing, "fotos del choque en la galería" + (f" (faltan {', '.join(missing)})" if missing else ""), PREP)
    return device_id_from_prefs(adb(serial, "shell", "run-as", PACKAGE, "cat", "shared_prefs/escudo.xml"))


# --- main -------------------------------------------------------------------------------------

async def run() -> int:
    from .config import Settings
    from .repository import FirestoreDocumentStore, Repository

    r = Report()
    check_cloud(r)
    device_id = check_emulator(r)  # before the Firestore client exists: adb subprocesses + gRPC threads log fork noise
    settings = Settings.from_env()
    repo = Repository(FirestoreDocumentStore(settings.project_id, settings.firestore_database))
    await check_seed(r, repo)
    await check_registered_device(r, repo, device_id)
    print()
    if r.failures:
        print(f"preflight: {r.failures} problema(s)")
        return 1
    print("preflight OK")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(run()))
