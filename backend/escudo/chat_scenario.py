"""Scripted chat scenario: the customer types to the Escudo Assistant, as on the phone.

    uv run python -m escudo.chat_scenario --base-url https://escudo-api-….run.app --test-mode

Its own isolated customer (POST /admin/runs). The claim and the 3 photos go in over REST (what the
app's "Reportar choque" does), then everything else happens in the chat: the assistant calls the MCP
tools, pauses three times for approval, and the runner — playing the phone with a software test device
— signs each decision. The assistant resumes on its own after every approval. Budget: 3 minutes.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time
from pathlib import Path

import httpx2
import websockets

from .scenario import BUDGET_SECONDS, EXPECTED_APPROVALS, LOCATION, PHOTOS_DIR, WEB_URL, prepare_run
from .test_device import TestDevice

OPENING = ("Tuve un choque menor, me pegaron atrás en un semáforo. Ya abrí el reclamo y subí las fotos en la app. "
           "Ayúdame con todo: la grúa, el taller mejor calificado con su primer horario, el auto de reemplazo, "
           "el deducible y firmar el reclamo.")
NUDGE = "Sí, adelante con todo lo que falta."
MAX_NUDGES = 4


async def main() -> int:
    parser = argparse.ArgumentParser(description="Escudo chat scenario")
    parser.add_argument("--base-url", default=os.environ.get("ESCUDO_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--test-mode", action="store_true", help="required: the runner approves as the customer's phone")
    parser.add_argument("--approve-delay", type=float, default=float(os.environ.get("APPROVE_DELAY", "1")))
    args = parser.parse_args()
    if not args.test_mode:
        parser.error("--test-mode is required: the runner plays the customer's phone")

    base = args.base_url.rstrip("/")
    session = await prepare_run(base, isolated=True)
    started = time.perf_counter()

    def say(text: str) -> None:
        print(f"{time.perf_counter() - started:6.1f}s  {text}", flush=True)

    print(f"\nEscudo · escenario por chat contra {base} · {session.label}")
    if session.run_id:
        print(f"Presentador: {WEB_URL}/presenter?run={session.run_id}")
    print()
    headers = session.headers(actor="customer")
    approvals: list[str] = []
    async with httpx2.AsyncClient(base_url=base, headers={**headers, "X-Escudo-Surface": "android"}, timeout=120) as http:
        device = TestDevice(f"chat-{session.run_id or 'shared'}")
        (await http.post("/devices", json=device.registration())).raise_for_status()
        claim = (await http.post("/claims", json={"description": OPENING[:80], "location": LOCATION})).json()
        photos = [("photos", (p.name, p.read_bytes(), "image/jpeg")) for p in sorted(Path(PHOTOS_DIR).glob("crash_*.jpg"))]
        assessed = (await http.post(f"/claims/{claim['claim_id']}/photos", files=photos)).json()
        say(f"reclamo {claim['claim_id']} abierto y evaluado por la app ({assessed.get('assessment_seconds', '?')} s)")

        ws_url = base.replace("http", "ws", 1) + "/assistant/chat"
        ws_headers = {"Authorization": headers["Authorization"], **({"X-Escudo-Run-Id": session.run_id} if session.run_id else {})}
        async with websockets.connect(ws_url, additional_headers=ws_headers, max_size=None, open_timeout=30) as ws:
            ready = json.loads(await ws.recv())
            assert ready["type"] == "ready", ready
            await ws.send(json.dumps({"type": "text", "text": OPENING}))
            say(f"> {OPENING}")
            nudges, waiting_approval = 0, False
            while time.perf_counter() - started < BUDGET_SECONDS:
                try:
                    frame = json.loads(await asyncio.wait_for(ws.recv(), timeout=BUDGET_SECONDS))
                except TimeoutError:
                    break
                kind = frame["type"]
                if kind == "tool_call":
                    say(f"  · {frame['name']}")
                elif kind == "approval_required":
                    approvals.append(frame["approval_id"])
                    waiting_approval = True
                    say(f"  ⏸ aprobación: {frame['summary']}")
                    await asyncio.sleep(args.approve_delay)
                    r = await http.post(f"/approvals/{frame['approval_id']}/approve",
                                        json=device.decision(frame["approval_id"], frame["args_hash"]))
                    r.raise_for_status()
                    say("  ✓ aprobada con el teléfono (firma del dispositivo)")
                elif kind == "approval_resolved":
                    waiting_approval = False
                elif kind == "text" and frame.get("final"):
                    say(f"< {frame['text']}")
                elif kind == "error":
                    say(f"  ! {frame['message']}")
                elif kind == "turn_complete":
                    status = (await http.get(f"/claims/{claim['claim_id']}")).json()["status"]
                    if status == "signed":
                        break
                    if not waiting_approval:
                        if nudges >= MAX_NUDGES:
                            break
                        nudges += 1
                        await ws.send(json.dumps({"type": "text", "text": NUDGE}))
                        say(f"> {NUDGE}")
            await ws.send(json.dumps({"type": "end"}))
        final = (await http.get(f"/claims/{claim['claim_id']}")).json()["status"]

    total = time.perf_counter() - started
    ok = final == "signed" and len(approvals) == EXPECTED_APPROVALS and total < BUDGET_SECONDS
    print(f"\n  reclamo {claim['claim_id']}: {final} · aprobaciones {len(approvals)} (esperadas {EXPECTED_APPROVALS}) · "
          f"total {total:.1f} s (límite {BUDGET_SECONDS} s) · {'OK' if ok else 'FALLA'}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
