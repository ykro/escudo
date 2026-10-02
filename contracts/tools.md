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
3. The customer approves in the Android app with BiometricPrompt, which unlocks a device-bound key that
   signs the decision → `POST /approvals/{id}/approve` with the customer's Firebase ID token **and** a
   signature from a registered device (protocol `escudo-approval:v1` below). A token alone cannot approve:
   an agent holding the customer's token (MCP, chat, A2A) has no device key. **This endpoint is never
   exposed as a tool, AppFunction or WebMCP function.**
4. The agent retries the same capability with `approval_id`. The server checks: exists, status `approved`, same tool, same args_hash, not used. Then executes and marks it `used`.
5. Anything else → `403` / MCP error `approval_invalid`. The REST body is
   `{"error": "approval_invalid", "detail": <why>, "message": <Spanish>}`; the MCP tool error text is
   `approval_invalid (<why>): <Spanish>`. `<why>` is one of `unknown`, `not_approved` (still pending),
   `rejected`, `wrong_tool`, `wrong_args`, `already_used`. A misused approval (wrong tool or args) is not
   burned; a used one never works again.
6. Before asking the human, the server runs the capability's precheck (claim exists, shop/slot was offered,
   deductible not already accepted, …), so a customer is never asked to approve something that would fail.

## Claim ids from agents

Agents (MCP, chat) refer to claims by `claim_id`. If an agent passes an id this customer does not
have — or omits it on a tool that needs one — the server uses the customer's **active claim** (the
newest one not yet signed; else the newest). Models do invent ids; the customer's claim is never
mixed up with someone else's because the lookup is always scoped to the token's customer. The chat
also tells the model the active claim id at the start of every turn.

## Device-bound approvals: `escudo-approval:v1`

**Register the device** (once per install, and whenever the key or FCM token changes):
`POST /devices` with the customer's Firebase ID token and

```json
{"device_id": "…", "fcm_token": "…", "platform": "android", "public_key": "<base64 DER SubjectPublicKeyInfo>"}
```

- `public_key`: EC **P-256** public key generated in the **Android Keystore** (private key never leaves it,
  use requires user authentication), encoded as standard base64 of the DER `SubjectPublicKeyInfo`
  (`KeyPair.public.encoded` on Android). Anything else → `422`.
- `device_id` is the device key. Registering an id that belongs to another customer → `403`.
  Re-registering the same `device_id` with a **different** key replaces it; the server logs it and keeps
  an audit record (`device_audit`: old and new key fingerprints, time, uid).
- `204` on success. Devices are disabled (not deleted) when FCM reports them unregistered.

**Sign the decision**: the device signs, with **ECDSA-SHA256**, the UTF-8 bytes of

```
escudo-approval:v1:{approval_id}:{args_hash}:approve      (or …:reject)
```

`approval_id` and `args_hash` come from `GET /approvals` (or the push). The signature is the standard
base64 of the **DER** ECDSA signature (what `java.security.Signature("SHA256withECDSA")` returns).

**Decide**: `POST /approvals/{approval_id}/approve` (or `/reject`) with the customer's token and

```json
{"device_id": "…", "signature": "<base64 DER ECDSA-SHA256>"}
```

The server checks, in order, and changes nothing unless all pass:

1. the approval exists and belongs to the token's `customer_id`;
2. `device_id` is registered to that customer and not disabled;
3. the signature verifies with that device's stored key over the exact string above (so a signature for
   another approval, other arguments or the other decision does not verify).

Any failure → `403 {"error": "approval_forbidden", "detail": <why>, "message": <Spanish>}`, `<why>` one of
`not_your_approval`, `no_device`, `unknown_device`, `device_disabled`, `bad_signature`. If all pass but the
approval is no longer `pending` (already decided, a replay) → `409`. A used approval is also refused when
the agent retries the action (`approval_invalid (already_used)`).

The scenario runner (`make scenario`) plays the customer's phone in test mode: it registers its own
software test device (`platform: "test"`, key generated in memory each run, no push sent to it) and signs
exactly like the app.

## Surfaces

