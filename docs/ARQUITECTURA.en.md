# Escudo: architecture and where each idea lives

> [README](../README.en.md) · [Español](ARQUITECTURA.md)

## What it teaches and where it lives in the code

| Idea | Where the code shows it |
| --- | --- |
| **Functions, not screens.** The domain is 11 functions; every surface is a thin adapter over them. | `backend/escudo/capabilities/` (one file per capability, registry in `capabilities/__init__.py`). Adapters: `adapters/rest.py`, `adapters/mcp_server.py`, `adapters/assistant_chat.py`, `agents/escudo_agent.py`. All of them go through one door: `gateway.call_capability`. On Android, screens and AppFunctions share `EscudoRepository`; on the web, screens and WebMCP share `api()` |
| **What an agent may do alone and what needs a person.** One table, enforced by the server, not by the prompt. | `backend/escudo/autonomy.py` (`AUTONOMY` table + `enforce()`), `approvals.py` (single-use approval bound to tool + arguments hash). Approving is **never** a tool: only `POST /approvals/{id}/approve` exists, called by the app after `BiometricPrompt` (`android/.../approvals/ApprovalFlow.kt`) |
| **Where MCP fits and where A2A fits.** Tools over MCP; other companies' work over A2A, discovered by Agent Card. | MCP: `adapters/mcp_server.py` (`/mcp`, streamable HTTP). A2A: `agents/hosting.py` (Agent Card at `/.well-known/agent-card.json`), `agents/peers.py` (discovery from `A2A_PEERS`), `agents/tow_agent.py`, `agents/repair_agent.py` |
| **AppFunctions**: the Android app as a tool for system agents. | `android/app/src/main/kotlin/app/escudo/android/appfunctions/EscudoAppFunctions.kt` (`reportAccident`, `getCoverage`, `getClaimStatus`), agent-facing description in `res/xml/app_metadata.xml`. See [`android/APPFUNCTIONS.md`](../android/APPFUNCTIONS.md) |
| **WebMCP**: the website as a tool for the browser's agent. | `web/src/lib/webmcp.ts` (`getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`, all read-only) and `web/src/components/portal/WebMcpTools.tsx` (registration with `document.modelContext.registerTool`) |
| **A chat assistant** with exactly the same powers — and the same pauses — as any MCP client. | `backend/escudo/adapters/assistant_chat.py` (ADK `LlmAgent` on `GEMINI_MODEL` + `McpToolset` looping back to `/mcp` with the customer's own token; transcript in Firestore; after an approval the assistant carries on by itself). On Android: `assistant/ChatSession.kt`, `assistant/ChatProtocol.kt` |

## Architecture

[![Escudo architecture](diagrams/architecture.png)](diagrams/architecture.svg)

([SVG](diagrams/architecture.svg) · [HTML](diagrams/architecture.html))

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

[![Crash sequence with three approvals](diagrams/crash-sequence.png)](diagrams/crash-sequence.svg)

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

[![Tool map](diagrams/tool-autonomy-map.png)](diagrams/tool-autonomy-map.svg)

The full table, with inputs and outputs, is in [`contracts/tools.md`](../contracts/tools.md).

## Deployed URLs

| What | URL |
| --- | --- |
| API | https://escudo-api-611681112050.us-central1.run.app (`/health`) |
| MCP | https://escudo-api-611681112050.us-central1.run.app/mcp (bearer: Firebase ID token) |
| Agent Cards | https://escudo-api-611681112050.us-central1.run.app/a2a/escudo/.well-known/agent-card.json (also `/a2a/tow/…` and `/a2a/repair/…`) |
| Chat | `wss://escudo-api-611681112050.us-central1.run.app/assistant/chat` (`Authorization` header) |
| Portal | https://escudo-web-611681112050.us-central1.run.app/ |
| Presenter | https://escudo-web-611681112050.us-central1.run.app/presenter (`?run=<id>` follows one run; `?demo=1` replays a recorded run with no cloud, labelled REPETICIÓN GRABADA) |

![Presenter view during an approval pause](screenshots/presenter-chat-pause.png)
