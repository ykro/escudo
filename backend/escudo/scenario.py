"""Scripted scenario: "tuve un choque menor, aquí están las fotos, ayúdame con todo".

Drives the whole flow through Escudo's MCP tools exactly as an agent would,
then prints a timing table. Acceptance: < 3 minutes, exactly 3 approvals.

    uv run python -m escudo.scenario --base-url http://127.0.0.1:8080            # waits for you to approve on the phone
    uv run python -m escudo.scenario --base-url URL --test-mode --auto-approve    # rehearsal: approves via the human endpoint

Each `--auto-approve` run gets its own isolated customer (POST /admin/runs: policy, login, run id),
so runs never collide with each other or with the stage phone; `--shared` uses the seeded customer.
`--auto-approve` plays the customer's phone: it registers a fresh software test device
(escudo/test_device.py) for the run and signs each decision with it,
exactly like the app (escudo-approval:v1). Refused unless `--test-mode` is set: in a
real run only the customer approves, on her phone.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import os
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from .test_device import TestDevice

PHOTOS_DIR = Path(__file__).resolve().parents[1] / "samples" / "photos"
BUDGET_SECONDS = 180
ASSESS_TARGET_SECONDS = 15
EXPECTED_APPROVALS = 3
LOCATION = {"lat": 14.5995, "lng": -90.5102, "label": "Av. La Reforma y 12 calle, zona 10"}
DESCRIPTION = "Tuve un choque menor: me pegaron atrás en un semáforo. Nadie salió herido."


@dataclass
class Report:
    steps: list[tuple[str, float, str]] = field(default_factory=list)
    approvals: list[str] = field(default_factory=list)
    assessment_seconds: float | None = None
    started: float = field(default_factory=time.perf_counter)

    @property
    def total(self) -> float:
        return time.perf_counter() - self.started

    def print(self, out=sys.stdout) -> None:  # noqa: ANN001
        print("\n  paso                          seg  resultado", file=out)
        print("  " + "-" * 76, file=out)
        for name, seconds, note in self.steps:
            print(f"  {name:<28}{seconds:>6.1f}  {note[:60]}", file=out)
        print("  " + "-" * 76, file=out)
        if self.assessment_seconds is not None:
            mark = "OK" if self.assessment_seconds < ASSESS_TARGET_SECONDS else "LENTO"
            print(f"  evaluación de fotos {self.assessment_seconds:.1f} s (meta < {ASSESS_TARGET_SECONDS} s) · {mark}", file=out)
        verdict = "OK" if self.total < BUDGET_SECONDS and len(self.approvals) == EXPECTED_APPROVALS else "FALLA"
        print(f"  total {self.total:.1f} s (límite {BUDGET_SECONDS} s) · aprobaciones: {len(self.approvals)} (esperadas {EXPECTED_APPROVALS}) · {verdict}\n", file=out)


Approver = Callable[[str, str], Awaitable[None]]  # (approval_id, args_hash)


def load_sample_photos() -> list[str]:
    files = sorted(PHOTOS_DIR.glob("crash_*.jpg"))
    if not files:
        raise SystemExit(f"No sample photos in {PHOTOS_DIR}; run scripts/generate_sample_photos.py")
    return [base64.b64encode(f.read_bytes()).decode() for f in files]


def payload(result: Any) -> dict[str, Any]:
    if result.is_error:
        text = " ".join(getattr(c, "text", "") for c in result.content)
        raise RuntimeError(text)
    if result.structured_content:
        return result.structured_content.get("result", result.structured_content)
    return json.loads(result.content[0].text)


async def run_scenario(mcp_client: Client, approve: Approver, photos: list[str], out=sys.stdout) -> Report:  # noqa: ANN001
    report = Report()

    async def step(name: str, tool: str, args: dict[str, Any]) -> dict[str, Any]:
        t0 = time.perf_counter()
        data = payload(await mcp_client.call_tool(tool, args))
        note = data.get("status") if data.get("status") == "pending_approval" else _note(tool, data)
        report.steps.append((name, time.perf_counter() - t0, note))
        print(f"  ✓ {name:<26} {time.perf_counter() - t0:5.1f}s  {note[:70]}", file=out, flush=True)
        return data

    async def gated(name: str, tool: str, args: dict[str, Any]) -> dict[str, Any]:
        first = await step(name, tool, args)
        if first.get("status") != "pending_approval":
            return first
        report.approvals.append(first["approval_id"])
        t0 = time.perf_counter()
        print(f"    ⏸  aprobación requerida: {first['summary']}", file=out, flush=True)
        await approve(first["approval_id"], first["args_hash"])
        report.steps.append(("  (aprobación humana)", time.perf_counter() - t0, first["approval_id"]))
        return await step(name + " (aprobado)", tool, {**args, "approval_id": first["approval_id"]})

    await step("get_policy", "get_policy", {})
    await step("check_coverage", "check_coverage", {"incident_type": "collision"})
    claim = await step("open_claim", "open_claim", {"description": DESCRIPTION, "location": LOCATION})
    claim_id = claim["claim_id"]
    assessed = await step("attach_claim_photos", "attach_claim_photos", {"claim_id": claim_id, "photos": photos})
    report.assessment_seconds = assessed.get("assessment_seconds")
    await step("request_tow (A2A)", "request_tow", {"claim_id": claim_id})
    options = (await step("list_repair_options (A2A)", "list_repair_options", {"claim_id": claim_id}))["options"]
    # What a sensible agent would pick: best rated shop, earliest slot.
    shop = max(options, key=lambda o: (o["rating"], -o["quote_usd"]))
    await step("request_rental_car", "request_rental_car", {"claim_id": claim_id})
    await gated("select_repair_shop", "select_repair_shop", {"claim_id": claim_id, "shop_id": shop["shop_id"], "slot": shop["slots"][0]})
    await gated("accept_deductible", "accept_deductible", {"claim_id": claim_id})
    await gated("sign_claim", "sign_claim", {"claim_id": claim_id})
    final = await step("get_claim_status", "get_claim_status", {"claim_id": claim_id})
    if final["status"] != "signed":
        raise RuntimeError(f"claim ended in status {final['status']}")
    return report


def _note(tool: str, data: dict[str, Any]) -> str:
    match tool:
        case "get_policy":
            return f"{data['policy_id']}, deducible USD {data['deductible_usd']:.0f}"
        case "check_coverage":
            return data["notes"]
        case "open_claim":
            return data["claim_id"]
        case "attach_claim_photos":
            return (f"{data.get('photos_assessed', '?')} fotos en {data.get('assessment_seconds', '?')} s · "
                    f"{data['severity']}, USD {data['cost_range_usd'][0]:.0f}-{data['cost_range_usd'][1]:.0f}")
        case "request_tow":
            return f"{data['provider']} en {data['eta_minutes']} min"
        case "list_repair_options":
            return ", ".join(f"{o['name']} USD {o['quote_usd']:.0f}" for o in data["options"])
        case "request_rental_car":
            return f"{data['days']} días, {'cubierto' if data['covered'] else 'con costo'}"
        case "select_repair_shop":
            return f"{data['shop_name']} {data['slot']}"
        case "accept_deductible":
            return f"USD {data['amount_usd']:.0f}"
        case "sign_claim" | "get_claim_status":
            return f"{data['claim_id']} {data['status']}"
    return "ok"


def human_endpoint_approver(http: httpx2.AsyncClient, device: TestDevice, delay: float = 0.0) -> Approver:
    """Test mode only: plays the customer's phone — signs with the test device and calls the human
    endpoint (after `delay` s of "reaching for the phone")."""

    async def approve(approval_id: str, args_hash: str) -> None:
        await asyncio.sleep(delay)
        r = await http.post(f"/approvals/{approval_id}/approve", json=device.decision(approval_id, args_hash),
                            headers={"X-Escudo-Surface": "android"})
        r.raise_for_status()

    return approve


def wait_for_phone_approver(http: httpx2.AsyncClient, timeout: float = 120) -> Approver:
    """Real mode: the customer approves on the phone; we poll until the approval leaves 'pending'."""

    async def approve(approval_id: str, args_hash: str) -> None:
        deadline = time.monotonic() + timeout
        print("    📱 apruébalo en el teléfono…", flush=True)
        while time.monotonic() < deadline:
            r = await http.get("/approvals")
            r.raise_for_status()
            if approval_id not in {a["approval_id"] for a in r.json()}:
                return
            await asyncio.sleep(1)
        raise TimeoutError(f"approval {approval_id} was not decided in {timeout:.0f}s")

    return approve


async def firebase_id_token(api_key: str, email: str | None, password: str | None) -> str:
    """Firebase sign-in over REST as the seeded customer (`make seed` writes the login to .env.local).

    Only a token carrying the customer's `customer_id` claim can see her policy and claims."""
    if not (email and password):
        raise SystemExit("Falta el login de la clienta: corre `make seed` (escribe ESCUDO_CUSTOMER_EMAIL/PASSWORD en .env.local)")
    async with httpx2.AsyncClient(timeout=20) as client:
        # Escudo's web key only accepts escudo-web's referrers, so the runner says it comes from there.
        # That check only limits casual reuse of a key every browser receives; it is not a boundary:
        # the server acts as the customer_id of the verified ID token, whoever signed in.
        r = await client.post(
            f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={api_key}",
            json={"email": email, "password": password, "returnSecureToken": True},
            headers={"Referer": f"{WEB_URL}/"},
        )
        r.raise_for_status()
        return r.json()["idToken"]


