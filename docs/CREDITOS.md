# Escudo: créditos y procedencia

> [README](../README.md) · [English](CREDITOS.en.md)

## Créditos y procedencia

- **Fotos de ensayo del choque** (`backend/samples/photos/crash_{1,2,3}.jpg`): sintéticas, generadas con
  `gemini-3.1-flash-lite-image` por `backend/scripts/generate_sample_photos.py` (`make photos`, semilla
  2027). El modelo, el prompt y la semilla de cada imagen están en `backend/samples/photos/prompts.json`.
- **Datos semilla** (clienta Andrea Morales, póliza `POL-GT-2027-0042`, talleres, grúas): ficticios,
  escritos por `backend/escudo/seed.py`; cada registro lleva `origin` (`synthetic`, semilla 2027).
- **Identidad visual** «A · Sereno»: tokens en `android/design/a-sereno/tokens.json` y
  `web/src/brand/tokens.json`, marca vectorial `android/design/a-sereno/mark.svg`; logo y maquetas
  sintéticos (`IMAGE_MODEL`) con sus prompts y generador en `brand/` (`make brand`).
- **Tipografía**: Onest (SIL OFL 1.1, `android/design/a-sereno/Onest-OFL.txt`; en la web, autoalojada en `web/src/app/fonts/` con su licencia).
- **Diagramas** (`docs/diagrams/`): hechos con la skill `diagram-design` (piel editorial clara, acento pino `#0F5C4D`).

Todas las empresas, personas, placas y direcciones son ficticias. Inventario completo de datos y
recursos generados en [`data/SOURCES.md`](../data/SOURCES.md); hoja de datos en [`data/DATASHEET.md`](../data/DATASHEET.md).
