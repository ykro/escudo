"""make preflight: the parsers over adb output and the Firestore checks, offline."""

from __future__ import annotations

import pytest

from escudo import preflight
from escudo.repository import MemoryDocumentStore, Repository
from escudo.repository.repository import APPROVALS, CLAIMS, DEVICES
from escudo.seed import write_seed

pytestmark = pytest.mark.anyio

DUMPSYS_PACKAGE = """
    requested permissions:
      android.permission.POST_NOTIFICATIONS
      android.permission.READ_MEDIA_IMAGES
    runtime permissions:
        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SENSITIVE_WHEN_GRANTED]
        android.permission.READ_MEDIA_IMAGES: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED]
"""

DUMPSYS_FINGERPRINT = """Dumping for sensorId: 0, provider: FingerprintProvider
Fps state: 0
{"service":"FingerprintProvider\\/default","prints":[{"id":0,"count":1,"accept":3,"reject":0}]}
HAL deaths since last reboot: 0
"""


def test_granted_permissions_only_counts_granted_true():
    assert preflight.granted_permissions(DUMPSYS_PACKAGE) == {"android.permission.POST_NOTIFICATIONS"}


def test_enrolled_fingerprints_reads_the_json_line():
    assert preflight.enrolled_fingerprints(DUMPSYS_FINGERPRINT) == 1
    assert preflight.enrolled_fingerprints(DUMPSYS_FINGERPRINT.replace('"count":1', '"count":0')) == 0
    assert preflight.enrolled_fingerprints("no fingerprint HAL") == 0


def test_device_id_from_shared_prefs():
    xml = "<map>\n    <string name=\"device_id\">79a58b04-8d83-4f0f-a010-c57ae3753b68</string>\n</map>"
    assert preflight.device_id_from_prefs(xml) == "79a58b04-8d83-4f0f-a010-c57ae3753b68"
    assert preflight.device_id_from_prefs("run-as: couldn't stat /data/user/0/app.escudo.android") is None


def test_gallery_names_and_lock_state():
    rows = "Row: 0 _display_name=escudo_crash_1.jpg\nRow: 1 _display_name=escudo_crash_2.jpg\n"
    assert preflight.gallery_names(rows) == {"escudo_crash_1.jpg", "escudo_crash_2.jpg"}
    assert preflight.device_locked("User \"Owner\" (id=0): trustState=UNTRUSTED, deviceLocked=1, x") is True
    assert preflight.device_locked("deviceLocked=0") is False
    assert preflight.device_locked("") is True  # unknown counts as locked


def test_device_problem():
    good = {"customer_id": "cus_demo", "public_key": "k", "fcm_token": "t", "disabled": False}
    assert preflight.device_problem(good, "cus_demo") is None
    assert preflight.device_problem(None, "cus_demo") == "no está registrado en Firestore"
    assert "desactivado" in preflight.device_problem({**good, "disabled": True}, "cus_demo")
    assert "llave" in preflight.device_problem({**good, "public_key": ""}, "cus_demo")
    assert "FCM" in preflight.device_problem({**good, "fcm_token": None}, "cus_demo")
    assert "cus_other" in preflight.device_problem({**good, "customer_id": "cus_other"}, "cus_demo")


async def test_seed_check_passes_after_seed_and_fails_on_leftover_claims():
    repo = Repository(MemoryDocumentStore())
    r = preflight.Report()
    await preflight.check_seed(r, repo)
    assert r.failures == 3  # nothing seeded: policy, shops and tow providers fail; "no claims" passes

    await write_seed(repo)
    r = preflight.Report()
    await preflight.check_seed(r, repo)
    assert r.failures == 0

    await repo.store.set(CLAIMS, "CLM-X", {"claim_id": "CLM-X", "customer_id": "cus_demo"})
    await repo.store.set(APPROVALS, "apr_x", {"approval_id": "apr_x", "customer_id": "cus_demo"})
    r = preflight.Report()
    await preflight.check_seed(r, repo)
    assert r.failures == 1


async def test_registered_device_check():
    repo = Repository(MemoryDocumentStore())
    r = preflight.Report()
    await preflight.check_registered_device(r, repo, None)
    await preflight.check_registered_device(r, repo, "dev-1")
    assert r.failures == 2
    await repo.store.set(DEVICES, "dev-1", {"device_id": "dev-1", "customer_id": "cus_demo", "public_key": "k",
                                            "fcm_token": "t", "disabled": False})
    r = preflight.Report()
    await preflight.check_registered_device(r, repo, "dev-1")
    assert r.failures == 0
