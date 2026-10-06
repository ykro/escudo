# Escudo AppFunctions

Escudo exposes three capabilities to on-device agents through Jetpack AppFunctions
(`androidx.appfunctions:appfunctions:1.0.0-alpha12`, KSP compiler of the same version).
They live in `app/src/main/kotlin/app/escudo/android/appfunctions/EscudoAppFunctions.kt`
and call the same `EscudoRepository` as the screens.

| Function id | Capabilities (tools.md) | Parameters |
| --- | --- | --- |
| `app.escudo.android.appfunctions.BaseEscudoAppFunctionService#reportAccident` | `open_claim` + `attach_claim_photos` | `description` (required), `photoUris` (list of `{"uri": "content://…"}`, max 5), `recentPhotoCount` (1–5, newest gallery photos), `locationLabel`, `occurredAt` (ISO-8601) |
| `app.escudo.android.appfunctions.BaseEscudoAppFunctionService#getCoverage` | `get_policy` + `check_coverage` | `incidentType` (optional) |
| `app.escudo.android.appfunctions.BaseEscudoAppFunctionService#getClaimStatus` | `get_claim_status` | `claimId` (optional, latest if omitted) |

Nothing here can accept the deductible, book a shop, sign or approve: those stay behind the
human approval in the app. Every API call made by an AppFunction carries
`X-Escudo-Surface: appfunctions` and `X-Escudo-Actor: external_agent`, so the presenter shows the
call as "Agente externo" with the AppFunctions badge.

## Requirements

- Device or emulator on **Android 16 QPR2 (API 36.1) or newer**. The AppFunctions service exists
  on API 36.0, but the `cmd app_function` shell command does not (`No shell command implementation.`).
  Verified on AVD `Pixel_9_API_36_1` (`system-images;android-36.1;google_apis_playstore;arm64-v8a`, r4).
- For `recentPhotoCount`, gallery access (`READ_MEDIA_IMAGES`), granted by `make android-prep`.

Create the emulator once:

```bash
sdkmanager "system-images;android-36.1;google_apis_playstore;arm64-v8a"
avdmanager create avd -n Pixel_9_API_36_1 -k "system-images;android-36.1;google_apis_playstore;arm64-v8a" -d pixel_9
emulator -avd Pixel_9_API_36_1
```

## List (discoverability)

```bash
adb shell cmd app_function help
adb shell cmd app_function list-app-functions | grep -o '"app.escudo.android.appfunctions[^"]*"' | sort -u
# or: make -C android appfunctions
```

Result on the emulator (2026-09-30): the three function ids above, their KDoc descriptions and
the `CoverageResult`, `ClaimStatusResult` and `ReportAccidentResult` schemas.

## Execute

```bash
F=app.escudo.android.appfunctions.BaseEscudoAppFunctionService

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#getCoverage' --parameters '{\"incidentType\": \"collision\"}' --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#reportAccident' \
  --parameters '{\"description\": \"Choque menor: me chocaron por detrás mientras esperaba en el semáforo. Nadie salió herido.\", \"recentPhotoCount\": 1, \"locationLabel\": \"Calzada Roosevelt, zona 11\"}' \
  --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#reportAccident' \
  --parameters '{\"description\": \"Golpe en el parachoques\", \"photoUris\": [{\"uri\": \"content://media/external/images/media/18\"}]}' \
  --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#getClaimStatus' --parameters '{}' --brief-yaml"
```

Note: a `Uri` parameter is an object `{"uri": "…"}`, not a bare string.
Default timeout is 30 s (`--timeout-duration <s>`); `reportAccident` runs the damage assessor.

Verified against the deployed `escudo-api` (Cloud Run, real Gemini assessor) on 2026-09-30:
`getCoverage` 2.4 s, `getClaimStatus` 0.3 s, `reportAccident` with one photo URI 5.5 s
(→ `CLM-YXCPM7`, severity moderate, 350–650 USD).

Earlier results (live flavor against the backend in `MOCK_AI=1 MOCK_AUTH=1` mode, and fake flavor):

- `getCoverage` → policy `POL-GT-2027-0042`, `covered: true` for `collision`, deductible 500.
- `reportAccident` → `claimId: CLM-…`, `status: assessed`, `severity: minor`, damaged parts, cost 650–1100.
- `getClaimStatus` → the same claim with `statusLabel: Daños evaluados`; the claim also appears in the app.
- Errors are typed: empty description or no gallery permission → `AppFunctionInvalidArgumentException`
  (code 1001); no claims → `AppFunctionElementNotFoundException` (code 1500).

## Enable / disable (e.g. to show gating live)

```bash
adb shell cmd app_function set-enabled --package app.escudo.android --function "$F#reportAccident" --state disable
adb shell cmd app_function set-enabled --package app.escudo.android --function "$F#reportAccident" --state default
```

## On stage: Google's AppFunction Testing Agent

The talk shows the functions with Google's **AppFunction Testing Agent** (`github.com/android/appfunctions`,
folder `agent/`, pinned commit in `scripts/prep_appfunctions_agent.sh`): an external app that lists every
app's AppFunctions and runs them with inputs you type, all with taps. It needs the system permission
`EXECUTE_APP_FUNCTIONS`, which it only holds when started through its `ShellIdentityInstrumentation`.
`make prep` (or `make android-prep` at the repo root) installs it and starts it that way; the emulator's
quick-boot snapshot keeps that process alive, so on talk day it is opened from recents or its icon:
search → **Escudo** (pinned) → **getCoverage** with `incidentType` = `collision` → **Run**. Verified on
2026-10-06 against the deployed `escudo-api`: the result shows the policy, vehicle, coverages and
deductible, and the presenter shows the call as "Agente externo · AppFunctions".

After a cold boot (no snapshot) the agent loses the permission; `make android-prep` starts it again.

**Gemini**: as of 2026-10-06, Gemini's integration with third-party AppFunctions is in private preview
(trusted testers / Early Access Program) and is not available to this app.
