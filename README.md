# Escudo — una aseguradora de autos lista para agentes

> Demo de la charla **«Built for Agents: Making Your Apps Usable by AI»**.
> [English version](README.en.md) · [Guía de incorporación](docs/ONBOARDING.md)

Escudo es una aseguradora de autos ficticia de Guatemala cuyas capacidades pueden usar agentes de IA
desde tres superficies: una **app Android con AppFunctions**, un **sitio web con WebMCP** y un
**servidor MCP**. Su propio agente delega en un agente de grúas y en uno de talleres por **A2A**, y la
clienta habla con el **Asistente Escudo** por voz (Gemini Live). El escenario de la charla —
*«tuve un choque menor, aquí están las fotos, ayúdame con todo»* — va del reporte al reclamo firmado
con **exactamente tres aprobaciones humanas**, cada una con la huella en el teléfono.

![Vista del presentador durante una pausa de aprobación](docs/screenshots/presenter-live-pause.png)

## Qué vas a aprender

| Idea | Dónde se ve en el código |
| --- | --- |
| **Funciones, no pantallas.** El dominio son 11 funciones; cada superficie es un adaptador delgado sobre ellas. | `backend/escudo/capabilities/` (un archivo por capacidad, registro en `capabilities/__init__.py`). Adaptadores: `adapters/rest.py`, `adapters/mcp_server.py`, `adapters/assistant_live.py`, `agents/escudo_agent.py`. Todos entran por una sola puerta: `gateway.call_capability`. En Android, pantallas y AppFunctions usan el mismo `EscudoRepository`; en la web, pantallas y WebMCP usan el mismo `api()` |
| **Qué puede hacer un agente solo y qué necesita a una persona.** Una tabla, aplicada por el servidor, no por el prompt. | `backend/escudo/autonomy.py` (tabla `AUTONOMY` + `enforce()`), `approvals.py` (aprobación de un solo uso atada a herramienta + hash de argumentos). Aprobar **nunca** es una herramienta: solo existe `POST /approvals/{id}/approve`, que llama la app tras `BiometricPrompt` (`android/.../approvals/ApprovalFlow.kt`) |
| **Dónde encaja MCP y dónde A2A.** Herramientas por MCP; trabajo de otras empresas por A2A, descubierto por Agent Card. | MCP: `adapters/mcp_server.py` (`/mcp`, streamable HTTP). A2A: `agents/hosting.py` (Agent Card en `/.well-known/agent-card.json`), `agents/peers.py` (descubrimiento desde `A2A_PEERS`), `agents/tow_agent.py`, `agents/repair_agent.py` |
| **AppFunctions**: la app Android como herramienta para agentes del sistema. | `android/app/src/main/kotlin/app/escudo/android/appfunctions/EscudoAppFunctions.kt` (`reportAccident`, `getCoverage`, `getClaimStatus`), descripción para agentes en `res/xml/app_metadata.xml`. Ver [`android/APPFUNCTIONS.md`](android/APPFUNCTIONS.md) |
| **WebMCP**: el sitio web como herramienta para el agente del navegador. | `web/src/lib/webmcp.ts` (`getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`, todas de solo lectura) y `web/src/components/portal/WebMcpTools.tsx` (registro con `document.modelContext.registerTool`) |
| **Voz con Gemini Live**: un agente de voz con exactamente los mismos poderes —y las mismas pausas— que cualquier cliente MCP. | `backend/escudo/adapters/assistant_live.py` (ADK `Runner.run_live` + `McpToolset` en *loopback* a `/mcp` con el token de la clienta). En Android: `assistant/LiveAssistantSession.kt`, `assistant/VoiceSource.kt` |

## Arquitectura

[![Arquitectura de Escudo](docs/diagrams/architecture.png)](docs/diagrams/architecture.svg)

([SVG](docs/diagrams/architecture.svg) · [HTML](docs/diagrams/architecture.html))

- **`escudo-api`** (Cloud Run): un solo proceso FastAPI con REST, MCP (`/mcp`), voz
  (`/assistant/live`) y tres agentes A2A (`/a2a/escudo`, `/a2a/tow`, `/a2a/repair`). Cada llamada pasa
  por `gateway.call_capability`, que valida la entrada, corre el *precheck*, aplica `autonomy.py`,
  ejecuta la capacidad y escribe los eventos de la línea de tiempo (validados contra
  `contracts/events.schema.json`).