- MCP: streamable HTTP at `/mcp`, bearer = customer Firebase ID token.
- A2A: Escudo agent at `/a2a/escudo`, Tow at `/a2a/tow`, Repair at `/a2a/repair`, each with its Agent Card at the well-known path of the current A2A version. Escudo discovers peers from `A2A_PEERS` (base URLs only). Cards are public. Calling `/a2a/escudo` requires the customer's Firebase ID token (bearer) or the partner key `X-Escudo-A2A-Key` plus `X-Escudo-Customer-Id`; Tow and Repair require the partner key when `A2A_SHARED_SECRET` is set. Every surface acts as the `customer_id` of the verified token.
- Chat: Escudo Assistant, a typed chat over a WebSocket at `/assistant/chat` (protocol below). An ADK agent on `GEMINI_MODEL` that acts only through the MCP tools (loopback to `/mcp` with the customer's own token, header `X-Escudo-Actor: escudo_assistant`).
- Timeline events from clients: `POST /events` accepts only `kind: "message"` (capability calls and
  approvals are recorded by the backend itself). The server always sets `id` and `ts`; a `claim_id` must
  belong to the caller (else `403`). Schema: `events.schema.json`.
- REST caller labels (optional headers on every REST call): `X-Escudo-Surface` (a `surface` value of
  events.schema.json, default `rest`), `X-Escudo-Actor` (an `actor` value, default `customer`; agents the
  server cannot identify — AppFunctions from adb, WebMCP, MCP clients — are `external_agent`),
  `X-Escudo-Run-Id` (groups a run; default: the customer's run, see `run_id` in events.schema.json). The backend writes the capability's
  `tool_call` / `tool_result` / `approval_requested` events with these labels, so clients do not post
  duplicates to `/events`. Unknown values fall back to the defaults. MCP clients may send
  `X-Escudo-Actor` and `X-Escudo-Run-Id` (surface is always `mcp`; actor defaults to `external_agent`).

## Approval push (FCM)

Sent to every device registered with `POST /devices` for the customer when an approval is created.

| Field | Value |
| --- | --- |
| `notification.title` | `Escudo necesita tu aprobación` |
| `notification.body` | the approval `summary` (Spanish) |
| `android.priority` / `android.notification.channel_id` | `high` / `approvals` |
| `data.type` | `approval_request` |
| `data.approval_id`, `data.tool`, `data.claim_id`, `data.reason`, `data.args_hash` | strings (`claim_id` may be empty) |

The app opens `escudo://approvals/{approval_id}`, shows the summary, and after BiometricPrompt signs and
calls `POST /approvals/{approval_id}/approve` with `{"device_id", "signature"}` (`escudo-approval:v1`). Devices answering `UNREGISTERED` to FCM
are disabled. If the customer has a chat open on that approval, the assistant resumes on its own
(`[Aviso de Escudo] La clienta aprobó <tool> … approval_id=…`) so it retries on its own.

## Chat WebSocket `/assistant/chat`

The Escudo Assistant is a typed chat. Every frame is a JSON text frame.

- **Auth**: `Authorization: Bearer <Firebase ID token>` on the upgrade request — nothing else (no query
  parameter, no auth frame). Missing or invalid → `{"type":"error","code":"unauthenticated"|"invalid_token",…}`
  and close `4401`.
- **Continue a conversation**: optional header `X-Escudo-Chat-Session: <session_id>` (from a previous
  `ready`). The transcript lives in Firestore (`chat_sessions/{id}`), so any server instance can continue
  it; a session of another customer is ignored and a new one starts.

Client → server:

| `type` | Fields | Meaning |
| --- | --- | --- |
| `text` | `text` | A user message |
| `end` | – | Close the chat |

Server → client:

| `type` | Fields | Meaning |
| --- | --- | --- |
| `ready` | `session_id`, `mock` | Authenticated; send messages |
| `text` | `text`, `final` | Assistant reply. `final:false` frames are deltas to append; the `final:true` frame carries the whole message and replaces the deltas |
| `tool_call` | `name`, `call_id` | The assistant called an MCP tool (once per call) |
| `tool_result` | `name`, `call_id` | That call answered; `call_id` matches its `tool_call` |
| `approval_required` | `approval_id`, `tool`, `summary`, `reason`, `args_hash` | A gated tool returned `pending_approval`; the push is on its way |
| `approval_resolved` | `approval_id`, `tool`, `status` (`approved`\|`rejected`) | The customer decided on her phone; the assistant continues **on its own** (no message needed) |
| `turn_complete` | – | The assistant finished a turn |
| `error` | `code`, `message` (Spanish) | The turn failed; the chat stays open unless the code is `unauthenticated`/`invalid_token` |

Resume after approval: while an approval from this chat is pending, the server watches it in Firestore;
when it is decided — on any instance, by the phone — it sends `approval_resolved` and runs the next
assistant turn, which retries the action with the `approval_id`.

`MOCK_AI=1`: same protocol with `ready.mock=true` and a deterministic assistant (no model) that uses the
same capabilities, so clients and tests run offline.
