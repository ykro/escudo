# Escudo capabilities

One domain function per capability lives in `backend/escudo/capabilities/`. Every surface is a thin adapter over the same function. Autonomy is defined once in `backend/escudo/autonomy.py` and enforced by the server.

| Capability | Input | Output | Autonomy | MCP | REST | AppFunctions | WebMCP |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `get_policy` | – | policy (coverage list, deductible_usd, vehicle, rental_days) | automatic | ✓ | `GET /policy` | `getCoverage` | `getCoverage`, `getDeductible` |
| `check_coverage` | incident_type | covered: bool, notes | automatic | ✓ | `GET /coverage/check` | `getCoverage` | `getCoverage` |
| `open_claim` | description, location {lat,lng,label}, occurred_at | claim | automatic | ✓ | `POST /claims` | `reportAccident` | – |
| `attach_claim_photos` | claim_id, photos[] (base64 or gs:// uri) | damage assessment (parts, severity, cost_range_usd) | automatic | ✓ | `POST /claims/{id}/photos` | `reportAccident` | – |
| `request_tow` | claim_id | tow dispatch (provider, eta_minutes) via A2A | automatic | ✓ | `POST /claims/{id}/tow` | – | – |
| `list_repair_options` | claim_id | shops[] with slots and quotes via A2A | automatic | ✓ | `GET /claims/{id}/repair-options` | – | `findRepairShops` |
| `request_rental_car` | claim_id | rental reservation | automatic if covered, else needs_approval | ✓ | `POST /claims/{id}/rental` | – | – |
| `accept_deductible` | claim_id, approval_id? | accepted | needs_approval | ✓ | `POST /claims/{id}/deductible` | – | – |
| `select_repair_shop` | claim_id, shop_id, slot, approval_id? | booking | needs_approval | ✓ | `POST /claims/{id}/repair` | – | – |
| `sign_claim` | claim_id, approval_id? | submitted claim | needs_approval | ✓ | `POST /claims/{id}/sign` | – | – |
| `get_claim_status` | claim_id? (latest if omitted) | claim with timeline | automatic | ✓ | `GET /claims/{id}` | `getClaimStatus` | `getClaimStatus` |

## Approval protocol

1. A gated capability called without a valid `approval_id` returns
   `{"status": "pending_approval", "approval_id": "apr_…", "reason": "…", "tool": "…", "args_hash": "…"}` and changes nothing.
2. The backend stores the approval (`approvals/{id}`: status `pending`, tool, args_hash, reason, summary, amount_usd, claim_id, created_at), writes an `approval_requested` event and sends an FCM push to the customer's devices (see *Approval push* below).
3. The customer approves in the Android app with BiometricPrompt → `POST /approvals/{id}/approve` (customer Firebase ID token). **This endpoint is never exposed as a tool, AppFunction or WebMCP function.**
4. The agent retries the same capability with `approval_id`. The server checks: exists, status `approved`, same tool, same args_hash, not used. Then executes and marks it `used`.
5. Anything else → `403` / MCP error `approval_invalid`. The REST body is
   `{"error": "approval_invalid", "detail": <why>, "message": <Spanish>}`; the MCP tool error text is
   `approval_invalid (<why>): <Spanish>`. `<why>` is one of `unknown`, `not_approved` (still pending),
   `rejected`, `wrong_tool`, `wrong_args`, `already_used`. A misused approval (wrong tool or args) is not
   burned; a used one never works again.
6. Before asking the human, the server runs the capability's precheck (claim exists, shop/slot was offered,
   deductible not already accepted, …), so a customer is never asked to approve something that would fail.

## Surfaces

