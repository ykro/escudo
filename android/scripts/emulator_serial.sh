#!/usr/bin/env bash
# Prints the adb serial (emulator-55xx) of the running emulator for an AVD, so every target
# talks to the same device whatever port it booted on.
#   scripts/emulator_serial.sh [AVD]      (default: $ANDROID_AVD or Pixel_9_API_36_1)
#   --wait N   wait up to N seconds for it to appear and finish booting
set -euo pipefail

WAIT=0
if [ "${1:-}" = "--wait" ]; then WAIT="$2"; shift 2; fi
AVD="${1:-${ANDROID_AVD:-Pixel_9_API_36_1}}"
ADB_BIN="${ADB:-$(command -v adb 2>/dev/null || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"

find_serial() {
  local serial name
  for serial in $("$ADB_BIN" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1}'); do
    name="$("$ADB_BIN" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    if [ "$name" = "$AVD" ]; then echo "$serial"; return 0; fi
  done
  return 1
}

deadline=$(( $(date +%s) + WAIT ))
while true; do
  if serial="$(find_serial)"; then
    if [ "$WAIT" = 0 ] || [ "$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "$serial"
      exit 0
    fi
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "No running emulator for AVD $AVD. Start it with: make android-emulator" >&2
    exit 1
  fi
  sleep 2
done