- **`escudo-web`** (Cloud Run): portal de la clienta con WebMCP y la vista `/presenter`, que lee
  Firestore en el servidor y lo envía al navegador por Server-Sent Events.
- **Escudo para Android** (teléfono o emulador): póliza, reclamo, aprobaciones con huella, Asistente
  Escudo por voz, push FCM y AppFunctions.
- **Firebase**: Auth (login de la clienta), Firestore (base `escudo`), Cloud Storage (fotos del
  reclamo en `ai-experiments-487722-escudo-claims`) y FCM (push de aprobación). Vertex AI aporta Gemini.

### El escenario, paso a paso

[![Secuencia del choque con tres aprobaciones](docs/diagrams/crash-sequence.png)](docs/diagrams/crash-sequence.svg)

1. Andrea reporta el accidente en la app con tres fotos (`open_claim` + `attach_claim_photos`); el
   perito de daños (Gemini multimodal con salida estructurada) devuelve piezas, severidad y rango de costo.
2. Le pide al Asistente Escudo «ayúdame con todo». El asistente llama, sin pedir permiso,
   `request_tow` y `list_repair_options` (el agente Escudo delega por A2A en Grúas Aliadas y la Red de
   Talleres) y `request_rental_car` (automático porque la póliza sembrada lo cubre).
3. Tres veces —`select_repair_shop`, `accept_deductible`, `sign_claim`— la herramienta devuelve
   `pending_approval` sin cambiar nada; llega un push, Andrea aprueba con la huella, el backend avisa a
   la sesión de voz y el asistente repite la misma llamada con el `approval_id`.

### Herramientas, autonomía y superficies

[![Mapa de herramientas](docs/diagrams/tool-autonomy-map.png)](docs/diagrams/tool-autonomy-map.svg)

La tabla completa, con entradas y salidas, está en [`contracts/tools.md`](contracts/tools.md).

## Inicio rápido

Sin nube (todo simulado: `MOCK_AI=1 MOCK_AUTH=1`, almacén en memoria, agentes socios deterministas):

```bash
make up-mock          # API en http://127.0.0.1:8080
make scenario         # en otra terminal: el escenario completo por MCP, con las 3 aprobaciones simuladas
make test             # backend (pytest), Android (unitarias) y web (vitest)
```

Contra la nube (requiere ADC y `.env`; ver [docs/ONBOARDING.md](docs/ONBOARDING.md)):

```bash
cp .env.example .env  # y completa los secretos
make reset CLOUD=1    # vuelve el servicio desplegado al estado inicial
make scenario CLOUD=1 APPROVE_DELAY=4
```

## Comandos `make`

Desde la raíz del repo (`make help` los lista todos):

| Comando | Qué hace |
| --- | --- |
| `make up` | API local en `:8080` contra Firestore, Storage y Gemini reales (ADC, `.env`) |
| `make up-mock` | API local sin nube (`MOCK_AI=1 MOCK_AUTH=1`, `ADMIN_TOKEN=local-admin`) |
| `make web` | Portal y `/presenter` locales en `:3217` (`npm run dev`, configuración en `web/.env.local`) |
| `make seed` | Escribe póliza, talleres y grúas en Firestore y crea el login de la clienta en Firebase Auth (credenciales en `.env.local`) |
| `make reset` | `POST /admin/reset` en `ESCUDO_BASE_URL` (< 10 s). `CLOUD=1` apunta al servicio desplegado y lee el token de Secret Manager (`escudo-admin-token`) |
| `make test` | `pytest` del backend con `MOCK_AI=1 MOCK_AUTH=1`, unitarias de Android y `vitest` de la web |
| `make scenario` | Escenario completo por MCP en modo prueba (`--test-mode --auto-approve`); `CLOUD=1`, `APPROVE_DELAY=4` |
| `make scenario-live` | El mismo escenario, pero espera a que la clienta apruebe cada paso en el teléfono |
| `make infra` | Crea o verifica todo en GCP desde un proyecto vacío: `infra/01..06` (APIs, base `escudo`, bucket, Artifact Registry, Firebase Auth por API, cuentas de servicio, Secret Manager) |
| `make deploy` | `deploy-api` + `deploy-web` |
| `make deploy-api` | `infra/10-deploy-api.sh`: construye y despliega `escudo-api` |
| `make deploy-web` | `infra/11-deploy-web.sh`: construye y despliega `escudo-web` |
| `make photos` | Regenera las fotos de ensayo del choque con `IMAGE_MODEL` |
| `make voices` | Regenera las grabaciones de voz de prueba (macOS `say` + ffmpeg) |
| `make brand` | Regenera logo y maquetas «A · Sereno» con `IMAGE_MODEL` desde `brand/prompts/` (`ARGS="--force logo"`) |
| `make android-build` | `./gradlew assembleDebug` (sabores `live` y `fake`) |
| `make android-test` | Pruebas unitarias de Android (ambos sabores) |
| `make android-install` | Instala y abre la app (`FLAVOR=live` por defecto, `FLAVOR=fake` sin red) |
| `make android-emulator` | Arranca (o reutiliza) el AVD `Pixel_9_API_36_1` (Android 16 QPR2, necesario para `cmd app_function`) y muestra su serial |
| `make android-serial` | Serial adb de ese emulador; todos los comandos de Android lo detectan solos |
| `make voice-scenario` | Escenario completo **por voz** en el emulador, con huella virtual y cronometrado |

