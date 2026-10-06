#!/usr/bin/env bash
# Installs and starts Google's AppFunction Testing Agent (github.com/android/appfunctions, agent/) on
# the emulator: an external agent with the system permission to list and run other apps' AppFunctions,
# driven with taps. It only gets that permission when launched through its ShellIdentityInstrumentation,
# so this starts it that way; the emulator's quick-boot snapshot keeps that process alive, and the
# presenter opens it from recents or its icon on talk day without any command. Idempotent: does nothing
# when the privileged agent is already running.
#
# Usage: scripts/prep_appfunctions_agent.sh   (ANDROID_SERIAL defaults to the running Pixel_9_API_36_1)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB_BIN="$(command -v "${ADB:-adb}" 2>/dev/null || echo "$SDK/platform-tools/adb")"
SERIAL="${ANDROID_SERIAL:-$(ADB="$ADB_BIN" "$HERE/emulator_serial.sh" --wait 180)}"
REPO=https://github.com/android/appfunctions.git
COMMIT=5bd21cc98e47bbe841d58bdd433977af7ff3d64c # 2026-10-06, the version the talk was rehearsed with
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/escudo/appfunctions"
PKG=com.example.appfunctions.agent
APK="$CACHE/agent/app/build/outputs/apk/standard/debug/app-standard-debug.apk"

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }
ok() { printf '   %s\n' "$*"; }

# Privileged = the agent's process runs under its instrumentation with a UiAutomation connection.
privileged() { adb shell dumpsys activity processes "$PKG" 2>/dev/null | grep -q "$PKG/u0a[0-9]* (instrumentation)"; }

if privileged; then
  ok "el agente de prueba de AppFunctions ya corre con permisos"
  exit 0
fi

if [ ! -f "$APK" ]; then
  if [ ! -d "$CACHE/.git" ]; then
    git clone -q "$REPO" "$CACHE"
  fi
  git -C "$CACHE" fetch -q origin "$COMMIT" && git -C "$CACHE" checkout -q "$COMMIT"
  echo "sdk.dir=$SDK" > "$CACHE/agent/local.properties"
  (cd "$CACHE/agent" && ./gradlew --console=plain -q assembleStandardDebug)
fi
adb install -r "$APK" >/dev/null && ok "agente instalado ($PKG)"

# -w keeps the UiAutomation connection the agent uses to adopt the shell identity
# (EXECUTE_APP_FUNCTIONS); nohup, on the device, keeps it alive after adb disconnects.
adb shell am force-stop "$PKG"
adb shell "nohup am instrument -w $PKG/.ShellIdentityInstrumentation >/dev/null 2>&1 &"
for _ in $(seq 1 20); do
  privileged && break
  sleep 0.5
done
privileged || { echo "   FALLA: el agente no arrancó con permisos" >&2; exit 1; }
ok "agente corriendo con permisos de sistema (ShellIdentityInstrumentation)"
