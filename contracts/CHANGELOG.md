# Contracts: current state

What the contracts in this folder say today, in one page, for anyone implementing a client. Details
live in `openapi.yaml` (REST), `tools.md` (capabilities, approvals, push, voice) and
`events.schema.json` (timeline). Last change: 2026-10-01, `escudo-approval:v1`.

## Identity
- Every REST, MCP, voice and A2A call carries the customer's **Firebase ID token**; the backend acts as
  the token's `customer_id` custom claim (set on the seeded login by `make seed`). A verified user
  without the claim is their own customer and sees no policy or claims.
- `MOCK_AUTH=1` (local tests only) maps every caller to `DEMO_CUSTOMER_ID`.

## Approvals (`escudo-approval:v1`)
- Gated capabilities return `pending_approval` with `approval_id` and `args_hash`; nothing changes.
- Only a **registered device** can decide: `POST /devices {device_id, fcm_token, platform, public_key}`
  (EC P-256 SPKI from the Android Keystore), then `POST /approvals/{id}/approve|reject
  {device_id, signature}` where `signature` is ECDSA-SHA256 (DER, base64) over
  `escudo-approval:v1:{approval_id}:{args_hash}:approve|reject`.
- Failures → `403 approval_forbidden` (`not_your_approval`, `no_device`, `unknown_device`,
  `device_disabled`, `bad_signature`), no state change; not pending → `409`.
- An approval is single use and bound to tool + arguments; retrying the action with a wrong, pending,
  rejected or used approval → `403 approval_invalid` (REST) / tool error `approval_invalid (<detail>)` (MCP).
- Approving is never a tool, AppFunction or WebMCP function.
- `GET /approvals` lists the caller's pending approvals including `args_hash`; the FCM push carries
  `data.type=approval_request`, `approval_id`, `tool`, `claim_id`, `reason`, `args_hash`.

## Surfaces
- REST: `servers` in `openapi.yaml` (`https://escudo-api-611681112050.us-central1.run.app`). Optional
  headers `X-Escudo-Surface`, `X-Escudo-Actor`, `X-Escudo-Run-Id` label the timeline events the backend
  writes for each capability call.
- MCP: streamable HTTP at `/mcp`, bearer token.
- A2A: Agent Cards are public at `{base}/.well-known/agent-card.json`. `/a2a/escudo` requires the
  customer's token or the partner key (`X-Escudo-A2A-Key`) plus `X-Escudo-Customer-Id`; `/a2a/tow` and
  `/a2a/repair` require the partner key. Cards declare these schemes.
- Voice: `/assistant/live`; token in the `Authorization` header or as the first frame
  `{"type":"auth","token":…}` — never in the URL. Audio PCM16 16 kHz up, 24 kHz down.
- Timeline: clients may `POST /events` with `kind: "message"` only; the server sets `id` and `ts` and
  rejects `claim_id`s the caller does not own.
- Admin: `POST /admin/reset` with `X-Admin-Token`.