`android/Makefile` agrega `make -C android live-test` (prueba instrumentada de voz contra la nube),
`google-services` (descarga `google-services.json`) y `appfunctions` (lista las AppFunctions en el dispositivo).

## Mapa del repo

```
AGENTS.md                 guía para agentes de código (especificación y reglas)
Makefile                  comandos de la tabla anterior
.env.example              variables (modelos, datos, superficies, modos); copia a .env
contracts/                fuente de verdad compartida
  openapi.yaml            API REST (Android y web)
  tools.md                las 11 capacidades, autonomía, superficies, protocolo de aprobación, voz y push
  events.schema.json      esquema de los eventos de la línea de tiempo
  CHANGELOG.md            cambios (todos compatibles hacia atrás)
backend/                  Python 3.13 + uv
  escudo/capabilities/    el dominio: una función por capacidad
  escudo/autonomy.py      qué puede hacer un agente solo
  escudo/approvals.py     aprobaciones de un solo uso
  escudo/gateway.py       la única puerta al dominio
  escudo/adapters/        REST, MCP y voz (Gemini Live)
  escudo/agents/          agente Escudo + socios Grúa y Talleres por A2A
  escudo/damage_assessor.py  perito de daños (Gemini, salida estructurada)
  escudo/scenario.py      corredor del escenario por MCP
  escudo/seed.py, admin.py   datos sintéticos, make seed / make reset
  samples/photos/         3 fotos de ensayo + prompts.json
  scripts/                generate_sample_photos.py, assistant_probe.py
  tests/                  pytest (aprobaciones, autonomía, descubrimiento A2A, eventos, escenario)
android/                  Kotlin + Compose + Material 3 (ver android/README.md)
  app/src/main/…/appfunctions/   AppFunctions
  app/src/main/…/approvals/      ApprovalFlow + BiometricPrompt
  app/src/main/…/assistant/      sesión de voz, VoiceSource (micrófono o WAV)
  scripts/voice_scenario.sh      escenario por voz en el emulador
  testdata/voice/                grabaciones en español para ensayos
web/                      Next.js 16 (ver web/README.md)
  src/lib/webmcp.ts       herramientas WebMCP
  src/app/presenter/      vista del presentador (1920×1080)
infra/                    scripts numerados e idempotentes: 01-06 crean el proyecto desde cero, 10-11 despliegan
brand/                    prompts y generador del logo y las maquetas «A · Sereno» (make brand)
data/                     SOURCES.md (procedencia de cada dato y recurso) y DATASHEET.md
docs/                     ONBOARDING.md, diagramas y capturas
```

## Decisiones técnicas

