"""Regenerate Escudo's brand images (direction "A · Sereno") with IMAGE_MODEL on Vertex AI.

    make brand                      # images that are missing
    make brand ARGS="--force logo"  # regenerate one (logo | android | presenter)

Each prompt in brand/prompts/<kind>.md has a header (aspect, kind, out: comma-separated paths
relative to escudo/) and a body; brand/prompts/_rules.md and _rules_<kind>.md are appended, exactly
as when the direction was chosen. The images are references (logo raster, screen mockups): the UI
itself is code built from the direction's tokens (web/src/brand/tokens.json,
android/design/a-sereno/tokens.json), and the vector mark is a hand-drawn SVG
(web/src/brand/EscudoMark.tsx, android/design/a-sereno/mark.svg).
"""

from __future__ import annotations

import argparse
import io
import os
import pathlib
import time

from google import genai
from google.genai import types
from PIL import Image

BRAND = pathlib.Path(__file__).resolve().parent
ROOT = BRAND.parent
PROMPTS = BRAND / "prompts"
MAX_EDGE = {"16:9": 1400, "9:16": 1100, "1:1": 640}


def parse(path: pathlib.Path) -> dict:
    _, header, body = path.read_text().split("---", 2)
    meta = {k.strip(): v.strip() for k, v in (line.split(":", 1) for line in header.strip().splitlines())}
    rules = (PROMPTS / "_rules.md").read_text().strip()
    kind_rules = PROMPTS / f"_rules_{meta.get('kind', 'screen')}.md"
    extra = kind_rules.read_text().strip() if kind_rules.exists() else ""
    meta["prompt"] = "\n\n".join(p for p in (body.strip(), extra, rules) if p)
    meta["outs"] = [ROOT / o.strip() for o in meta["out"].split(",")]
    meta["name"] = path.stem
    return meta


def generate(client: genai.Client, model: str, job: dict) -> bytes:
    for attempt in range(7):  # the image model is often rate limited (429): back off and retry
        try:
            response = client.models.generate_content(
                model=model,
                contents=job["prompt"],
                config=types.GenerateContentConfig(
                    response_modalities=["IMAGE"], image_config=types.ImageConfig(aspect_ratio=job["aspect"])
                ),
            )
            return next(p.inline_data.data for p in response.candidates[0].content.parts if p.inline_data)
        except Exception:
            if attempt == 6:
                raise
            time.sleep(min(90, 8 * 2**attempt))
    raise RuntimeError("unreachable")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", nargs="*", default=[], help="regenerate these even if they exist")
    args = parser.parse_args()
    model = os.environ["IMAGE_MODEL"]
    client = genai.Client(vertexai=True, project=os.environ.get("GOOGLE_CLOUD_PROJECT", "ai-experiments-487722"),
                          location=os.environ.get("GOOGLE_CLOUD_LOCATION", "global"))
    for path in sorted(PROMPTS.glob("[!_]*.md")):
        job = parse(path)
        if all(o.exists() for o in job["outs"]) and job["name"] not in args.force:
            print(f"skip {job['name']} (exists)")
            continue
        image = Image.open(io.BytesIO(generate(client, model, job))).convert("RGB")
        image.thumbnail((MAX_EDGE.get(job["aspect"], 1200),) * 2, Image.LANCZOS)
        for out in job["outs"]:
            out.parent.mkdir(parents=True, exist_ok=True)
            image.save(out, "JPEG", quality=80, optimize=True, progressive=True)
            print(f"ok   {out.relative_to(ROOT)} {image.size}")


if __name__ == "__main__":
    main()
