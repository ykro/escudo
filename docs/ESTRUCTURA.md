# Escudo: estructura del repo

> [README](../README.md) · [English](ESTRUCTURA.en.md)

## Mapa del repo

```
AGENTS.md                 guía para agentes de código (especificación y reglas)
Makefile                  comandos de la tabla anterior
.env.example              variables (modelos, datos, superficies, modos); copia a .env
contracts/                fuente de verdad compartida
  openapi.yaml            API REST (Android y web)
  tools.md                las 11 capacidades, autonomía, superficies, protocolo de aprobación, chat y push
  events.schema.json      esquema de los eventos de la línea de tiempo
  CHANGELOG.md            estado actual de los contratos en una página
backend/                  Python 3.13 + uv
  escudo/capabilities/    el dominio: una función por capacidad
  escudo/autonomy.py      qué puede hacer un agente solo
  escudo/approvals.py     aprobaciones de un solo uso
  escudo/gateway.py       la única puerta al dominio
  escudo/adapters/        REST, MCP y chat del asistente
  escudo/agents/          agente Escudo + socios Grúa y Talleres por A2A
  escudo/damage_assessor.py  perito de daños (Gemini, salida estructurada)
  escudo/scenario.py      corredor del escenario por MCP (un cliente propio por corrida)
  escudo/chat_scenario.py corredor del escenario por chat
  escudo/seed.py, admin.py   datos sintéticos, make seed, reinicio de la demo
  samples/photos/         3 fotos de ensayo + prompts.json
  scripts/                generate_sample_photos.py
  tests/                  pytest (aprobaciones, firmas de dispositivo, autonomía, A2A, chat, corridas, eventos)
android/                  Kotlin + Compose + Material 3 (ver android/README.md)
  app/src/main/…/appfunctions/   AppFunctions
  app/src/main/…/approvals/      ApprovalFlow + BiometricPrompt
  app/src/main/…/assistant/      chat con el asistente (ChatSession, ChatProtocol)
  scripts/chat_scenario.sh       escenario por chat en el emulador
  scripts/prep_emulator.sh       make android-prep: deja el emulador listo para la charla
  scripts/prep_appfunctions_agent.sh  agente de prueba de AppFunctions de Google, con su permiso de sistema
.github/workflows/test.yml  CI: make test en cada push y PR, solo modos simulados
web/                      Next.js 16 (ver web/README.md)
  src/lib/webmcp.ts       herramientas WebMCP
  src/app/presentador/      vista del presentador (1920×1080)
  src/app/api/presenter/  stream SSE de Firestore y wake (despierta el API al abrir la vista)
infra/                    scripts numerados e idempotentes: 01-07 crean el proyecto desde cero, 10-11 despliegan
brand/                    prompts y generador del logo y las maquetas «A · Sereno» (make brand)
data/                     SOURCES.md (procedencia de cada dato y recurso) y DATASHEET.md
docs/                     ONBOARDING.md, DEMO.md + DEMO.en.md (guion, capturas en docs/demo/), ARQUITECTURA, COMANDOS,
                          ESTRUCTURA, DECISIONES, CREDITOS (cada uno con su .en.md), diagramas
                          (incluido demo-coverage: qué se prueba), capturas y escudo-chat-run.mp4 (respaldo)
```
