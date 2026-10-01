# Contracts changelog

Every change here is backward compatible: fields, headers and messages are added, nothing is
removed or renamed.

## 2026-10-01 — identity per customer, authenticated Escudo agent

- Every surface acts as the `customer_id` of the verified Firebase token (custom claim set by
  `make seed`); a user without the claim is their own customer and sees no policy or claims. Only
  `MOCK_AUTH=1` maps callers to `DEMO_CUSTOMER_ID`. Anonymous sign-in is no longer used anywhere
  (portal demo button and scenario fallback removed).
- `/a2a/escudo` RPC requires `Authorization: Bearer <customer Firebase ID token>`, or the partner key
  (`X-Escudo-A2A-Key`) plus `X-Escudo-Customer-Id`; otherwise `401`. Its Agent Card stays public and
  declares `securitySchemes.customerToken` (HTTP bearer) and, when configured, `partnerKey`.
- `attach_claim_photos` also returns `photos_assessed` and `assessment_seconds`; photos are downscaled
  to 1024 px before the model (target < 15 s for 3 photos, measured ~5 s).

## 2026-09-30 — web, presenter and partner secret

- Timeline: read-only capabilities (`get_policy`, `check_coverage`, `get_claim_status`) called with actor
  `customer` (screens refreshing themselves) no longer write events; agents' reads still do.
- `a2a_request` summaries name the partner company from its Agent Card (`Pide una grúa a Grúas Aliadas…`);
  `data.partner` added.
- Optional `A2A_SHARED_SECRET`: Tow/Repair RPC require `X-Escudo-A2A-Key`; their Agent Cards declare it as
  `securitySchemes.partnerKey` (apiKey in header). Cards stay public. Enabled on Cloud Run.
- The web registers the WebMCP tools `getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`
  (as in `tools.md`), calling REST with `X-Escudo-Surface: webmcp`, `X-Escudo-Actor: gemini`.

## 2026-09-30 — backend 1.0 deployed; wire details written down (accepted)

Proposed by Android, accepted and implemented by the backend. These write down what both sides
already speak.

1. **Voice WebSocket `/assistant/live`** — documented in `tools.md` → *Voice WebSocket*: auth (header or
   `?token=`, `error` + close 4401), PCM16 LE mono 16 kHz up / 24 kHz down, client frames `text`, `end`,
   `activity_start`/`activity_end` (accepted, ignored: automatic VAD), server frames `ready`, `transcript`
   (`final:true` replaces the deltas), `tool_call`, `tool_result`, `approval_required`, `interrupted`,
   `turn_complete`, `error`.
2. **Approval push (FCM)** — documented in `tools.md` → *Approval push*: notification title/body, Android
   channel `approvals`, data `{type: approval_request, approval_id, tool, claim_id, reason}`.
3. **Caller labels** — optional headers `X-Escudo-Surface`, `X-Escudo-Actor`, `X-Escudo-Run-Id` on every
   REST operation (`openapi.yaml` → `components/parameters`). The backend writes `tool_call`,
   `tool_result`, `approval_requested` and `error` events with those labels (tested in
   `backend/tests/test_events.py::test_caller_headers_label_the_timeline`); unknown values fall back to
   `rest` / `customer`. Clients do not post duplicate capability events.
4. **`POST /devices`** accepts optional `device_id` (used as the device key). **`POST /events`** fills `id`
   and `ts` when missing and answers `422` when the event does not match the schema.

Added by the backend:

5. `servers` in `openapi.yaml`: `https://escudo-api-611681112050.us-central1.run.app`.
6. Invalid approvals answer `403 {"error":"approval_invalid","detail":…,"message":…}` with `detail` in
   `unknown | not_approved | rejected | wrong_tool | wrong_args | already_used`; MCP returns a tool error
   `approval_invalid (<detail>): …`. `POST /claims/{id}/rental` also accepts `{approval_id}` and may answer 403.
7. Additive response fields: `DamageAssessment.summary`; `PendingApproval.summary`/`message`;
   `Approval.claim_id`; `Claim.policy_id`, `repair_options`, `status_label`, `timeline` (GET), `tow.provider_id`,
   `tow.phone`, `repair.address`, `rental.covered`, `rental.cost_usd`.
8. `POST /admin/reset` takes the token in `X-Admin-Token` (or as bearer) and returns `X-Reset-Seconds`.
   Devices survive a reset.
9. Firebase login of the seeded customer (`make seed`): email + password, custom claim `customer_id`.
   Any other verified Firebase user (e.g. anonymous) acts as the demo customer.
10. `GET /health` for probes (`/healthz` is reserved by Cloud Run's front end).