| Decisión | Por qué | Alternativas descartadas |
| --- | --- | --- |
| **Un solo servicio de Cloud Run con Grúa y Talleres adentro, pero solo por A2A.** Los tres agentes están montados en `escudo-api` bajo `/a2a/escudo`, `/a2a/tow` y `/a2a/repair`, cada uno con su Agent Card. | Un despliegue, un arranque en frío y una cuenta de servicio. El agente Escudo solo conoce URLs base (`A2A_PEERS`), lee la Agent Card, indexa por *skill* y llama la URL RPC que declara la tarjeta (`agents/peers.py`); nunca importa código del socio. `tests/test_discovery.py` lo verifica, incluido que no haya rutas de socios escritas en `escudo_agent.py`. | Tres servicios separados (más despliegues y arranques en frío sin enseñar nada nuevo). Llamar las funciones de los socios directamente (escondería la lección de A2A). |
| **Una sola instancia (`--min-instances=1 --max-instances=1`).** | El aviso «la clienta aprobó» viaja de `POST /approvals/{id}/approve` a la sesión de voz abierta por un `AssistantHub` en memoria; ambos deben vivir en el mismo proceso. También las sesiones ADK y la tienda de tareas A2A están en memoria. `min-instances=1` evita el arranque en frío en el escenario; `--no-cpu-throttling`, `--timeout=3600` y `--session-affinity` mantienen vivo el WebSocket de voz. Una presentadora, una clienta: una instancia sobra. | Un canal entre instancias (Pub/Sub o un listener de Firestore) para repartir avisos: más piezas que pueden fallar en el escenario. |
| **Aprobación atada a herramienta + hash de argumentos, de un solo uso.** | `approvals.args_hash` es SHA-256 del JSON canónico `{tool, args}` (sin `approval_id` ni nulos). `redeem` comprueba, dentro de una actualización atómica, cliente, herramienta, hash y estado, y marca la aprobación `used`. Una aprobación para «Taller Carrocería Diez el martes» no sirve para otro taller ni otro horario ni dos veces. Antes de pedirla, el servidor corre el *precheck* para no pedir aprobación de algo que fallaría. Una aprobación mal usada (otra herramienta u otros datos) no se quema. | Pedirle al modelo en el prompt que «pregunte antes» (un prompt no es un control). Exponer `approve` como herramienta (el agente se aprobaría a sí mismo). Un token genérico de «sí» sin atar a la acción mostrada. |
| **Aprobar exige una firma del teléfono, no solo el token (`escudo-approval:v1`).** | Un agente con el token de la clienta (MCP, voz, A2A) podría llamar `POST /approvals/{id}/approve` él mismo. Por eso la app registra en `POST /devices` una llave EC P-256 del Android Keystore (desbloqueada por BiometricPrompt) y firma `escudo-approval:v1:{approval_id}:{args_hash}:approve`; el servidor (`backend/escudo/devices.py`) verifica dueño, dispositivo registrado y firma, y si algo falla responde 403 sin cambiar nada. El runner de escenarios hace de teléfono con un dispositivo de prueba en software (`escudo/test_device.py`). | Confiar en el token (cualquier agente con él aprobaría). Un PIN o código enviado por push (se puede reenviar a un agente). |
| **Secreto compartido opcional para A2A (`A2A_SHARED_SECRET`).** | Grúa y Talleres solo responden RPC con la cabecera `X-Escudo-A2A-Key` (comparada con `hmac.compare_digest` en `main.partner_key_guard`). Sus Agent Cards siguen públicas —el descubrimiento debe funcionar— y declaran el esquema `securitySchemes.partnerKey`, así el llamador aprende *cómo* autenticarse desde la tarjeta. El agente Escudo (`/a2a/escudo`) responde sobre los reclamos de una clienta, así que exige credencial: el token de Firebase de la clienta (actúa como ella) o la clave de socio más `X-Escudo-Customer-Id` (`main.EscudoAgentGuard`); su tarjeta declara ambos esquemas. Igual que REST, MCP y voz, actúa como el `customer_id` del token verificado, nunca como una clienta fija. El secreto vive en Secret Manager (`escudo-a2a-secret`, `infra/06-secrets.sh`) y Cloud Run lo monta; vacío en local y en pruebas. | OAuth o mTLS entre socios: correcto entre empresas reales, desproporcionado para socios que viven en el mismo servicio. |
| **Espejo del teléfono dibujado desde el estado del backend.** | El panel derecho de `/presenter` (`web/src/components/presenter/PhoneMirror.tsx`) muestra lo que la app enseña —aprobación pendiente primero, luego el reclamo— a partir de los mismos datos de Firestore. No depende de USB, del emulador ni de un proceso de captura, y siempre coincide con la línea de tiempo. | Captura de pantalla con `scrcpy` como fuente principal (queda como opción para el píxel exacto: una ventana `scrcpy` encima del panel). Un stream propio desde la app. |
| **Grabaciones de voz para ensayar en el emulador.** | El asistente escucha a través de una `VoiceSource`: `MicrophoneSource` en producción o `FileSource`, que reproduce un WAV PCM16 16 kHz mono en tiempo real por el mismo camino (fragmentos de 40 ms → WebSocket → Gemini Live → MCP). Con `-allow-host-audio` el micrófono de la laptop llegó al emulador con un pico de 0.013, demasiado bajo; las grabaciones (`android/testdata/voice/`, generadas con `say -v Paulina` + ffmpeg) hacen el ensayo repetible y `make voice-scenario` lo cronometra (168 s de punta a punta el 2026-09-30). Solo las versiones debug las incluyen. Al soltar el botón se envían 2 s de silencio: con 800 ms Gemini Live nunca cerraba el turno. | Ensayar hablando al micrófono de la laptop (no reproducible). Señales manuales de fin de turno (`activity_start`/`activity_end` se aceptan pero se ignoran: los turnos los detecta la VAD automática). |
| **Reducción de fotos en dos lugares.** | En el teléfono, `PhotoLoader` deja cada foto en ≤ 1600 px, JPEG 80 (subir fotos crudas de 12 MP tardaba ~40 s). En el backend, `damage_assessor.for_assessment` las lleva a 1024 px, JPEG 80, solo para el modelo: tres fotos tardaban 86–90 s y bajaron a 6–10 s con el mismo veredicto. Los originales recibidos se guardan en Cloud Storage en paralelo con la evaluación. El perito además usa `thinking_level=LOW` y `MEDIA_RESOLUTION_MEDIUM`. | Mandar las fotos a resolución completa (lento y sin mejor resultado). |
| **`gemini-3.8-live` en `us-central1`.** | En Vertex AI el endpoint `global` responde «model not found» para todos los modelos Live probados; `gemini-3.8-live` transmite audio de 24 kHz desde `us-central1`. Por eso hay una variable aparte, `GEMINI_LIVE_LOCATION`, que ADK recibe con `Gemini(client_kwargs={"location": …})`; el resto de Gemini usa `global`. | Usar `global` para todo (no funciona para Live). |
| **Todo corre en la nube, más el teléfono.** Ver la tabla siguiente. | La charla no necesita la laptop para nada propio: ningún modelo local ni medición de latencia en el hardware del escenario. Así el escenario no depende de la red del lugar más allá del teléfono y el navegador, y cualquiera puede repetir la demo con las URLs desplegadas. | Correr el backend en la laptop (un componente más que puede fallar en el escenario). |

