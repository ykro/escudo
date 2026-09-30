"""Generate the three rehearsal photos of a minor crash with IMAGE_MODEL (Vertex AI).

    cd backend && uv run python scripts/generate_sample_photos.py

Writes samples/photos/crash_{1,2,3}.jpg and samples/photos/prompts.json
(model, prompt and seed next to each asset, as the data rules require).
"""

from __future__ import annotations

import io
import json
import os
import time
from pathlib import Path

from google import genai
from google.genai import types
from PIL import Image

OUT = Path(__file__).resolve().parents[1] / "samples" / "photos"
SCENE = (
    "Photorealistic smartphone photo taken by the driver right after a minor rear-end collision in "
    "Guatemala City, daytime, overcast. The car is a silver 2022 Toyota Corolla sedan with a Guatemalan "
    "licence plate 'P-512KLM'. No people, no readable brand logos, no text overlays."
)
PROMPTS = {
    "crash_1": SCENE + " Wide shot of the rear of the car: the rear bumper is dented and scraped on the left side, parked at the curb on a street in zona 10.",
    "crash_2": SCENE + " Close-up of the rear left corner: the tail light lens is cracked, the bumper paint is scraped showing black plastic, small fragments of red plastic on the asphalt.",
    "crash_3": SCENE + " Three-quarter view from the left rear: the trunk lid is intact, the bumper is pushed in a few centimetres, the car otherwise looks drivable.",
}
SEED = 2027


def generate(client: genai.Client, model: str, prompt: str) -> types.GenerateContentResponse:
    for attempt in range(6):  # image models are often rate limited (429): back off and retry
        try:
            return client.models.generate_content(
                model=model,
                contents=prompt,
                config=types.GenerateContentConfig(response_modalities=["IMAGE"], seed=SEED),
            )
        except genai.errors.ClientError as exc:
            if exc.code != 429 or attempt == 5:
                raise
            time.sleep(10 * 2**attempt)
    raise RuntimeError("unreachable")


def main() -> None:
    model = os.environ["IMAGE_MODEL"]
    client = genai.Client(
        vertexai=True,
        project=os.environ.get("GOOGLE_CLOUD_PROJECT", "ai-experiments-487722"),
        location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"),
    )
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for name, prompt in PROMPTS.items():
        response = generate(client, model, prompt)
        image_bytes = next(
            p.inline_data.data for p in response.candidates[0].content.parts if p.inline_data and p.inline_data.data
        )
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        image.thumbnail((1280, 1280))
        path = OUT / f"{name}.jpg"
        image.save(path, "JPEG", quality=85)
        manifest.append({"file": path.name, "origin": {"type": "synthetic", "model": model, "prompt": prompt, "seed": SEED}})
        print(f"wrote {path} ({path.stat().st_size // 1024} KB)")
    (OUT / "prompts.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
