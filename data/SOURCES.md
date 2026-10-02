# Sources

Every data file and generated asset in this repo, with its origin. Nothing comes from the web;
nothing is real customer data. Origins follow the shared rule: `synthetic` (model or tool + prompt +
seed), `human` (written by hand for the demo).

| Asset | Path | Origin | How to regenerate |
| --- | --- | --- | --- |
| Customer, policy, repair shops, tow providers | `backend/escudo/seed.py` → Firestore `escudo` (`customers`, `policies`, `repair_shops`, `tow_providers`) | `human` — fictional values written for the demo; every record carries `origin: {type: synthetic, generator: backend/escudo/seed.py, seed: 2027}` | `make seed` / `make reset` (slots are recomputed from today) |
| Run customers (ephemeral) | `POST /admin/runs` → Firestore `customers`/`policies`/`runs` + Firebase Auth user `run_<id>` (custom claim `customer_id`) | `synthetic` — `backend/escudo/seed.py:create_run`, same persona and coverage as the seed, random password; one per `make scenario` / `make chat-scenario` run | deleted by `make reset` |
| Seeded customer login | Firebase Auth user `cus_demo_login` (custom claim `customer_id: cus_demo`) | `synthetic` — `backend/escudo/customer_login.py`, random password | `make seed` (password kept in the gitignored `.env.local`) |
| Sample crash photos (3) | `backend/samples/photos/crash_{1,2,3}.jpg` | `synthetic` — `IMAGE_MODEL` (`gemini-3.1-flash-lite-image`, Vertex AI), seed 2027; each prompt stored next to the file in `backend/samples/photos/prompts.json` | `make photos` (or `scripts/generate_sample_photos.py --only crash_1`) |
| Logo (raster) | `web/public/brand/escudo-logo.jpg`, `android/design/a-sereno/logo.jpg` | `synthetic` — `IMAGE_MODEL`, prompt `brand/prompts/logo.md` + `_rules*.md` | `make brand ARGS="--force logo"` |
| Android screen mockup | `android/design/a-sereno/android.jpg` | `synthetic` — `IMAGE_MODEL`, prompt `brand/prompts/android.md` | `make brand ARGS="--force android"` |
| Presenter mockup | `brand/mockups/presenter.jpg` | `synthetic` — `IMAGE_MODEL`, prompt `brand/prompts/presenter.md` | `make brand ARGS="--force presenter"` |
| Vector mark | `web/src/brand/EscudoMark.tsx`, `web/src/app/icon.svg`, `android/design/a-sereno/mark.svg` | `human` — hand-drawn SVG redraw of the raster logo | – |
| Design tokens | `web/src/brand/tokens.json`, `android/design/a-sereno/tokens.json` | `human` — direction "A · Sereno" (colors, contrast pairs, type, spacing, motion) | – |
| Font | Onest (web: `web/src/app/fonts/Onest-Variable.woff2` via `next/font/local`, license `web/src/app/fonts/Onest-OFL.txt`; Android bundles it, license `android/design/a-sereno/Onest-OFL.txt`) | third party, SIL Open Font License 1.1 | – |
| Presenter demo replay | `web/src/lib/fixtures/scenario-events.json` | `synthetic` — the 37 events of one real `make chat-scenario CLOUD=1` run (`run-6958m5`, 2026-10-01, fictional customer), idle gaps capped at 6 s; shown as REPETICIÓN GRABADA | re-record from Firestore `events` after a run |
| Screenshots | `docs/screenshots/*.png`, `docs/demo/*.png`, `android/docs/` | captured from the running apps against the cloud (Playwright for the presenter, `adb exec-out screencap` / `adb emu screenrecord screenshot` for the emulator, fictional customer) | `node web/e2e/live-run.mjs` |
| Run video | `docs/escudo-chat-run.mp4` (83 s, 1280×720) | captured with Playwright from `/presenter?demo=1` (the recorded run `run-6958m5`, labelled REPETICIÓN GRABADA), H.264 with ffmpeg | record `/presenter?demo=1` after re-recording the fixture |
