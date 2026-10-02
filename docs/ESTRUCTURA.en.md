# Escudo: repo structure

> [README](../README.en.md) · [Español](ESTRUCTURA.md)

## Repo map

```
AGENTS.md                 guide for coding agents (spec and rules)
Makefile                  the commands above
.env.example              variables (models, data, surfaces, modes); copy to .env
contracts/                shared source of truth
  openapi.yaml            REST API (Android and web)
  tools.md                the 11 capabilities, autonomy, surfaces, approval protocol, chat and push
  events.schema.json      schema of the timeline events
  CHANGELOG.md            current state of the contracts on one page
backend/                  Python 3.13 + uv
  escudo/capabilities/    the domain: one function per capability
  escudo/autonomy.py      what an agent may do alone
  escudo/approvals.py     single-use approvals
  escudo/gateway.py       the one door into the domain
  escudo/adapters/        REST, MCP and the assistant chat
  escudo/agents/          Escudo agent + Tow and Repair partners over A2A
  escudo/damage_assessor.py  damage assessor (Gemini, structured output)
  escudo/scenario.py      scenario runner over MCP (its own customer per run)
  escudo/chat_scenario.py scenario runner over the chat
  escudo/seed.py, admin.py   synthetic data, make seed / make reset
  samples/photos/         3 rehearsal photos + prompts.json
  scripts/                generate_sample_photos.py
  tests/                  pytest (approvals, device signatures, autonomy, A2A, chat, runs, events)
android/                  Kotlin + Compose + Material 3 (see android/README.md)
  app/src/main/…/appfunctions/   AppFunctions
  app/src/main/…/approvals/      ApprovalFlow + BiometricPrompt
  app/src/main/…/assistant/      chat with the assistant (ChatSession, ChatProtocol)
  scripts/chat_scenario.sh       chat scenario on the emulator
.github/workflows/test.yml  CI: make test on every push and PR, mock modes only
web/                      Next.js 16 (see web/README.md)
  src/lib/webmcp.ts       WebMCP tools
  src/app/presenter/      presenter view (1920×1080)
  scripts/serve-offline.sh  make up-offline: production build with no network
infra/                    numbered, idempotent scripts: 01-06 build the project from scratch, 10-11 deploy
brand/                    prompts and generator for the "A · Sereno" logo and mockups (make brand)
data/                     SOURCES.md (origin of every data file and asset) and DATASHEET.md
docs/                     ONBOARDING.md, DEMO.md (script, screenshots in docs/demo/), ARQUITECTURA, COMANDOS,
                          ESTRUCTURA, DECISIONES, CREDITOS (each with an .en.md twin), diagrams (including
                          demo-coverage: what the demo verifies), screenshots and escudo-chat-run.mp4 (fallback)
```
