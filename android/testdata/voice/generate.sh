#!/usr/bin/env bash
# Spoken Spanish fixtures for the Escudo Assistant (voice tests on the emulator).
# Synthetic: macOS `say -v Paulina` (es_MX) -> ffmpeg -> WAV PCM16 LE, 16 kHz, mono (the Live input format).
# Re-run to regenerate; texts are the single source of truth (see SOURCES.md).
set -euo pipefail
cd "$(dirname "$0")"
VOICE="${VOICE:-Paulina}"

make_fixture() {
  local name="$1" text="$2" tmp
  tmp="$(mktemp -t escudo-voice).aiff"
  say -v "$VOICE" -r 175 -o "$tmp" "$text"
  # 250 ms of leading silence so the first syllable is never clipped by activity detection.
  ffmpeg -loglevel error -y -i "$tmp" -af "adelay=250" -ac 1 -ar 16000 -c:a pcm_s16le "$name.wav"
  rm -f "$tmp"
  echo "$name.wav  $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$name.wav")s  \"$text\""
}

make_fixture 01_choque     "Tuve un choque menor, me pegaron atrás en un semáforo. Ya subí las fotos en la app. Ayúdame con todo, por favor."
make_fixture 02_taller     "Sí, agenda el taller más cercano con el primer horario disponible."
make_fixture 03_deducible  "Sí, acepta el deducible y firma el reclamo."
