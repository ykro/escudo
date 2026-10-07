# Escudo: commands

> [README](../README.en.md) · [Español](COMANDOS.md)

## Full quickstart

No cloud (everything simulated: `MOCK_AI=1 MOCK_AUTH=1`, in-memory store, deterministic partner agents):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties   # once: make test includes Android (make android-prep writes the full file)
make up-mock          # API at http://127.0.0.1:8080 (ADMIN_TOKEN=local-admin)
make reset            # in another terminal: back to the initial state (same token)
make scenario         # the whole scenario over MCP, the 3 approvals signed by a test device
make chat-scenario    # the same flow over the chat, with the scripted assistant
make test             # backend (pytest), Android (unit) and web (vitest)
```

Against the cloud (needs ADC and `.env`; see [docs/ONBOARDING.md](ONBOARDING.md)):

```bash
cp .env.example .env  # and fill in the secrets
make reset CLOUD=1    # put the deployed service back in its initial state
make scenario CLOUD=1 APPROVE_DELAY=4   # its own customer for this run; prints …/presentador?run=<id>
make chat-scenario CLOUD=1              # the same, typing to the assistant in the chat
```

Every `make scenario` and `make chat-scenario` run creates its own fictional customer
(`POST /admin/runs`: policy, Firebase login and `run_id`), so concurrent runs never collide with each
other or with the stage customer, who is the seeded one. `make reset` deletes those customers.

On talk day no command is run: you open the presenter and the emulator (see
[DEMO.en.md](DEMO.en.md)). Beforehand, the developer gets the emulator ready once:

```bash
make android-prep     # boots the emulator and gets it ready: PIN, live app, permissions, photos, fingerprint, AppFunctions agent and token
# … then closes it normally: its quick-boot snapshot keeps all of it
```

Fallbacks without commands: `/presentador?demo=1` replays a real recorded run, labelled
**REPETICIÓN GRABADA** (recorded replay), and the video [`docs/escudo-chat-run.mp4`](escudo-chat-run.mp4).

## `make` commands

From the repo root (`make help` lists them all):

| Command | What it does |
| --- | --- |
| `make up` | Local API on `:8080` against real Firestore, Storage and Gemini (ADC, `.env`) |
| `make up-mock` | Local API without cloud (`MOCK_AI=1 MOCK_AUTH=1`, `ADMIN_TOKEN=local-admin`, the same one `make reset` uses) |
| `make web` | Local portal and `/presentador` on `:3217` (`npm run dev`, config in `web/.env.local`) |
| `node web/e2e/responsive.mjs` | Screenshots of every page (presenter, sign-in, policy, claim, repair shops) on 375 and 414 px phones, a 768 tablet, a 1280 laptop and a 1920 projector, in `web/e2e/screenshots/responsive/`; fails if any page scrolls sideways. `ESCUDO_WEB_URL=http://localhost:3217` runs it against `make web` |
| `make seed` | Writes policy, shops and tow providers to Firestore and creates the customer's Firebase Auth login (credentials in `.env.local`) |
| `make reset` | `POST /admin/reset` on `ESCUDO_BASE_URL` (< 10 s). `CLOUD=1` targets the deployed service and reads the token from Secret Manager (`escudo-admin-token`). On stage, **Empezar de nuevo** (start over) in the app does the same |
| `make test` | Backend `pytest` with `MOCK_AI=1 MOCK_AUTH=1`, Android unit tests and web `vitest` |
| `make scenario` | Full scenario over MCP in test mode, with its own customer (`POST /admin/runs`) and a test device signing the 3 approvals; `CLOUD=1`, `APPROVE_DELAY=4`. Prints `…/presentador?run=<id>` |
| `make chat-scenario` | Typed-chat scenario: own customer, claim and 3 photos over REST (as the app does), then the chat; 3 signed approvals; < 3 min. Against `make up-mock` the scripted assistant answers; `CLOUD=1`, Gemini |
| `make scenario-live` | The MCP scenario with the seeded customer (`--shared`); waits for her to approve each step in the app on the emulator |
| `make infra` | Creates or verifies everything in GCP from an empty project: `infra/01..07` (APIs, `escudo` database, bucket, Artifact Registry, Firebase Auth via API, service accounts, Secret Manager, Escudo's own Firebase web key) |
| `make deploy` | `deploy-api` + `deploy-web`; both scale to zero (`min-instances=0`) with startup CPU boost |
| `make deploy-api` | `infra/10-deploy-api.sh`: builds and deploys `escudo-api` |
| `make deploy-web` | `infra/11-deploy-web.sh`: builds and deploys `escudo-web` |
| `make photos` | Regenerates the rehearsal crash photos with `IMAGE_MODEL` |
| `make brand` | Regenerates the "A · Sereno" logo and mockups with `IMAGE_MODEL` from `brand/prompts/` (`ARGS="--force logo"`) |
| `make android-build` | `./gradlew assembleDebug` (`live` and `fake` flavors) |
| `make android-test` | Android unit tests (both flavors) |
| `make android-install` | Installs and opens the app (`FLAVOR=live` by default, `FLAVOR=fake` for no network) |
| `make android-emulator` | Starts (or reuses) the `Pixel_9_API_36_1` AVD (Android 16 QPR2, needed for `cmd app_function`) and prints its serial |
| `make android-prep` | Boots the emulator and gets it ready with no manual steps (idempotent): PIN 1234 and screen unlocked, `android/local.properties` from `.env.local`, `google-services.json`, `live` app installed, notification and gallery permissions, the three photos as `escudo_crash_1..3.jpg`, one enrolled fingerprint (driving Settings' enrollment screen), Google's AppFunction Testing Agent running with its system permission, and the app opened so it registers its token. Then close the emulator normally so its snapshot keeps it |
| `make android-serial` | adb serial of that emulator; every Android target detects it on its own |

`android/Makefile` adds `make -C android chat-scenario` (chat scenario on the emulator, with a virtual
fingerprint, timed),
`google-services` (downloads `google-services.json`) and `appfunctions` (lists the AppFunctions on the device).
