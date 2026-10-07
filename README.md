# Escudo — una aseguradora de autos lista para agentes

> Demo de la charla **«Built for Agents: Making Your Apps Usable by AI»** · [English](README.en.md)

Escudo es una aseguradora de autos ficticia de Guatemala que los agentes de IA pueden usar desde una
**app Android con AppFunctions**, un **sitio con WebMCP**, un **servidor MCP** y un **chat**; su agente
delega grúa y taller en socios por **A2A**. De «tuve un choque menor, ayúdame con todo» al reclamo
firmado, con **exactamente tres aprobaciones con huella**.

## Qué vas a aprender

- **Funciones, no pantallas:** 11 capacidades; cada superficie es un adaptador delgado sobre ellas.
- **Autonomía frente a aprobación:** una tabla en el servidor decide qué hace el agente solo; aprobar nunca es una herramienta.
- **MCP frente a A2A:** herramientas por MCP; trabajo de otras empresas por A2A, descubiertas por su Agent Card.
- **AppFunctions y WebMCP:** la app y el sitio como herramientas para los agentes del sistema y del navegador.

[![Arquitectura de Escudo](docs/diagrams/architecture.png)](docs/diagrams/architecture.svg)
[![Qué se prueba en la demo](docs/diagrams/demo-coverage.png)](docs/diagrams/demo-coverage.svg)

## Inicio rápido

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties  # una vez
make up-mock                 # API sin nube en :8080
make scenario                # otra terminal: el escenario por MCP, 3 aprobaciones
make chat-scenario           # el mismo flujo por chat
make test                    # backend, Android y web
make chat-scenario CLOUD=1   # nube: imprime …/presentador?run=<id>
```

El día de la charla no se ejecuta nada: abres el [presentador](https://escudo-web-611681112050.us-central1.run.app/presentador)
(despierta los servicios, que se apagan solos cuando nadie los usa) y el emulador `Pixel_9_API_36_1`, que
ya tiene la app lista. **Empezar de nuevo**, en la pantalla Póliza de la app, vuelve todo al estado
inicial. Guion: [docs/DEMO.md](docs/DEMO.md).

## Qué es el producto y qué es solo para la charla

- **El producto, lo puede probar cualquiera:** la app Android de Escudo y el portal web
  https://escudo-web-611681112050.us-central1.run.app/ (póliza, reclamo y talleres, con las herramientas
  WebMCP). Se ven bien en teléfono, tableta y computadora.
- **Solo para la demo en vivo:** el presentador,
  https://escudo-web-611681112050.us-central1.run.app/presentador. Es la pantalla del proyector: muestra
  en grande lo que hacen los agentes mientras la clienta usa la app. No es parte del producto. En el
  teléfono se acomoda en una sola columna. `/presenter` redirige aquí.

| Portal en el teléfono | Presentador en el teléfono |
| --- | --- |
| ![Portal en un teléfono de 375 px](docs/demo/movil-poliza.png) | ![Presentador en un teléfono de 375 px](docs/demo/movil-presentador.png) |

## URLs desplegadas

| Qué | URL |
| --- | --- |
| API · MCP · chat | https://escudo-api-611681112050.us-central1.run.app (`/health`, `/mcp`, `wss://…/assistant/chat`) |
| Agent Cards | https://escudo-api-611681112050.us-central1.run.app/a2a/escudo/.well-known/agent-card.json (también `/a2a/tow/…`, `/a2a/repair/…`) |
| Portal (producto) | https://escudo-web-611681112050.us-central1.run.app/ |
| Presentador (solo la charla) | https://escudo-web-611681112050.us-central1.run.app/presentador (`?demo=1`: repetición grabada) |

## Documentación

- [Guion de la demo](docs/DEMO.md): paso a paso, con capturas ([English](docs/DEMO.en.md))
- [Guía de incorporación](docs/ONBOARDING.md): instalación, nube, pruebas, problemas frecuentes
- [Arquitectura](docs/ARQUITECTURA.md) · [Comandos](docs/COMANDOS.md) · [Estructura del repo](docs/ESTRUCTURA.md)
- [Decisiones técnicas y versiones](docs/DECISIONES.md) · [Créditos y procedencia](docs/CREDITOS.md)
- Contratos: [herramientas](contracts/tools.md), [API REST](contracts/openapi.yaml), [eventos](contracts/events.schema.json)
