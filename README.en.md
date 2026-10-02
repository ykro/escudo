# Escudo — an agent-ready car insurer

> Demo for the talk **"Built for Agents: Making Your Apps Usable by AI"** · [Español](README.md)

Escudo is a fictional Guatemalan car insurer that AI agents can use from an **Android app with
AppFunctions**, a **website with WebMCP**, an **MCP server** and a **chat**; its agent delegates tow
and repair to partners over **A2A**. From "I had a minor crash, help me with everything" to a signed
claim, with **exactly three fingerprint approvals**.

## What you'll learn

- **Functions, not screens:** 11 capabilities; every surface is a thin adapter over them.
- **Autonomy vs. approval:** a server-side table decides what the agent may do alone; approving is never a tool.
- **MCP vs. A2A:** tools over MCP; other companies' work over A2A, discovered through their Agent Card.
- **AppFunctions and WebMCP:** the app and the site as tools for the system's and the browser's agents.

[![Escudo architecture](docs/diagrams/architecture.png)](docs/diagrams/architecture.svg)
[![What the demo verifies](docs/diagrams/demo-coverage.png)](docs/diagrams/demo-coverage.svg)

## Quickstart

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties  # once
make up-mock                 # cloud-free API on :8080
make scenario                # another terminal: the scenario over MCP, 3 approvals
make chat-scenario           # the same flow over the chat
make test                    # backend, Android and web
make reset CLOUD=1           # cloud: initial state (needs ADC and .env)
make chat-scenario CLOUD=1   # cloud: prints …/presenter?run=<id>
```

## Deployed URLs

| What | URL |
| --- | --- |
| API · MCP · chat | https://escudo-api-611681112050.us-central1.run.app (`/health`, `/mcp`, `wss://…/assistant/chat`) |
| Agent Cards | https://escudo-api-611681112050.us-central1.run.app/a2a/escudo/.well-known/agent-card.json (also `/a2a/tow/…`, `/a2a/repair/…`) |
| Portal · presenter | https://escudo-web-611681112050.us-central1.run.app/ · [`/presenter`](https://escudo-web-611681112050.us-central1.run.app/presenter) (`?demo=1`: recorded replay) |

## Documentation

- [Demo script (Spanish)](docs/DEMO.md): step by step, with screenshots
- [Onboarding guide (Spanish)](docs/ONBOARDING.md): setup, cloud, tests, troubleshooting
- [Architecture](docs/ARQUITECTURA.en.md) · [Commands](docs/COMANDOS.en.md) · [Repo structure](docs/ESTRUCTURA.en.md)
- [Technical decisions and versions](docs/DECISIONES.en.md) · [Credits and provenance](docs/CREDITOS.en.md)
- Contracts: [tools](contracts/tools.md), [REST API](contracts/openapi.yaml), [events](contracts/events.schema.json)