WEB_URL = os.environ.get("ESCUDO_WEB_URL", "https://escudo-web-611681112050.us-central1.run.app")


@dataclass
class RunSession:
    """Who the runner is for this run: an isolated customer (POST /admin/runs) or the seeded one."""

    token: str | None
    run_id: str | None
    label: str

    def headers(self, actor: str = "external_agent") -> dict[str, str]:
        h = {"X-Escudo-Actor": actor}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        if self.run_id:
            h["X-Escudo-Run-Id"] = self.run_id
        return h


async def prepare_run(base: str, *, isolated: bool, token: str | None = None) -> RunSession:
    """With an admin token + Firebase web key: a brand-new customer for this run, so concurrent runs
    (and the stage phone) never collide. Otherwise the seeded customer (.env.local)."""
    api_key = os.environ.get("FIREBASE_WEB_API_KEY")
    admin = os.environ.get("ADMIN_TOKEN")
    if token:
        return RunSession(token, None, "token dado")
    if isolated and admin and (api_key or _is_local(base)):
        async with httpx2.AsyncClient(base_url=base, timeout=60) as http:
            r = await http.post("/admin/runs", headers={"X-Admin-Token": admin})
            r.raise_for_status()
            run = r.json()
        token = run.get("token") or await firebase_id_token(api_key, run["email"], run["password"])  # token: MOCK_AUTH
        return RunSession(token, run["run_id"], f"cliente aislado {run['customer_id']}")
    if api_key:
        token = await firebase_id_token(api_key, os.environ.get("ESCUDO_CUSTOMER_EMAIL"), os.environ.get("ESCUDO_CUSTOMER_PASSWORD"))
        return RunSession(token, None, "clienta sembrada")
    return RunSession(None, None, "sin sesión (MOCK_AUTH)")


