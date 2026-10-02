# Escudo — agent-ready car insurer

Talk: **Built for Agents: Making Your Apps Usable by AI.**

Escudo is a fictional car insurer whose capabilities are usable by AI agents from three surfaces: an Android app with AppFunctions, a website with WebMCP, and an MCP server. Its own agent delegates to a tow-truck agent and a repair-shop agent over A2A. The scenario — "tuve un choque menor, aquí están las fotos, ayúdame con todo" — runs end to end with exactly three human approvals.

## What the talk teaches

| Principle | Where |
| --- | --- |
| Functions, not screens | `backend/escudo/capabilities/` is the single domain; REST, MCP, AppFunctions and WebMCP are thin adapters over the same functions |
| What an agent may do alone vs. what needs a human | `backend/escudo/autonomy.py`: one table, enforced by the server. Approving is never a tool |
| Where MCP and A2A fit | tools via MCP; tow and repair via A2A, discovered by Agent Card |

## Contracts (source of truth — read first)

- `contracts/openapi.yaml` — REST API used by Android and web.
- `contracts/tools.md` — the 11 capabilities, their autonomy and the surfaces that expose them.
- `contracts/events.schema.json` — the shared event schema for the presenter timeline.

## Components

| Component | Stack | Runs on |
| --- | --- | --- |
| Escudo backend (`backend/`) | Python, FastAPI, official MCP SDK (streamable HTTP), ADK, Firestore db `escudo`, Firebase Storage/Cloud Storage bucket `ai-experiments-487722-escudo-claims`, FCM | Cloud Run service `escudo-api` |
| Escudo Assistant | The customer's chat agent: ADK + `GEMINI_MODEL`, uses the MCP tools; typed chat over a WebSocket (`/assistant/chat`), transcript in Firestore | Inside the backend (WebSocket), chat from the Android app |
| Escudo / Tow / Repair agents | ADK agents, each with its own A2A endpoint and Agent Card | Inside the backend, separate mount paths |
| Damage assessor | Gemini multimodal (`GEMINI_MODEL`): damaged parts, severity, cost range | Inside the backend |
| Escudo for Android (`android/`) | Kotlin, Compose, Material 3, Jetpack AppFunctions, Firebase Auth, FCM, BiometricPrompt | Presenter's phone |
| Escudo web (`web/`) | Next.js, Firebase Auth, WebMCP tools, presenter view | Cloud Run service `escudo-web` |

Tow and repair live in the same service for simplicity, but they only talk to the Escudo agent over A2A, exactly as if they belonged to other companies.

## Seed data (`make seed`, `make reset`)

- One customer with an active policy: own damage, towing included, replacement car for 7 days, 500 USD deductible.
- Three repair shops with open slots and two tow providers with simulated arrival times based on location.
- Three sample photos of a minor crash generated with `IMAGE_MODEL` (script + prompt committed) for rehearsals; new photos from the phone also work.

## Tools

| Tool | What it does | Autonomy |
| --- | --- | --- |
| `get_policy` | Coverage, deductible and vehicle | Automatic |
| `check_coverage` | Whether an incident type is covered | Automatic |
| `open_claim` | Opens a claim with description, location and time | Automatic |
| `attach_claim_photos` | Uploads photos and runs the damage assessor | Automatic |
| `request_tow` | Requests a tow; the Escudo agent delegates over A2A | Automatic |
| `list_repair_options` | Shops, slots and quotes gathered over A2A | Automatic |
| `request_rental_car` | Replacement car if the policy covers it | Automatic if covered, otherwise needs approval |
| `accept_deductible` | Agrees to pay the deductible | Needs approval |
| `select_repair_shop` | Confirms shop and slot | Needs approval |
| `sign_claim` | Signs and submits the claim | Needs approval |
| `get_claim_status` | Current claim status | Automatic |

## Human approval

- Enforced by the server, not the prompt: a gated tool without a valid `approval_id` returns `pending_approval` with a new `approval_id` and does nothing else.
- The customer gets a push notification (FCM) and approves in the Android app with biometrics (`POST /approvals/{id}/approve`, Firebase ID token of the customer + device-bound signal). Only then can the agent retry the action with the approved `approval_id`. An approval is single-use and bound to tool + arguments hash.
- Approving is never exposed as a tool, AppFunction or WebMCP function.
- MCP calls are authenticated with the customer's Firebase ID token (`MOCK_AUTH=1` for local tests).

## A2A

- Each agent publishes an Agent Card at the well-known path defined by the current A2A version.
- The Escudo agent discovers Tow and Repair through their Agent Cards from a registry list of base URLs (`A2A_PEERS`), never hard-coded endpoint paths.
- Tow returns provider and ETA; Repair returns slots and quotes based on the damage assessment.

## Android with AppFunctions

- Exposed functions: `reportAccident` (gallery photos + description), `getCoverage`, `getClaimStatus`, all backed by the same API.
- Screens: policy, claim status, pending approvals (biometric), Escudo Assistant (chat).
- Escudo Assistant (ADK + `GEMINI_MODEL`, typed chat) is the primary agent on stage. If Gemini's AppFunctions integration is available at build time, the same scenario also runs through Gemini; otherwise AppFunctions are shown with the Android developer tools.

## Web with WebMCP

