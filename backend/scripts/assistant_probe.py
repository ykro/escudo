"""Talk to the Escudo Assistant over /assistant/live without a phone (rehearsal / smoke test).

    uv run python scripts/assistant_probe.py --base-url http://127.0.0.1:8080 "¿Qué cubre mi póliza?"

Sends typed text instead of microphone audio (same socket, `{"type": "text"}`), prints
transcripts, tool calls and approval prompts, and counts the 24 kHz PCM audio received.
Signs in as the seeded customer (ESCUDO_CUSTOMER_EMAIL/PASSWORD + FIREBASE_WEB_API_KEY) when set.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import time

import websockets

from escudo.scenario import firebase_id_token


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("ESCUDO_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--seconds", type=float, default=40)
    parser.add_argument("text", nargs="+")
    args = parser.parse_args()

    headers = {}
    if os.environ.get("FIREBASE_WEB_API_KEY"):
        token = await firebase_id_token(
            os.environ["FIREBASE_WEB_API_KEY"], os.environ.get("ESCUDO_CUSTOMER_EMAIL"), os.environ.get("ESCUDO_CUSTOMER_PASSWORD")
        )
        headers["Authorization"] = f"Bearer {token}"
    url = args.base_url.replace("http", "ws", 1).rstrip("/") + "/assistant/live"
    started = time.perf_counter()
    audio = 0
    async with websockets.connect(url, additional_headers=headers, max_size=None) as ws:
        for text in args.text:
            await ws.send(json.dumps({"type": "text", "text": text}))
            print(f"> {text}")
            deadline = time.perf_counter() + args.seconds
            transcript = ""
            while time.perf_counter() < deadline:
                try:
                    message = await asyncio.wait_for(ws.recv(), timeout=deadline - time.perf_counter())
                except TimeoutError:
                    break
                if isinstance(message, bytes):
                    audio += len(message)
                    continue
                data = json.loads(message)
                t = f"{time.perf_counter() - started:5.1f}s"
                match data["type"]:
                    case "transcript" if data["role"] == "assistant":
                        transcript += data["text"]
                    case "transcript":
                        pass
                    case "turn_complete" if transcript.strip():  # a tool turn completes before the spoken answer
                        print(f"{t} < {transcript.strip()}")
                        break
                    case "turn_complete":
                        pass
                    case _:
                        print(f"{t} {data}")
        await ws.send(json.dumps({"type": "end"}))
    print(f"audio recibido: {audio} bytes ({audio / 2 / 24000:.1f} s a 24 kHz)")


if __name__ == "__main__":
    asyncio.run(main())
