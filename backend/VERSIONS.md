# Versions (checked 2026-09-30)

Preview-era APIs move fast; these are the versions the backend is built and tested with
(`uv.lock` pins the full tree). Python 3.13 (`.python-version`), uv 0.8.

| Piece | Version | Notes |
| --- | --- | --- |
| Agent Development Kit (`google-adk[a2a]`) | 2.10.0 | `LlmAgent`, `Runner.run_async` with SSE streaming (chat), `McpToolset` + `StreamableHTTPConnectionParams`, `RemoteA2aAgent`, `A2aAgentExecutor`. ADK 2.10 pins FastAPI < 0.142 |
| A2A Python SDK (`a2a-sdk[http-server]`) | 1.2.1 | A2A protocol **1.0** (`PROTOCOL_VERSION_CURRENT`); Agent Card at **`/.well-known/agent-card.json`** relative to each agent's base URL; JSON-RPC binding; cards are protobuf `AgentCard` with `supportedInterfaces` |
| MCP Python SDK (`mcp`) | 2.2.0 | `mcp.server.mcpserver.MCPServer` (FastMCP was renamed in 2.x), streamable HTTP at `/mcp` with `stateless_http=True`, `json_response=True`; auth via the SDK's `TokenVerifier` + `AuthSettings` (Firebase ID tokens); clients use `httpx2` |
| Google Gen AI SDK (`google-genai`) | 2.25.0 | Vertex AI (`vertexai=True`), structured output with Pydantic `response_schema` |
| Text / vision model | `GEMINI_MODEL=gemini-3.8-flash` (global) | Damage assessor, Asistente Escudo chat and the Escudo A2A agent (`thinking_level=LOW`); Tow/Repair use no model |
| Image model | `IMAGE_MODEL=gemini-3.1-flash-lite-image` (global) | Sample photos; often rate limited (429), the script backs off |
| firebase-admin | 7.7.0 | ID token verification, FCM `send_each_for_multicast`, Auth user for `make seed` |
| google-cloud-firestore / storage | 2.33.0 / 3.15.1 | Async Firestore client on the named database `escudo` |
| FastAPI / Starlette / uvicorn | 0.141.1 / 1.7.0 / 0.54.0 | |

## Integration notes

- A2A partners are mounted under a prefix (`/a2a/tow`), so their cards live at
  `/a2a/tow/.well-known/agent-card.json`; `A2A_PEERS` holds those base URLs.
- Tow and Repair are deterministic ADK agents (`BaseAgent`, no model call): the quote and ETA are
  computed. Measured in Cloud Run on 2026-10-01: an LLM tool-choice call cost 2-30 s with spikes past a
  25 s timeout; a Gemini-written note within a 1.5 s budget arrived only 5 of 31 times. Deterministic:
  A2A round trip median 0.10 s, p95 0.18 s; `list_repair_options` end to end p95 1.11 s.
  `A2A_TIMEOUT_SECONDS` = 2 (2x p95 floored), enforced with `asyncio.wait_for`; the one-time cold path
  (3-7 s) is paid by a warm-up request to each partner right after startup.
- Damage assessment: photos downscaled to 1024 px, one multimodal call, `thinking_level=LOW`, hedged:
  a new identical call starts every `ASSESS_HEDGE_SECONDS` (5 s) or on failure, up to
  `ASSESS_HEDGE_ATTEMPTS` (4), each on its own connection; first answer wins; hard deadline `ASSESS_DEADLINE_SECONDS` (45 s). The model
  is only served from `global`, so the backup cannot go to another region. Timings in the logs as
  `assessment_timing`.
- pytest runs async tests with the anyio plugin: MCP's task groups need fixture setup and teardown
  in the same task (pytest-asyncio splits them).
- Asistente Escudo is a typed chat (`/assistant/chat`): an ADK `LlmAgent` on `GEMINI_MODEL` with
  `McpToolset` looping back to `/mcp`. Each turn rebuilds the conversation from Firestore
  (`chat_sessions`) into a fresh in-memory ADK session, and a pending approval is polled in Firestore,
  so any Cloud Run instance can serve any turn and the chat resumes after an approval (`max-instances=3`).
