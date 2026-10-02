# Escudo: credits and provenance

> [README](../README.en.md) · [Español](CREDITOS.md)

## Credits and provenance

- **Rehearsal crash photos** (`backend/samples/photos/crash_{1,2,3}.jpg`): synthetic, generated with
  `gemini-3.1-flash-lite-image` by `backend/scripts/generate_sample_photos.py` (`make photos`, seed
  2027). Each image's model, prompt and seed are in `backend/samples/photos/prompts.json`.
- **Seed data** (customer Andrea Morales, policy `POL-GT-2027-0042`, shops, tow providers): fictional,
  written by `backend/escudo/seed.py`; every record carries `origin` (`synthetic`, seed 2027).
- **Visual identity** "A · Sereno": tokens in `android/design/a-sereno/tokens.json` and
  `web/src/brand/tokens.json`, vector mark `android/design/a-sereno/mark.svg`; synthetic logo and
  mockups (`IMAGE_MODEL`) with their prompts and generator in `brand/` (`make brand`).
- **Typeface**: Onest (SIL OFL 1.1, `android/design/a-sereno/Onest-OFL.txt`; on the web, self-hosted in `web/src/app/fonts/` with its license).
- **Diagrams** (`docs/diagrams/`): made with the `diagram-design` skill (light editorial skin, pine accent `#0F5C4D`).

All companies, people, plates and addresses are fictional. Full inventory of data and generated assets
in [`data/SOURCES.md`](../data/SOURCES.md); datasheet in [`data/DATASHEET.md`](../data/DATASHEET.md).
