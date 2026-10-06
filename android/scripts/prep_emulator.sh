#!/usr/bin/env bash
# Leaves the Pixel_9_API_36_1 emulator ready for the talk, with no manual steps. Idempotent: every
# step checks first and only does what is missing.
#   1. Screen PIN 1234 (needed for a fingerprint) and the screen unlocked.
#   2. android/local.properties from .env.local (written by `make seed`) and google-services.json.
#   3. Live debug build installed (Gradle skips it when nothing changed).
#   4. POST_NOTIFICATIONS and READ_MEDIA_IMAGES granted.
#   5. The three crash photos (backend/samples/photos) in the gallery as escudo_crash_1..3.jpg.
#   6. One enrolled fingerprint (Settings' enrollment screen driven over adb + `adb emu finger touch 1`).
#   7. Google's AppFunction Testing Agent installed and running with system permissions
#      (scripts/prep_appfunctions_agent.sh), for the AppFunctions part of the demo.
#   8. App opened: it signs in by itself and registers this install's FCM token and approval key
#      (POST /devices).
# Then close the emulator normally: its quick-boot snapshot keeps all of this, so on talk day opening
# the emulator is enough.
#
# Usage (from escudo/android, or `make android-prep` at the repo root, which boots the emulator first):
#   scripts/prep_emulator.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(dirname "$HERE")"
ROOT="$(dirname "$ANDROID_DIR")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB_BIN="$(command -v "${ADB:-adb}" 2>/dev/null || echo "$SDK/platform-tools/adb")" # a path, never the adb() function below
SERIAL="${ANDROID_SERIAL:-$(ADB="$ADB_BIN" "$HERE/emulator_serial.sh" --wait 180)}"
PKG=app.escudo.android
PIN=1234
API_URL=https://escudo-api-611681112050.us-central1.run.app

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }
step() { printf '\n== %s\n' "$*"; }
ok() { printf '   %s\n' "$*"; }
die() { printf '   FALLA: %s\n' "$*" >&2; exit 1; }

