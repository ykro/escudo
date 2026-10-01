#!/usr/bin/env bash
# Escudo voice scenario on the emulator against the deployed backend, event-driven:
# report the accident with 3 gallery photos, then speak (recorded WAVs) to the Escudo Assistant
# and approve every pause with the (virtual) fingerprint until the claim is signed.
#
# It never sleeps for a fixed time: it waits on the app's `EscudoScenario` logcat markers
# (voice_end, assistant_idle, approval_required, approval_outcome, report_done),
# so the measured time is the real system time.
#
# Prerequisites (once): live debug build installed and signed in; 3 crash photos in the gallery
# (backend/samples/photos); device PIN + one enrolled fingerprint (see README); backend reset
# (`make reset CLOUD=1` at the repo root).
#
# Usage: scripts/voice_scenario.sh            (ANDROID_SERIAL defaults to emulator-5556)
set -euo pipefail

ADB_BIN="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
SERIAL="${ANDROID_SERIAL:-emulator-5556}"
PKG=app.escudo.android
FIXTURES=(01_choque 02_taller 04_acepto 05_firma)
APPROVALS_NEEDED=3
TURN_TIMEOUT=90

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }
T0=$(now)
say() { python3 -c "import sys; print(f'{float(sys.argv[1]) - float(sys.argv[2]):6.1f}s  {sys.argv[3]}')" "$(now)" "$T0" "$*"; }
die() { say "FAILED: $*"; exit 1; }

LOG="$(mktemp -t escudo-scenario)"
adb logcat -c
adb logcat -v brief -s "EscudoScenario:I" > "$LOG" &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true' EXIT

count() { grep -c -E "EscudoScenario[^:]*: ($1)" "$LOG" || true; }

# wait_for <regex> <count-before> <timeout-seconds>
wait_for() {
  local pattern="$1" before="$2" timeout="$3" start
  start=$(date +%s)
  while [ "$(count "$pattern")" -le "$before" ]; do
    [ $(( $(date +%s) - start )) -ge "$timeout" ] && return 1
    sleep 0.2
  done
}

# Other agents may share the emulator: bring Escudo back before every step.
front() {
  adb shell dumpsys window | grep -q "mCurrentFocus.*$PKG" || adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
}