- Registered tools: `getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`.
- Tested in Chrome with the WebMCP implementation available at build time (record version). On stage WebMCP is a short aside.

## Presenter view (`escudo-web` route `/presenter`)

- Live timeline of every step: which agent acts, through which surface (Android, web, MCP, A2A), which tool, the result.
- Approval pauses stand out with the reason until the customer approves.
- Every component writes events (schema `contracts/events.schema.json`) to Firestore `events`; the view reads them in real time. Next to it, a mirror of the phone screen (scrcpy capture or the app's own mirror stream — pick the simplest reliable option and document it).

## Acceptance criteria

- Full scenario, report to signed claim, < 3 minutes with exactly three human approvals.
- Automated test: the server rejects every approval-gated action without a valid `approval_id`.
- The Escudo agent finds Tow and Repair only through Agent Cards (test).
- AppFunctions and WebMCP tools show up in their developer tools.
- `make reset` restores policy, shops and tow providers and clears claims and events.
- `make test` passes (backend pytest, Android unit tests, web tests).

## Shared rules (all four demo repos)

These demos are teaching material for a talk. The code, folder names, reports and docs must make the talk's ideas visible. Prefer small files named after the concept they teach, and comments that explain *why*, not *what*.

### Stack

| Layer | Choice |
| --- | --- |
| Web | Next.js (App Router, TypeScript), React |
| Android | Kotlin, Jetpack Compose, Material 3 |
| Cloud models | Gemini via Vertex AI with the Google Gen AI SDK (`@google/genai` / `google-genai`), ADC, `GOOGLE_CLOUD_LOCATION=global` |
| Images | Nano Banana 2 Lite (`IMAGE_MODEL`) via Vertex AI |
| Local models | Gemma served with Ollama; EmbeddingGemma where retrieval is needed |
| Fine-tuning | LoRA with Hugging Face TRL + PEFT as a Vertex AI Custom Job (L4, `europe-west4`); same script wrapped in a Colab notebook; exported to quantized GGUF for Ollama |
| Agents | Agent Development Kit (ADK, Python); MCP for tools; A2A for agent-to-agent |
| Cloud | Google Cloud project `ai-experiments-487722`. Every web app or API runs on **Cloud Run** (`us-central1`, Dockerfile + Cloud Build + Artifact Registry repo `charlas2027`). Firebase services (Auth, Firestore named database per app, FCM, Storage, App Check) are used as SDKs, never Firebase Hosting |
| Local data | JSON/JSONL files or SQLite in the repo |

### Cloud first

Everything runs in the cloud unless the talk requires the laptop (offline model, latency measured on the stage hardware). Every component that runs on the laptop is listed in the README's "Decisiones técnicas" section with the reason.

### Rules

- No model name or version is hard-coded. Models come from env vars (`GEMINI_MODEL`, `IMAGE_MODEL`, `GEMMA_BASE_MODEL`, `LOCAL_MODEL_TAG`, `EMBEDDING_MODEL`) documented in `.env.example`.
- Preview APIs (AppFunctions with Gemini, WebMCP, UCP, AP2, A2A, MCP SDK, ADK): check the current official docs before implementing (context7 or the web) and record the version used in the README.
- `Makefile` targets: `make up` (start everything needed locally), `make reset` (back to the initial state in under 10 seconds), `make test`, `make deploy`, and where it applies `make eval`, `make train`.
- Data: every record carries `origin` (`web` with URL + license, `synthetic` with model + prompt + seed, `human`). Documented in `data/SOURCES.md` and `data/DATASHEET.md`. Fictional seed data is generated by scripts. No secrets in the repo.
- Python uses `uv`. Node uses npm. Code in English; UI in Spanish (Guatemala/LatAm neutral).
- Tests must pass before a task is done (`make test`). Anything that needs the cloud also has a mock mode (`MOCK_AI=1`) so tests and rehearsals run without network.
- No `CLAUDE.md` in the repo.

### UI/UX bar

- Light theme only. No dark mode. Not overloaded: each screen shows what matters and nothing else.
- Production-grade: own brand identity (name, logo mark, palette, typography), consistent components, designed empty/loading/error states, no placeholder text.
- Web: responsive, keyboard accessible, WCAG 2.2 AA contrast, purposeful motion only. Android: Material 3 and platform conventions.
- Every app on stage has a presenter view: a separate route readable from the back of an auditorium (large type, high contrast, 16:9 at 1920×1080), no logins or manual steps during the talk.
- Visual assets (logos, product photos, sample photos) are generated with `IMAGE_MODEL` by a script in `scripts/` and committed; the prompt is kept next to the asset.

### Docs (written at the end, from the finished code)

- `README.md` (Spanish) and `README.en.md`: what you'll learn, architecture diagram, quickstart, `make` commands, repo map, **technical decisions** (what, why, discarded alternatives, where each component runs and why), preview API versions.
- `docs/ONBOARDING.md`: prerequisites with versions, local setup, credentials/ADC, GCP setup with `infra/NN-*.sh`, run/test/rehearse, code walkthrough, troubleshooting, talk-day checklist.
- `docs/diagrams/`: made with the `diagram-design` skill, exported to SVG and PNG.
- Never describe the project as a deviation from a spec; the docs describe what the project is.
