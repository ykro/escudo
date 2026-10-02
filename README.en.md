# Escudo — an agent-ready car insurer

> Demo for the talk **"Built for Agents: Making Your Apps Usable by AI"**.
> [Versión en español](README.md) · [Onboarding guide (Spanish)](docs/ONBOARDING.md) · [Demo script (Spanish)](docs/DEMO.md)

Escudo is a fictional Guatemalan car insurer whose capabilities AI agents can use from three
surfaces: an **Android app with AppFunctions**, a **website with WebMCP** and an **MCP server**. Its
own agent delegates to a tow-truck agent and a repair-shop agent over **A2A**, and the customer writes
to **Asistente Escudo** in a chat. The talk's scenario — *"tuve un choque menor, aquí
están las fotos, ayúdame con todo"* ("I had a minor crash, here are the photos, help me with
everything") — goes from report to signed claim with **exactly three human approvals**, each one a
fingerprint on the phone.

![Presenter view during an approval pause](docs/screenshots/presenter-chat-pause.png)

## What you'll learn

| Idea | Where the code shows it |
| --- | --- |
| **Functions, not screens.** The domain is 11 functions; every surface is a thin adapter over them. | `backend/escudo/capabilities/` (one file per capability, registry in `capabilities/__init__.py`). Adapters: `adapters/rest.py`, `adapters/mcp_server.py`, `adapters/assistant_chat.py`, `agents/escudo_agent.py`. All of them go through one door: `gateway.call_capability`. On Android, screens and AppFunctions share `EscudoRepository`; on the web, screens and WebMCP share `api()` |
| **What an agent may do alone and what needs a person.** One table, enforced by the server, not by the prompt. | `backend/escudo/autonomy.py` (`AUTONOMY` table + `enforce()`), `approvals.py` (single-use approval bound to tool + arguments hash). Approving is **never** a tool: only `POST /approvals/{id}/approve` exists, called by the app after `BiometricPrompt` (`android/.../approvals/ApprovalFlow.kt`) |
| **Where MCP fits and where A2A fits.** Tools over MCP; other companies' work over A2A, discovered by Agent Card. | MCP: `adapters/mcp_server.py` (`/mcp`, streamable HTTP). A2A: `agents/hosting.py` (Agent Card at `/.well-known/agent-card.json`), `agents/peers.py` (discovery from `A2A_PEERS`), `agents/tow_agent.py`, `agents/repair_agent.py` |
| **AppFunctions**: the Android app as a tool for system agents. | `android/app/src/main/kotlin/app/escudo/android/appfunctions/EscudoAppFunctions.kt` (`reportAccident`, `getCoverage`, `getClaimStatus`), agent-facing description in `res/xml/app_metadata.xml`. See [`android/APPFUNCTIONS.md`](android/APPFUNCTIONS.md) |
| **WebMCP**: the website as a tool for the browser's agent. | `web/src/lib/webmcp.ts` (`getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`, all read-only) and `web/src/components/portal/WebMcpTools.tsx` (registration with `document.modelContext.registerTool`) |
| **A chat assistant** with exactly the same powers — and the same pauses — as any MCP client. | `backend/escudo/adapters/assistant_chat.py` (ADK `LlmAgent` on `GEMINI_MODEL` + `McpToolset` looping back to `/mcp` with the customer's own token; transcript in Firestore; after an approval the assistant carries on by itself). On Android: `assistant/ChatSession.kt`, `assistant/ChatProtocol.kt` |

## Architecture

[![Escudo architecture](docs/diagrams/architecture.png)](docs/diagrams/architecture.svg)

([SVG](docs/diagrams/architecture.svg) · [HTML](docs/diagrams/architecture.html))

- **`escudo-api`** (Cloud Run): a FastAPI process with REST, MCP (`/mcp`), the assistant chat
  (`/assistant/chat`) and three A2A agents (`/a2a/escudo`, `/a2a/tow`, `/a2a/repair`). Every call goes
  through `gateway.call_capability`, which validates input, runs the precheck, applies `autonomy.py`,
  runs the capability and writes the timeline events (validated against `contracts/events.schema.json`).
- **`escudo-web`** (Cloud Run): the customer portal with WebMCP and the `/presenter` view, which reads
  Firestore on the server and streams it to the browser as Server-Sent Events.
- **Escudo for Android** (phone or emulator): policy, claim, fingerprint approvals, the Escudo
  Assistant chat, FCM push and AppFunctions.
- **Firebase**: Auth (customer login), Firestore (database `escudo`), Cloud Storage (claim photos in
  `ai-experiments-487722-escudo-claims`) and FCM (approval push). Vertex AI provides Gemini.

### The scenario, step by step

[![Crash sequence with three approvals](docs/diagrams/crash-sequence.png)](docs/diagrams/crash-sequence.svg)

1. Andrea reports the accident in the app with three photos (`open_claim` + `attach_claim_photos`);
   the damage assessor (multimodal Gemini with structured output) returns parts, severity and cost range.
2. She writes to Asistente Escudo: "help me with everything". Without asking, the assistant calls
   `request_tow` and `list_repair_options` (the Escudo agent delegates over A2A to Grúas Aliadas and the
   Red de Talleres) and `request_rental_car` (automatic because the seeded policy covers it).
3. Three times — `select_repair_shop`, `accept_deductible`, `sign_claim` — the tool returns
   `pending_approval` and changes nothing; a push arrives, Andrea approves with her fingerprint, the chat
   (which watches that approval in Firestore) sends `approval_resolved` and the assistant, on its own,
   repeats the same call with the `approval_id`.

### Tools, autonomy and surfaces

[![Tool map](docs/diagrams/tool-autonomy-map.png)](docs/diagrams/tool-autonomy-map.svg)

The full table, with inputs and outputs, is in [`contracts/tools.md`](contracts/tools.md).

## Quickstart

No cloud (everything simulated: `MOCK_AI=1 MOCK_AUTH=1`, in-memory store, deterministic partner agents):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties   # once: make test includes Android
make up-mock          # API at http://127.0.0.1:8080 (ADMIN_TOKEN=local-admin)
make reset            # in another terminal: back to the initial state (same token)
make scenario         # the whole scenario over MCP, the 3 approvals signed by a test device
make chat-scenario    # the same flow over the chat, with the scripted assistant
make test             # backend (pytest), Android (unit) and web (vitest)
```

Against the cloud (needs ADC and `.env`; see [docs/ONBOARDING.md](docs/ONBOARDING.md)):

```bash
cp .env.example .env  # and fill in the secrets
make reset CLOUD=1    # put the deployed service back in its initial state
make scenario CLOUD=1 APPROVE_DELAY=4   # its own customer for this run; prints …/presenter?run=<id>
make chat-scenario CLOUD=1              # the same, typing to the assistant in the chat
```

Every `make scenario` and `make chat-scenario` run creates its own fictional customer
(`POST /admin/runs`: policy, Firebase login and `run_id`), so concurrent runs never collide with each
other or with the stage customer, who is the seeded one. `make reset` deletes those customers.

No network on stage: `make up-offline` serves the production web build from the laptop and
`http://127.0.0.1:3217/presenter?demo=1` replays a real recorded run, labelled
**REPETICIÓN GRABADA** (recorded replay). Last resort: the video [`docs/escudo-chat-run.mp4`](docs/escudo-chat-run.mp4).

## `make` commands

From the repo root (`make help` lists them all):

| Command | What it does |
| --- | --- |
| `make up` | Local API on `:8080` against real Firestore, Storage and Gemini (ADC, `.env`) |
| `make up-mock` | Local API without cloud (`MOCK_AI=1 MOCK_AUTH=1`, `ADMIN_TOKEN=local-admin`, the same one `make reset` uses) |
| `make up-offline` | No-network fallback: production web build on `127.0.0.1:3217` with self-hosted fonts; `/presenter?demo=1` replays the recorded run |
| `make web` | Local portal and `/presenter` on `:3217` (`npm run dev`, config in `web/.env.local`) |
| `make seed` | Writes policy, shops and tow providers to Firestore and creates the customer's Firebase Auth login (credentials in `.env.local`) |
| `make reset` | `POST /admin/reset` on `ESCUDO_BASE_URL` (< 10 s). `CLOUD=1` targets the deployed service and reads the token from Secret Manager (`escudo-admin-token`) |
| `make test` | Backend `pytest` with `MOCK_AI=1 MOCK_AUTH=1`, Android unit tests and web `vitest` |
| `make scenario` | Full scenario over MCP in test mode, with its own customer (`POST /admin/runs`) and a test device signing the 3 approvals; `CLOUD=1`, `APPROVE_DELAY=4`. Prints `…/presenter?run=<id>` |
| `make chat-scenario` | Typed-chat scenario: own customer, claim and 3 photos over REST (as the app does), then the chat; 3 signed approvals; < 3 min. Against `make up-mock` the scripted assistant answers; `CLOUD=1`, Gemini |
| `make scenario-live` | The MCP scenario with the seeded customer (`--shared`); waits for her to approve each step on the phone |
| `make infra` | Creates or verifies everything in GCP from an empty project: `infra/01..06` (APIs, `escudo` database, bucket, Artifact Registry, Firebase Auth via API, service accounts, Secret Manager) |
| `make deploy` | `deploy-api` + `deploy-web`; each keeps the service's current `min-instances` |
| `make deploy-api` | `infra/10-deploy-api.sh`: builds and deploys `escudo-api` |
| `make deploy-web` | `infra/11-deploy-web.sh`: builds and deploys `escudo-web` |
| `make warmup` | Before going on stage: touches the deployed API, Agent Cards, portal and `/presenter`, prints status and latency (fails unless all 200) |
| `make stage-on` | Talk day: `min-instances=1` on `escudo-api` and `escudo-web` (no cold start) |
| `make stage-off` | Any other day: `min-instances=0` (scale to zero; later deploys keep it) |
| `make photos` | Regenerates the rehearsal crash photos with `IMAGE_MODEL` |
| `make brand` | Regenerates the "A · Sereno" logo and mockups with `IMAGE_MODEL` from `brand/prompts/` (`ARGS="--force logo"`) |
| `make android-build` | `./gradlew assembleDebug` (`live` and `fake` flavors) |
| `make android-test` | Android unit tests (both flavors) |
| `make android-install` | Installs and opens the app (`FLAVOR=live` by default, `FLAVOR=fake` for no network) |
| `make android-emulator` | Starts (or reuses) the `Pixel_9_API_36_1` AVD (Android 16 QPR2, needed for `cmd app_function`) and prints its serial |
| `make android-serial` | adb serial of that emulator; every Android target detects it on its own |

`android/Makefile` adds `make -C android chat-scenario` (chat scenario on the emulator, with a virtual
fingerprint, timed),
`google-services` (downloads `google-services.json`) and `appfunctions` (lists the AppFunctions on the device).

## Repo map

```
AGENTS.md                 guide for coding agents (spec and rules)
Makefile                  the commands above
.env.example              variables (models, data, surfaces, modes); copy to .env
contracts/                shared source of truth
  openapi.yaml            REST API (Android and web)
  tools.md                the 11 capabilities, autonomy, surfaces, approval protocol, chat and push
  events.schema.json      schema of the timeline events
  CHANGELOG.md            current state of the contracts on one page
backend/                  Python 3.13 + uv
  escudo/capabilities/    the domain: one function per capability
  escudo/autonomy.py      what an agent may do alone
  escudo/approvals.py     single-use approvals
  escudo/gateway.py       the one door into the domain
  escudo/adapters/        REST, MCP and the assistant chat
  escudo/agents/          Escudo agent + Tow and Repair partners over A2A
  escudo/damage_assessor.py  damage assessor (Gemini, structured output)
  escudo/scenario.py      scenario runner over MCP (its own customer per run)
  escudo/chat_scenario.py scenario runner over the chat
  escudo/seed.py, admin.py   synthetic data, make seed / make reset
  samples/photos/         3 rehearsal photos + prompts.json
  scripts/                generate_sample_photos.py
  tests/                  pytest (approvals, device signatures, autonomy, A2A, chat, runs, events)
android/                  Kotlin + Compose + Material 3 (see android/README.md)
  app/src/main/…/appfunctions/   AppFunctions
  app/src/main/…/approvals/      ApprovalFlow + BiometricPrompt
  app/src/main/…/assistant/      chat with the assistant (ChatSession, ChatProtocol)
  scripts/chat_scenario.sh       chat scenario on the emulator
.github/workflows/test.yml  CI: make test on every push and PR, mock modes only
web/                      Next.js 16 (see web/README.md)
  src/lib/webmcp.ts       WebMCP tools
  src/app/presenter/      presenter view (1920×1080)
  scripts/serve-offline.sh  make up-offline: production build with no network
infra/                    numbered, idempotent scripts: 01-06 build the project from scratch, 10-11 deploy
brand/                    prompts and generator for the "A · Sereno" logo and mockups (make brand)
data/                     SOURCES.md (origin of every data file and asset) and DATASHEET.md
docs/                     ONBOARDING.md, DEMO.md (demo script, screenshots in docs/demo/), diagrams, screenshots and escudo-chat-run.mp4 (a full run, fallback)
```

## Technical decisions

| Decision | Why | Discarded alternatives |
| --- | --- | --- |
| **One Cloud Run service with Tow and Repair inside it, but A2A only.** The three agents are mounted in `escudo-api` under `/a2a/escudo`, `/a2a/tow` and `/a2a/repair`, each with its own Agent Card. | One deploy, one cold start and one service account. The Escudo agent only knows base URLs (`A2A_PEERS`), reads the Agent Card, indexes by skill and calls the RPC URL the card declares (`agents/peers.py`); it never imports partner code. `tests/test_discovery.py` checks this, including that `escudo_agent.py` contains no partner paths. | Three separate services (more deploys and cold starts without teaching anything new). Calling the partners' functions directly (it would hide the A2A lesson). |
| **The chat keeps no state in the process (up to 3 instances).** | The transcript lives in Firestore (`chat_sessions`) and is rebuilt every turn; while an approval is pending the chat watches it in Firestore, so when the phone approves — whichever instance that request reaches — the chat sends `approval_resolved` and the assistant carries on by itself. So `escudo-api` scales up to 3 instances (`--session-affinity` keeps each WebSocket on its instance). `min-instances` is 0 by default and `make stage-on` raises it to 1 on talk days; `--no-cpu-throttling` and `--timeout=3600` keep the WebSocket alive. | A single instance with in-memory nudges (a single point of failure that forces `max-instances=1`). Pub/Sub to fan out nudges (more parts for no reason: Firestore already holds the state). |
| **Typed chat instead of voice.** | On the emulator the live microphone never worked — only pre-recorded audio did — and Gemini Live socket drops added risk on stage. Text is readable by the audience, rehearses deterministically (`make chat-scenario`) and uses the same agent, the same MCP tools and the same approval pauses: the talk's lesson doesn't change. The model is `GEMINI_MODEL` with `thinking_level=LOW`. | Voice with Gemini Live (`Runner.run_live`). |
| **Its own customer per scenario run.** | `POST /admin/runs` (admin token) creates an ephemeral customer: its policy, a Firebase user `run_<id>` with the `customer_id` claim, and a `run_id`. The runners send `X-Escudo-Run-Id`, and `/presenter?run=<id>` follows only that run (without `?run`, the run of the latest claim). Without the header every event carries the customer's run, so an aside over MCP or WebMCP lands on the same timeline. Two rehearsals at once — or a rehearsal while the stage phone is connected — never step on each other. `make reset` deletes those customers, their users and their devices; the stage customer stays the seeded one. The `escudo-api` account has `roles/firebaseauth.admin` for this (`infra/04`). | One shared customer (runs stepped on each other). |
| **CI with GitHub Actions.** | `.github/workflows/test.yml` runs `make test` on every push to `main` and every PR: backend `pytest`, web `vitest` and Android `testDebugUnitTest` with JDK 17, mock modes only (no cloud calls), with uv, npm and Gradle caches. A Cloud Build GitHub trigger needs a one-time manual OAuth connection in the console (there is no CLI or API path); Actions needs nothing beyond the repo. | A Cloud Build trigger. |
| **Approval bound to tool + arguments hash, single use.** | `approvals.args_hash` is the SHA-256 of the canonical JSON `{tool, args}` (without `approval_id` or nulls). `redeem` checks customer, tool, hash and status inside one atomic update and marks the approval `used`. An approval for "Taller Carrocería Diez on Tuesday" does not work for another shop, another slot, or twice. Before asking, the server runs the precheck so a customer is never asked to approve something that would fail. A misused approval (other tool or other data) is not burned. | Telling the model in the prompt to "ask first" (a prompt is not a control). Exposing `approve` as a tool (the agent would approve itself). A generic "yes" token not bound to the action shown. |
| **Approving takes a phone signature, not just the token (`escudo-approval:v1`).** | An agent holding the customer's token (MCP, chat, A2A) could call `POST /approvals/{id}/approve` itself. So the app registers an EC P-256 key from the Android Keystore (unlocked by BiometricPrompt) via `POST /devices` and signs `escudo-approval:v1:{approval_id}:{args_hash}:approve`; the server (`backend/escudo/devices.py`) checks owner, registered device and signature, and answers 403 with no state change otherwise. The scenario runner plays the phone with a software test device (`escudo/test_device.py`). | Trusting the token (any agent holding it could approve). A PIN or code sent by push (can be relayed to an agent). |
| **Optional A2A shared secret (`A2A_SHARED_SECRET`).** | Tow and Repair only answer RPC carrying `X-Escudo-A2A-Key` (compared with `hmac.compare_digest` in `main.partner_key_guard`). Their Agent Cards stay public — discovery must work — and declare the `securitySchemes.partnerKey` scheme, so a caller learns *how* to authenticate from the card. The Escudo agent (`/a2a/escudo`) answers about a customer's claims, so it requires a credential: the customer's Firebase token (acts as her) or the partner key plus `X-Escudo-Customer-Id` (`main.EscudoAgentGuard`); its card declares both schemes. Like REST, MCP and the chat, it acts as the verified token's `customer_id`, never as a fixed customer. The secret lives in Secret Manager (`escudo-a2a-secret`, `infra/06-secrets.sh`) and Cloud Run mounts it; empty locally and in tests. | OAuth or mTLS between partners: right between real companies, out of proportion for partners living in the same service. |
| **Phone mirror drawn from backend state.** | The right panel of `/presenter` (`web/src/components/presenter/PhoneMirror.tsx`) shows what the app shows — pending approval first, then the claim — from the same Firestore data. It does not depend on USB, the emulator or a capture process, and it always matches the timeline. | Screen capture with `scrcpy` as the main source (kept as an option for pixel-exact mirroring: a `scrcpy` window over the panel). A custom stream from the app. |
| **The server picks the claim, not the model.** | Every chat turn the server reminds the assistant of the active claim, and `gateway.call_capability` replaces any `claim_id` an agent invents or takes from another customer with the newest open claim (`repository.active_claim`). A model that hallucinates an id (`clm_96051512`) no longer breaks the scenario. Each chat model call is also capped at 30 s and retried. | Trusting the prompt (it failed in a real rehearsal). Rejecting the invented id (the scenario stalled). |
| **Actors under their real name.** | On the timeline the chat is "Asistente Escudo"; whoever calls over MCP, WebMCP or AppFunctions (`adb` on stage) is "Agente externo". No surface says "Gemini" unless it is. The presenter also shows when an agent discovers another (Agent Card) or lists the tools (`tools/list`), and tags steps that need no approval as "automático". | Labelling everything "Gemini". |
| **A labelled no-network fallback.** | `make up-offline` serves the production build from the laptop with self-hosted fonts (`next/font/local`), and `/presenter?demo=1` replays a real recorded chat run (`run-6958m5`, 37 events). The screen says **REPETICIÓN GRABADA** and "Reclamo grabado", never "en vivo". Last resort: `docs/escudo-chat-run.mp4`. | An unlabelled replay (misleads the audience). Google Fonts (fails offline). |
| **Photo downscaling in two places.** | On the phone, `PhotoLoader` keeps each photo at ≤ 1600 px, JPEG 80 (uploading raw 12 MP photos took ~40 s). On the backend, `damage_assessor.for_assessment` takes them to 1024 px, JPEG 80, for the model only: three photos took 86–90 s and dropped to 6–10 s with the same verdict. The received originals are stored in Cloud Storage in parallel with the assessment. The assessor also uses `thinking_level=LOW` and `MEDIA_RESOLUTION_MEDIUM`. | Sending full-resolution photos (slow, no better result). |
| **Everything runs in the cloud, plus the phone.** See the table below. | The talk needs nothing of its own on the laptop: no local model and no latency measured on stage hardware. The scenario depends on the venue network only for the phone and the browser, and anyone can repeat the demo with the deployed URLs. | Running the backend on the laptop (one more thing that can fail on stage). |

### Where each component runs

| Component | Runs on | Why |
| --- | --- | --- |
| `escudo-api` (REST, MCP, chat, A2A agents, assessor) | Cloud Run `us-central1`, 0–3 instances (1 kept warm with `make stage-on`), service account `escudo-api` | No state in the process: the chat and its approvals live in Firestore |
| `escudo-web` (portal, WebMCP, `/presenter`) | Cloud Run `us-central1`, 0–2 instances (1 with `make stage-on`), service account `escudo-web` (Firestore read-only) | The presenter needs no login: the server reads Firestore and streams SSE |
| Firestore, Storage, Auth, FCM | Firebase (project `ai-experiments-487722`, database `escudo`) | Used as SDKs, never Firebase Hosting |
| Gemini (assessor, chat, Escudo agent) | Vertex AI (`global`) | Models come from environment variables |
| Escudo for Android | The presenter's phone; in rehearsals, the `Pixel_9_API_36_1` emulator | Fingerprint and AppFunctions belong to the device |
| Laptop | Only a browser (Chrome with `/presenter` and the WebMCP portal) and, in rehearsals, the emulator | No Escudo component runs on the laptop, except the no-network fallback (`make up-offline`) |

## Preview API versions

Checked on 2026-09-30. Details in [`backend/VERSIONS.md`](backend/VERSIONS.md),
[`android/VERSIONS.md`](android/VERSIONS.md) and [`web/VERSIONS.md`](web/VERSIONS.md).

| Piece | Version |
| --- | --- |
| Agent Development Kit (`google-adk[a2a]`) | 2.10.0 |
| A2A Python SDK (`a2a-sdk[http-server]`) | 1.2.1 — A2A protocol 1.0, Agent Card at `/.well-known/agent-card.json`, JSON-RPC binding |
| MCP Python SDK (`mcp`) | 2.2.0 — `MCPServer`, stateless streamable HTTP at `/mcp` |
| Google Gen AI SDK (`google-genai`) | 2.25.0 (Vertex AI) |
| Gemini | `GEMINI_MODEL=gemini-3.8-flash` (global), `IMAGE_MODEL=gemini-3.1-flash-lite-image` |
| Jetpack AppFunctions (`androidx.appfunctions`) | 1.0.0-alpha12; `adb shell cmd app_function` from Android 16 QPR2 (API 36.1) |
| Gemini + third-party AppFunctions | Private preview; not available to this app. On stage, AppFunctions are shown with `adb` |
| WebMCP | W3C WebML CG draft of 2026-09-30 (`document.modelContext.registerTool`); tested on Chrome 154.0.8037.59 with `chrome://flags/#enable-webmcp-testing` |
| Next.js / React | 16.3.8 / 19.2.8 |
| Firebase | Android BoM 34.19.0, JS SDK 12.19.0, `firebase-admin` 7.7.0 (Python) and 14.5.0 (Node) |

## Deployed URLs

| What | URL |
| --- | --- |
| API | https://escudo-api-611681112050.us-central1.run.app (`/health`) |
| MCP | https://escudo-api-611681112050.us-central1.run.app/mcp (bearer: Firebase ID token) |
| Agent Cards | https://escudo-api-611681112050.us-central1.run.app/a2a/escudo/.well-known/agent-card.json (also `/a2a/tow/…` and `/a2a/repair/…`) |
| Chat | `wss://escudo-api-611681112050.us-central1.run.app/assistant/chat` (`Authorization` header) |
| Portal | https://escudo-web-611681112050.us-central1.run.app/ |
| Presenter | https://escudo-web-611681112050.us-central1.run.app/presenter (`?run=<id>` follows one run; `?demo=1` replays a recorded run with no cloud, labelled REPETICIÓN GRABADA) |

## Credits and provenance

- **Rehearsal crash photos** (`backend/samples/photos/crash_{1,2,3}.jpg`): synthetic, generated with
  `gemini-3.1-flash-lite-image` by `backend/scripts/generate_sample_photos.py` (`make photos`, seed
  2027). Each image's model, prompt and seed are in `backend/samples/photos/prompts.json`.
- **Seed data** (customer Andrea Morales, policy `POL-GT-2027-0042`, shops, tow providers): fictional,
  written by `backend/escudo/seed.py`; every record carries `origin` (`synthetic`, seed 2027).
- **Visual identity** "A · Sereno": tokens in `android/design/a-sereno/tokens.json` and
  `web/src/brand/tokens.json`, vector mark `android/design/a-sereno/mark.svg`; synthetic logo and
  mockups (`IMAGE_MODEL`) with their prompts and generator in `brand/` (`make brand`).
- **Typeface**: Onest (SIL OFL 1.1, `android/design/a-sereno/Onest-OFL.txt`; on the web, self-hosted in `web/src/app/fonts/` with its license).
- **Diagrams** (`docs/diagrams/`): made with the `diagram-design` skill (light editorial skin, pine accent `#0F5C4D`).

All companies, people, plates and addresses are fictional. Full inventory of data and generated assets
in [`data/SOURCES.md`](data/SOURCES.md); datasheet in [`data/DATASHEET.md`](data/DATASHEET.md).
