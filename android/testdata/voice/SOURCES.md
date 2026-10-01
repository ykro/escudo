# Voice fixtures

Spoken Spanish the Escudo Assistant "hears" in emulator tests and rehearsals, through the same
pipeline as the microphone (`FileSource` → WebSocket → Gemini Live → MCP tools → voice reply).

| File | Text | Origin |
| --- | --- | --- |
| `01_choque.wav` | Tuve un choque menor, me pegaron atrás en un semáforo. Ya subí las fotos en la app. Ayúdame con todo, por favor. | synthetic |
| `02_taller.wav` | Sí, agenda el taller más cercano con el primer horario disponible. | synthetic |
| `03_deducible.wav` | Sí, acepta el deducible y firma el reclamo. | synthetic |
| `04_acepto.wav` | Sí, acepto el deducible. | synthetic |
| `05_firma.wav` | Firma el reclamo, por favor. | synthetic |

- origin: `synthetic` — macOS `say -v Paulina -r 175` (es_MX), converted with ffmpeg to WAV PCM16 LE,
  16 kHz, mono, with 250 ms leading silence. Generator: `generate.sh` (deterministic for a given
  macOS voice version; no seed).
- Format matches the Live API input exactly, so no resampling happens on the phone.
- Bundled into **debug** builds only as assets (`voice/*.wav`); release builds do not contain them.