### Dónde corre cada componente

| Componente | Corre en | Por qué |
| --- | --- | --- |
| `escudo-api` (REST, MCP, voz, agentes A2A, perito) | Cloud Run `us-central1`, 1 instancia, cuenta de servicio `escudo-api` | Un proceso para la voz y sus avisos; sin arranque en frío |
| `escudo-web` (portal, WebMCP, `/presenter`) | Cloud Run `us-central1`, 1–2 instancias, cuenta de servicio `escudo-web` (Firestore solo lectura) | El presentador no necesita login: el servidor lee Firestore y envía SSE |
| Firestore, Storage, Auth, FCM | Firebase (proyecto `ai-experiments-487722`, base `escudo`) | Usados como SDKs, nunca Firebase Hosting |
| Gemini (perito, agentes, Live) | Vertex AI (`global`; Live en `us-central1`) | Modelos por variable de entorno |
| Escudo para Android | El teléfono de la presentadora; en ensayos, el emulador `Pixel_9_API_36_1` | La huella y las AppFunctions son del dispositivo |
| Laptop | Solo un navegador (Chrome con `/presenter` y el portal con WebMCP) y, en ensayos, el emulador | Ningún componente de Escudo corre en la laptop |

## Versiones de las APIs en vista previa

Verificadas el 2026-09-30. El detalle está en [`backend/VERSIONS.md`](backend/VERSIONS.md),
[`android/VERSIONS.md`](android/VERSIONS.md) y [`web/VERSIONS.md`](web/VERSIONS.md).