ui_dump() { adb shell uiautomator dump /sdcard/escudo_ui.xml >/dev/null 2>&1; adb shell cat /sdcard/escudo_ui.xml; }
center() { # <attribute regex> → "x y" of the first matching node
  ui_dump | grep -o -E "$1[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 |
    sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
tap() { # <attribute regex> [timeout]
  local c="" start; start=$(date +%s)
  while [ -z "$c" ]; do
    c=$(center "$1" || true) # the UI dump fails transiently while screens change
    [ -z "$c" ] && { [ $(( $(date +%s) - start )) -ge "${2:-15}" ] && die "not on screen: $1"; front; sleep 0.3; }
  done
  adb shell input tap $c
}

hide_keyboard() { # Back only while the IME is up; otherwise Back would leave the screen
  local i
  for i in $(seq 1 10); do
    adb shell dumpsys input_method | grep -q "mInputShown=true" && { adb shell input keyevent 4; return; }
    sleep 0.2
  done
}

report_accident() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  local start; start=$(date +%s)
  until (ui_dump || true) | grep -q 'text="Póliza activa"'; do
    [ $(( $(date +%s) - start )) -ge 30 ] && die "policy did not load"; sleep 0.3
  done
  tap 'text="Reportar accidente"' 30
  start=$(date +%s)
  until (ui_dump || true) | grep -q 'class="android.widget.EditText"'; do
    [ $(( $(date +%s) - start )) -ge 10 ] && die "report screen did not open"; sleep 0.3
  done
  tap 'class="android.widget.EditText"'
  adb shell input text "Choque%smenor:%sme%spegaron%satras%sen%sun%ssemaforo."
  hide_keyboard
  tap 'text="Elegir fotos de la galería"'
  # Thumbnails in the system photo picker, newest first; wait until the layout settles.
  start=$(date +%s); local photos="" previous="-"
  until [ -n "$photos" ] && [ "$photos" = "$previous" ]; do
    previous="$photos"
    photos=$( (ui_dump || true) | grep -o -E 'content-desc="Photo taken on[^"]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -3 |
      sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' || true)
    [ $(( $(date +%s) - start )) -ge 20 ] && die "photo picker empty"
  done
  while read -r x y; do adb shell input tap "$x" "$y" </dev/null; sleep 0.5; done <<< "$photos" # adb would eat stdin
  start=$(date +%s)
  until (ui_dump || true) | grep -q 'text="3"'; do
    [ $(( $(date +%s) - start )) -ge 10 ] && die "could not select 3 photos"; sleep 0.3
  done
  tap 'text="Done"'
  local before; before=$(count "report_done")
  front
  tap 'text="Enviar reporte"'
  say "report sent, waiting for the damage assessment"
  wait_for "report_done" "$before" 120 || die "report did not finish"
  say "claim $(grep -o 'report_done [A-Z0-9-]*' "$LOG" | tail -1 | cut -d' ' -f2) opened and assessed (3 photos)"
}

speak() { # <fixture> : play it and wait until the assistant is done answering
  local idle voice
  idle=$(count "assistant_idle"); voice=$(count "voice_end")
  front
  adb shell am start -n "$PKG/.MainActivity" --es voice_input_file "$1" >/dev/null 2>&1
  wait_for "voice_end" "$voice" 60 || die "fixture $1 did not play"
  say "spoke $1"
  wait_for "assistant_idle" "$idle" "$TURN_TIMEOUT" || die "no answer to $1"
  say "assistant answered (tools so far: $(grep -o 'tool_call [a-z_]*' "$LOG" | cut -d' ' -f2 | paste -sd, -))"
}

approved=0
approve_pending() { # approve every pause the agent has asked for, with the fingerprint
  while [ "$(count "approval_required")" -gt "$approved" ] && [ "$approved" -lt "$APPROVALS_NEEDED" ]; do
    local outcomes idle
    outcomes=$(count "approval_outcome Approved"); idle=$(count "assistant_idle")
    front
    adb shell am start -a android.intent.action.VIEW -d "escudo://approvals" "$PKG" >/dev/null 2>&1
    tap 'text="Aprobar con huella"' 20
    local start; start=$(date +%s)
    until adb shell dumpsys window | grep -q "mCurrentFocus.*BiometricPrompt"; do
      [ $(( $(date +%s) - start )) -ge 10 ] && die "no biometric prompt"; sleep 0.2
    done
    # The sensor starts listening a moment after the prompt shows: touch until it is accepted.
    local tries=0
    until wait_for "approval_outcome Approved" "$outcomes" 2; do
      tries=$((tries + 1)); [ "$tries" -gt 8 ] && die "approval not granted"
      adb emu finger touch 1 >/dev/null; sleep 0.3; adb emu finger remove 1 >/dev/null 2>&1 || true
    done
    approved=$((approved + 1))
    say "approval $approved granted with fingerprint ($(grep -o 'approval_outcome Approved [a-z_]*' "$LOG" | tail -1 | cut -d' ' -f3))"
    # The backend tells the running assistant; it retries the action and answers by itself.
    wait_for "assistant_idle" "$idle" 45 || say "assistant stayed quiet after the approval"
    say "assistant resumed"
  done
}

say "start (serial $SERIAL)"
report_accident
for fixture in "${FIXTURES[@]}"; do
  [ "$approved" -ge "$APPROVALS_NEEDED" ] && break
  speak "$fixture"
  approve_pending
done
[ "$approved" -eq "$APPROVALS_NEEDED" ] || die "only $approved approvals"

front
tap 'text="Reclamo"'
start=$(date +%s)
until ui_dump | grep -q 'text="Reclamo firmado y enviado"'; do
  [ $(( $(date +%s) - start )) -ge 20 ] && die "claim not signed"; sleep 0.5
done
say "DONE: claim signed with $approved approvals"