ui_dump() { adb shell uiautomator dump /sdcard/escudo_ui.xml >/dev/null 2>&1; adb shell cat /sdcard/escudo_ui.xml 2>/dev/null; }
center_in() { # <ui xml> <text regex> → "x y" of the first node whose text matches
  printf '%s' "$1" | grep -o -E "text=\"($2)\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 |
    sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
locked() { adb shell dumpsys trust | grep -q "deviceLocked=1"; }
enrolled() { adb shell dumpsys fingerprint | grep -o '"count":[0-9]*' | grep -q -v '"count":0'; }

echo "Emulador $SERIAL"

step "PIN y pantalla"
if adb shell locksettings verify >/dev/null 2>&1; then # succeeds only when there is no lock credential
  adb shell locksettings set-pin "$PIN" >/dev/null && ok "PIN $PIN definido"
elif adb shell locksettings verify --old "$PIN" >/dev/null 2>&1; then
  ok "PIN $PIN ya definido"
else
  die "el emulador tiene un PIN distinto de $PIN: quítalo en Ajustes > Seguridad y repite"
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell settings put system screen_off_timeout 1800000 # 30 min: the screen does not sleep mid-demo
if locked; then
  adb shell wm dismiss-keyguard
  adb shell input text "$PIN"
  adb shell input keyevent KEYCODE_ENTER
  for _ in $(seq 1 20); do locked || break; sleep 0.5; done
fi
locked && die "la pantalla sigue bloqueada"
ok "desbloqueado"

step "local.properties y google-services.json"
ENV_LOCAL="$ROOT/.env.local"
if [ ! -f "$ANDROID_DIR/local.properties" ] || ! grep -q '^escudo.customerPassword=' "$ANDROID_DIR/local.properties"; then
  [ -f "$ENV_LOCAL" ] || die "falta $ENV_LOCAL: corre make seed"
  email="$(sed -n 's/^ESCUDO_CUSTOMER_EMAIL=//p' "$ENV_LOCAL")"
  password="$(sed -n 's/^ESCUDO_CUSTOMER_PASSWORD=//p' "$ENV_LOCAL")"
  [ -n "$email" ] && [ -n "$password" ] || die "$ENV_LOCAL no tiene ESCUDO_CUSTOMER_EMAIL/PASSWORD: corre make seed"
  umask 077
  printf 'sdk.dir=%s\nescudo.apiUrl=%s\nescudo.customerEmail=%s\nescudo.customerPassword=%s\n' \
    "$SDK" "$API_URL" "$email" "$password" > "$ANDROID_DIR/local.properties"
  ok "local.properties escrito desde .env.local"
else
  ok "local.properties ya existe"
fi
if [ ! -f "$ANDROID_DIR/app/google-services.json" ]; then
  make -s -C "$ANDROID_DIR" google-services && ok "google-services.json descargado"
else
  ok "google-services.json ya existe"
fi

step "App live"
(cd "$ANDROID_DIR" && ANDROID_SERIAL="$SERIAL" ./gradlew --console=plain -q installLiveDebug) && ok "instalada ($PKG)"

step "Permisos"
for perm in POST_NOTIFICATIONS READ_MEDIA_IMAGES; do
  adb shell pm grant "$PKG" "android.permission.$perm" && ok "$perm"
done

step "Fotos del choque en la galería"
names="$(adb shell content query --uri content://media/external/images/media --projection _display_name 2>/dev/null || true)"
pushed=0
for i in 1 2 3; do
  if printf '%s' "$names" | grep -q "_display_name=escudo_crash_$i.jpg"; then
    ok "escudo_crash_$i.jpg ya está"
  else
    adb push "$ROOT/backend/samples/photos/crash_$i.jpg" "/sdcard/Pictures/escudo_crash_$i.jpg" >/dev/null
    pushed=1
    ok "escudo_crash_$i.jpg copiada"
  fi
done
if [ "$pushed" = 1 ]; then
  adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null
  ok "galería actualizada"
fi

step "Huella"
if enrolled; then
  ok "ya hay una huella registrada"
else
  adb shell am force-stop com.android.settings # an enrollment left half done would come back mid-flow
  adb shell am start -W -a android.settings.FINGERPRINT_ENROLL >/dev/null
  deadline=$(( $(date +%s) + 120 ))
  until enrolled; do
    [ "$(date +%s)" -ge "$deadline" ] && die "no se pudo registrar la huella en 120 s"
    ui="$(ui_dump)"
    if printf '%s' "$ui" | grep -q 'resource-id="com.android.settings:id/password_entry"'; then
      adb shell input text "$PIN"; adb shell input keyevent KEYCODE_ENTER
    elif c="$(center_in "$ui" "I AGREE|I agree|Agree|AGREE|Start|START|Next|NEXT")" && [ -n "$c" ]; then
      adb shell input tap $c
    elif c="$(center_in "$ui" "MORE|More")" && [ -n "$c" ]; then
      adb shell input tap $c
    else
      adb emu finger touch 1 >/dev/null
      sleep 0.4
      adb emu finger remove 1 >/dev/null
    fi
    sleep 0.6
  done
  ok "huella registrada"
  c="$(center_in "$(ui_dump)" "Done|DONE")" && [ -n "$c" ] && adb shell input tap $c
  adb shell input keyevent KEYCODE_HOME
fi

step "Agente de prueba de AppFunctions"
ANDROID_SERIAL="$SERIAL" ADB="$ADB_BIN" "$HERE/prep_appfunctions_agent.sh"

step "Sesión y registro del dispositivo"
adb logcat -c
adb shell am force-stop "$PKG" # a fresh process: the Application registers the token on start
adb shell am start -n "$PKG/.MainActivity" >/dev/null
deadline=$(( $(date +%s) + 45 ))
until adb logcat -d -s "DeviceRegistration:I" | grep -q "Device registered for approval pushes"; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    adb logcat -d -s "DeviceRegistration:*" | tail -3 >&2
    die "la app no registró su token en 45 s (¿red? ¿make seed?)"
  fi
  sleep 1
done
ok "token FCM y llave de aprobación registrados (POST /devices)"

printf '\nEmulador listo. Ciérralo normalmente para que su snapshot guarde este estado.\n'