| Pieza | Versión |
| --- | --- |
| Agent Development Kit (`google-adk[a2a]`) | 2.10.0 |
| A2A Python SDK (`a2a-sdk[http-server]`) | 1.2.1 — protocolo A2A 1.0, Agent Card en `/.well-known/agent-card.json`, binding JSON-RPC |
| MCP Python SDK (`mcp`) | 2.2.0 — `MCPServer`, streamable HTTP sin estado en `/mcp` |
| Google Gen AI SDK (`google-genai`) | 2.25.0 (Vertex AI) |
| Gemini | `GEMINI_MODEL=gemini-3.8-flash` (global), `GEMINI_LIVE_MODEL=gemini-3.8-live` (`us-central1`), `IMAGE_MODEL=gemini-3.1-flash-lite-image` |
| Jetpack AppFunctions (`androidx.appfunctions`) | 1.0.0-alpha12; `adb shell cmd app_function` desde Android 16 QPR2 (API 36.1) |
| Gemini + AppFunctions de terceros | Vista previa privada; no disponible para esta app. En el escenario las AppFunctions se muestran con `adb` |
| WebMCP | Borrador del W3C WebML CG del 2026-09-30 (`document.modelContext.registerTool`); probado en Chrome 154.0.8037.59 con `chrome://flags/#enable-webmcp-testing` |
| Next.js / React | 16.3.8 / 19.2.8 |
| Firebase | Android BoM 34.19.0, JS SDK 12.19.0, `firebase-admin` 7.7.0 (Python) y 14.5.0 (Node) |

## URLs desplegadas

| Qué | URL |
| --- | --- |
| API | https://escudo-api-611681112050.us-central1.run.app (`/health`) |
| MCP | https://escudo-api-611681112050.us-central1.run.app/mcp (bearer: ID token de Firebase) |
| Agent Cards | https://escudo-api-611681112050.us-central1.run.app/a2a/escudo/.well-known/agent-card.json (también `/a2a/tow/…` y `/a2a/repair/…`) |
| Voz | `wss://escudo-api-611681112050.us-central1.run.app/assistant/live` |
| Portal | https://escudo-web-611681112050.us-central1.run.app/ |
| Presentador | https://escudo-web-611681112050.us-central1.run.app/presenter (`?demo=1` reproduce una corrida grabada sin nube) |

## Créditos y procedencia

- **Fotos de ensayo del choque** (`backend/samples/photos/crash_{1,2,3}.jpg`): sintéticas, generadas con
  `gemini-3.1-flash-lite-image` por `backend/scripts/generate_sample_photos.py` (`make photos`, semilla
  2027). El modelo, el prompt y la semilla de cada imagen están en `backend/samples/photos/prompts.json`.
- **Grabaciones de voz** (`android/testdata/voice/*.wav`): sintéticas, macOS `say -v Paulina` (es_MX) +
  ffmpeg, con `generate.sh`; textos y formato en `android/testdata/voice/SOURCES.md`.
- **Datos semilla** (clienta Andrea Morales, póliza `POL-GT-2027-0042`, talleres, grúas): ficticios,
  escritos por `backend/escudo/seed.py`; cada registro lleva `origin` (`synthetic`, semilla 2027).
- **Identidad visual** «A · Sereno»: tokens en `android/design/a-sereno/tokens.json` y
  `web/src/brand/tokens.json`, marca vectorial `android/design/a-sereno/mark.svg`; logo y maquetas
  sintéticos (`IMAGE_MODEL`) con sus prompts y generador en `brand/` (`make brand`).
- **Tipografía**: Onest (SIL OFL 1.1, `android/design/a-sereno/Onest-OFL.txt`).
- **Diagramas** (`docs/diagrams/`): hechos con la skill `diagram-design` (piel editorial clara, acento pino `#0F5C4D`).

Todas las empresas, personas, placas y direcciones son ficticias. Inventario completo de datos y
recursos generados en [`data/SOURCES.md`](data/SOURCES.md); hoja de datos en [`data/DATASHEET.md`](data/DATASHEET.md).