- MCP: streamable HTTP at `/mcp`, bearer = customer Firebase ID token.
- A2A: Escudo agent at `/a2a/escudo`, Tow at `/a2a/tow`, Repair at `/a2a/repair`, each with its Agent Card at the well-known path of the current A2A version. Escudo discovers peers from `A2A_PEERS` (base URLs only).
- Voice: Escudo Assistant WebSocket at `/assistant/live` (PCM16 16 kHz in, 24 kHz out), ADK + Gemini Live, uses the MCP tools (loopback to `/mcp` with the customer's own token, header `X-Escudo-Actor: escudo_assistant`).
- REST caller labels (optional headers on every REST call): `X-Escudo-Surface` (a `surface` value of
  events.schema.json, default `rest`), `X-Escudo-Actor` (an `actor` value, default `customer`),
  `X-Escudo-Run-Id` (groups a run; default the claim id). The backend writes the capability's
  `tool_call` / `tool_result` / `approval_requested` events with these labels, so clients do not post
  duplicates to `/events`. Unknown values fall back to the defaults. MCP clients may send
  `X-Escudo-Actor` and `X-Escudo-Run-Id` (surface is always `mcp`; actor defaults to `gemini`).

## Approval push (FCM)

Sent to every device registered with `POST /devices` for the customer when an approval is created.

| Field | Value |
| --- | --- |
| `notification.title` | `Escudo necesita tu aprobación` |
| `notification.body` | the approval `summary` (Spanish) |
| `android.priority` / `android.notification.channel_id` | `high` / `approvals` |
| `data.type` | `approval_request` |
| `data.approval_id`, `data.tool`, `data.claim_id`, `data.reason` | strings (`claim_id` may be empty) |

The app opens `escudo://approvals/{approval_id}`, shows the summary, and after BiometricPrompt calls
`POST /approvals/{approval_id}/approve` with `{"device_id": …}`. Devices answering `UNREGISTERED` to FCM
are disabled. If a voice session of that customer is open, approving also nudges the assistant
(`[Aviso de Escudo] La clienta aprobó <tool> … approval_id=…`) so it retries on its own.

## Voice WebSocket `/assistant/live`

- **Auth**: `Authorization: Bearer <Firebase ID token>` header, or `?token=<id token>` query parameter.
  On failure the server sends `{"type":"error","code":"unauthenticated"|"invalid_token","message":…}` and
  closes with code `4401`.
- **Audio**: binary frames, raw PCM signed 16-bit little-endian, mono.
  Phone → server: **16 000 Hz** (any chunk size; 20–100 ms recommended).
  Server → phone: **24 000 Hz**, played in arrival order.
- **Phone → server text frames** (JSON):
  - `{"type":"text","text":"…"}` — a typed user turn (rehearsals, accessibility).
  - `{"type":"end"}` — close the session.
  - `{"type":"activity_start"}` / `{"type":"activity_end"}` — accepted and ignored (turns are detected
    by automatic voice activity detection; Android appends 2 s of silence on push-to-talk release: measured against the deployed Live model, 800 ms never ends the turn and 2 s always does).
- **Server → phone text frames** (JSON):

| `type` | Fields | Meaning |
| --- | --- | --- |
| `ready` | `mock`, `session_id`, `input_audio` {encoding `pcm_s16le`, sample_rate 16000, channels 1}, `output_audio` {… 24000 …} | First frame after auth: connected |
| `transcript` | `role` (`user`\|`assistant`), `text`, `final` | Live captions. `final:false` frames are deltas to append; a `final:true` frame carries the whole utterance and replaces the deltas since the previous final of that role |
| `tool_call` | `name` | The assistant called an MCP tool |
| `tool_result` | `name` | The tool answered |
| `approval_required` | `approval_id`, `tool`, `summary`, `reason` | A gated tool returned `pending_approval` (the push is on its way) |
| `interrupted` | – | The user barged in: stop playback |
| `turn_complete` | – | The model finished a turn (also after a tool-only turn) |
| `error` | `code`, `message` (Spanish) | Session failed; the server closes the socket |

- `MOCK_AI=1`: same protocol with `ready.mock=true`; text turns are echoed and answered with 100 ms of silence.
- Model: `GEMINI_LIVE_MODEL` on Vertex AI in `GEMINI_LIVE_LOCATION` (see `.env.example`).