def _is_local(base: str) -> bool:
    return "127.0.0.1" in base or "localhost" in base


async def main() -> int:
    parser = argparse.ArgumentParser(description="Escudo scenario runner (MCP)")
    parser.add_argument("--base-url", default=os.environ.get("ESCUDO_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--token", default=os.environ.get("ESCUDO_ID_TOKEN"), help="Firebase ID token of the customer")
    parser.add_argument("--shared", action="store_true", help="use the seeded customer instead of an isolated run customer")
    parser.add_argument("--test-mode", action="store_true", help="rehearsal run (required for --auto-approve)")
    parser.add_argument("--auto-approve", action="store_true", help="approve through the human endpoint (test mode only)")
    parser.add_argument("--approve-delay", type=float, default=float(os.environ.get("APPROVE_DELAY", "0")),
                        help="seconds the simulated customer takes to approve (rehearsals; keeps the pause on screen)")
    args = parser.parse_args()
    if args.auto_approve and not args.test_mode:
        parser.error("--auto-approve is only allowed with --test-mode: in a real run the customer approves on the phone")

    base = args.base_url.rstrip("/")
    session = await prepare_run(base, isolated=not args.shared and args.auto_approve, token=args.token)
    print(f"\nEscudo · escenario vía MCP contra {base} · {session.label}")
    if session.run_id:
        print(f"Presentador: {WEB_URL}/presenter?run={session.run_id}")
    print()
    headers = session.headers()
    async with httpx2.AsyncClient(base_url=base, headers=headers, timeout=120) as http:
        if args.auto_approve:
            # The runner plays the customer's phone: a fresh software device for this run.
            device = TestDevice(f"scenario-{session.run_id or 'shared'}")
            (await http.post("/devices", json=device.registration())).raise_for_status()
            approver = human_endpoint_approver(http, device, args.approve_delay)
        else:
            approver = wait_for_phone_approver(http)
        mcp_http = httpx2.AsyncClient(headers=headers, timeout=httpx2.Timeout(120, read=180))
        async with Client(streamable_http_client(f"{base}/mcp", http_client=mcp_http)) as mcp_client:
            report = await run_scenario(mcp_client, approver, load_sample_photos())
    report.print()
    ok = report.total < BUDGET_SECONDS and len(report.approvals) == EXPECTED_APPROVALS
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
