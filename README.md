# Escudo — una aseguradora de autos lista para agentes

> Demo de la charla **«Built for Agents: Making Your Apps Usable by AI»**.
> [English version](README.en.md) · [Guía de incorporación](docs/ONBOARDING.md)

Escudo es una aseguradora de autos ficticia de Guatemala cuyas capacidades pueden usar agentes de IA
desde tres superficies: una **app Android con AppFunctions**, un **sitio web con WebMCP** y un
**servidor MCP**. Su propio agente delega en un agente de grúas y en uno de talleres por **A2A**, y la
clienta le escribe al **Asistente Escudo** por chat. El escenario de la charla —
*«tuve un choque menor, aquí están las fotos, ayúdame con todo»* — va del reporte al reclamo firmado
con **exactamente tres aprobaciones humanas**, cada una con la huella en el teléfono.

![Vista del presentador durante una pausa de aprobación](docs/screenshots/presenter-chat-pause.png)

## Qué vas a aprender

| Idea | Dónde se ve en el código |
| --- | --- |
| **Funciones, no pantallas.** El dominio son 11 funciones; cada superficie es un adaptador delgado sobre ellas. | `backend/escudo/capabilities/` (un archivo por capacidad, registro en `capabilities/__init__.py`). Adaptadores: `adapters/rest.py`, `adapters/mcp_server.py`, `adapters/assistant_chat.py`, `agents/escudo_agent.py`. Todos entran por una sola puerta: `gateway.call_capability`. En Android, pantallas y AppFunctions usan el mismo `EscudoRepository`; en la web, pantallas y WebMCP usan el mismo `api()` |
| **Qué puede hacer un agente solo y qué necesita a una persona.** Una tabla, aplicada por el servidor, no por el prompt. | `backend/escudo/autonomy.py` (tabla `AUTONOMY` + `enforce()`), `approvals.py` (aprobación de un solo uso atada a herramienta + hash de argumentos). Aprobar **nunca** es una herramienta: solo existe `POST /approvals/{id}/approve`, que llama la app tras `BiometricPrompt` (`android/.../approvals/ApprovalFlow.kt`) |
| **Dónde encaja MCP y dónde A2A.** Herramientas por MCP; trabajo de otras empresas por A2A, descubierto por Agent Card. | MCP: `adapters/mcp_server.py` (`/mcp`, streamable HTTP). A2A: `agents/hosting.py` (Agent Card en `/.well-known/agent-card.json`), `agents/peers.py` (descubrimiento desde `A2A_PEERS`), `agents/tow_agent.py`, `agents/repair_agent.py` |
| **AppFunctions**: la app Android como herramienta para agentes del sistema. | `android/app/src/main/kotlin/app/escudo/android/appfunctions/EscudoAppFunctions.kt` (`reportAccident`, `getCoverage`, `getClaimStatus`), descripción para agentes en `res/xml/app_metadata.xml`. Ver [`android/APPFUNCTIONS.md`](android/APPFUNCTIONS.md) |
| **WebMCP**: el sitio web como herramienta para el agente del navegador. | `web/src/lib/webmcp.ts` (`getCoverage`, `getDeductible`, `findRepairShops`, `getClaimStatus`, todas de solo lectura) y `web/src/components/portal/WebMcpTools.tsx` (registro con `document.modelContext.registerTool`) |
| **Un asistente de chat** con exactamente los mismos poderes —y las mismas pausas— que cualquier cliente MCP. | `backend/escudo/adapters/assistant_chat.py` (ADK `LlmAgent` sobre `GEMINI_MODEL` + `McpToolset` en *loopback* a `/mcp` con el token de la clienta; transcripción en Firestore; tras una aprobación el asistente sigue solo). En Android: `assistant/ChatSession.kt`, `assistant/ChatProtocol.kt` |

## Arquitectura

[![Arquitectura de Escudo](docs/diagrams/architecture.png)](docs/diagrams/architecture.svg)

([SVG](docs/diagrams/architecture.svg) · [HTML](docs/diagrams/architecture.html))

- **`escudo-api`** (Cloud Run): un proceso FastAPI con REST, MCP (`/mcp`), el chat del asistente
  (`/assistant/chat`) y tres agentes A2A (`/a2a/escudo`, `/a2a/tow`, `/a2a/repair`). Cada llamada pasa
  por `gateway.call_capability`, que valida la entrada, corre el *precheck*, aplica `autonomy.py`,
  ejecuta la capacidad y escribe los eventos de la línea de tiempo (validados contra
  `contracts/events.schema.json`).
- **`escudo-web`** (Cloud Run): portal de la clienta con WebMCP y la vista `/presenter`, que lee
  Firestore en el servidor y lo envía al navegador por Server-Sent Events.
- **Escudo para Android** (teléfono o emulador): póliza, reclamo, aprobaciones con huella, chat con el
  Asistente Escudo, push FCM y AppFunctions.
- **Firebase**: Auth (login de la clienta), Firestore (base `escudo`), Cloud Storage (fotos del
  reclamo en `ai-experiments-487722-escudo-claims`) y FCM (push de aprobación). Vertex AI aporta Gemini.

### El escenario, paso a paso

[![Secuencia del choque con tres aprobaciones](docs/diagrams/crash-sequence.png)](docs/diagrams/crash-sequence.svg)

1. Andrea reporta el accidente en la app con tres fotos (`open_claim` + `attach_claim_photos`); el
   perito de daños (Gemini multimodal con salida estructurada) devuelve piezas, severidad y rango de costo.
2. Le escribe al Asistente Escudo «ayúdame con todo». El asistente llama, sin pedir permiso,
   `request_tow` y `list_repair_options` (el agente Escudo delega por A2A en Grúas Aliadas y la Red de
   Talleres) y `request_rental_car` (automático porque la póliza sembrada lo cubre).
3. Tres veces —`select_repair_shop`, `accept_deductible`, `sign_claim`— la herramienta devuelve
   `pending_approval` sin cambiar nada; llega un push, Andrea aprueba con la huella, el chat (que vigila
   esa aprobación en Firestore) envía `approval_resolved` y el asistente, solo, repite la misma llamada
   con el `approval_id`.

### Herramientas, autonomía y superficies

[![Mapa de herramientas](docs/diagrams/tool-autonomy-map.png)](docs/diagrams/tool-autonomy-map.svg)

La tabla completa, con entradas y salidas, está en [`contracts/tools.md`](contracts/tools.md).

## Inicio rápido

Sin nube (todo simulado: `MOCK_AI=1 MOCK_AUTH=1`, almacén en memoria, agentes socios deterministas):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties   # una vez: make test incluye Android
make up-mock          # API en http://127.0.0.1:8080 (ADMIN_TOKEN=local-admin)
make reset            # en otra terminal: vuelve al estado inicial (mismo token)
make scenario         # el escenario completo por MCP, con las 3 aprobaciones firmadas por un dispositivo de prueba
make chat-scenario    # el mismo flujo por chat, con el asistente simulado
make test             # backend (pytest), Android (unitarias) y web (vitest)
```

Contra la nube (requiere ADC y `.env`; ver [docs/ONBOARDING.md](docs/ONBOARDING.md)):

```bash
cp .env.example .env  # y completa los secretos
make reset CLOUD=1    # vuelve el servicio desplegado al estado inicial
make scenario CLOUD=1 APPROVE_DELAY=4   # cliente propio para esta corrida; imprime …/presenter?run=<id>
make chat-scenario CLOUD=1              # lo mismo, escribiéndole al asistente por chat
```

Cada corrida de `make scenario` y `make chat-scenario` crea su propio cliente ficticio
(`POST /admin/runs`: póliza, login de Firebase y `run_id`), así que varias corridas a la vez no chocan
entre sí ni con la clienta del escenario, que es la sembrada. `make reset` borra esos clientes.

Sin red en el escenario: `make up-offline` sirve la web en producción desde la laptop y
`http://127.0.0.1:3217/presenter?demo=1` reproduce una corrida real grabada, rotulada
**REPETICIÓN GRABADA**. Último respaldo: el video [`docs/escudo-chat-run.mp4`](docs/escudo-chat-run.mp4).

## Comandos `make`

Desde la raíz del repo (`make help` los lista todos):

| Comando | Qué hace |
| --- | --- |
| `make up` | API local en `:8080` contra Firestore, Storage y Gemini reales (ADC, `.env`) |
| `make up-mock` | API local sin nube (`MOCK_AI=1 MOCK_AUTH=1`, `ADMIN_TOKEN=local-admin`, el mismo que usa `make reset`) |
| `make up-offline` | Respaldo sin red: build de producción de la web en `127.0.0.1:3217` con fuentes propias; `/presenter?demo=1` reproduce la corrida grabada |
| `make web` | Portal y `/presenter` locales en `:3217` (`npm run dev`, configuración en `web/.env.local`) |
| `make seed` | Escribe póliza, talleres y grúas en Firestore y crea el login de la clienta en Firebase Auth (credenciales en `.env.local`) |
| `make reset` | `POST /admin/reset` en `ESCUDO_BASE_URL` (< 10 s). `CLOUD=1` apunta al servicio desplegado y lee el token de Secret Manager (`escudo-admin-token`) |
| `make test` | `pytest` del backend con `MOCK_AI=1 MOCK_AUTH=1`, unitarias de Android y `vitest` de la web |
| `make scenario` | Escenario completo por MCP en modo prueba, con un cliente propio (`POST /admin/runs`) y un dispositivo de prueba que firma las 3 aprobaciones; `CLOUD=1`, `APPROVE_DELAY=4`. Imprime `…/presenter?run=<id>` |
| `make chat-scenario` | Escenario por chat escrito: cliente propio, reclamo y 3 fotos por REST (como la app) y luego el chat; 3 aprobaciones firmadas; < 3 min. Contra `make up-mock` responde el asistente simulado; `CLOUD=1`, Gemini |
| `make scenario-live` | El escenario por MCP con la clienta sembrada (`--shared`); espera a que apruebe cada paso en el teléfono |
| `make infra` | Crea o verifica todo en GCP desde un proyecto vacío: `infra/01..06` (APIs, base `escudo`, bucket, Artifact Registry, Firebase Auth por API, cuentas de servicio, Secret Manager) |
| `make deploy` | `deploy-api` + `deploy-web`; conservan el `min-instances` actual de cada servicio |
| `make deploy-api` | `infra/10-deploy-api.sh`: construye y despliega `escudo-api` |
| `make deploy-web` | `infra/11-deploy-web.sh`: construye y despliega `escudo-web` |
| `make stage-on` | Día de charla: `min-instances=1` en `escudo-api` y `escudo-web` (sin arranque en frío) |
| `make stage-off` | Fuera de la charla: `min-instances=0` (escalan a cero; los despliegues siguientes lo respetan) |
| `make photos` | Regenera las fotos de ensayo del choque con `IMAGE_MODEL` |
| `make brand` | Regenera logo y maquetas «A · Sereno» con `IMAGE_MODEL` desde `brand/prompts/` (`ARGS="--force logo"`) |
| `make android-build` | `./gradlew assembleDebug` (sabores `live` y `fake`) |
| `make android-test` | Pruebas unitarias de Android (ambos sabores) |
| `make android-install` | Instala y abre la app (`FLAVOR=live` por defecto, `FLAVOR=fake` sin red) |
| `make android-emulator` | Arranca (o reutiliza) el AVD `Pixel_9_API_36_1` (Android 16 QPR2, necesario para `cmd app_function`) y muestra su serial |
| `make android-serial` | Serial adb de ese emulador; todos los comandos de Android lo detectan solos |

`android/Makefile` agrega `make -C android chat-scenario` (escenario por chat en el emulador, con huella
virtual y cronometrado), `google-services` (descarga `google-services.json`) y `appfunctions` (lista las AppFunctions en el dispositivo).

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
  escudo/seed.py, admin.py   datos sintéticos, make seed / make reset
  samples/photos/         3 fotos de ensayo + prompts.json
  scripts/                generate_sample_photos.py
  tests/                  pytest (aprobaciones, firmas de dispositivo, autonomía, A2A, chat, corridas, eventos)
android/                  Kotlin + Compose + Material 3 (ver android/README.md)
  app/src/main/…/appfunctions/   AppFunctions
  app/src/main/…/approvals/      ApprovalFlow + BiometricPrompt
  app/src/main/…/assistant/      chat con el asistente (ChatSession, ChatProtocol)
  scripts/chat_scenario.sh       escenario por chat en el emulador
.github/workflows/test.yml  CI: make test en cada push y PR, solo modos simulados
web/                      Next.js 16 (ver web/README.md)
  src/lib/webmcp.ts       herramientas WebMCP
  src/app/presenter/      vista del presentador (1920×1080)
  scripts/serve-offline.sh  make up-offline: build de producción sin red
infra/                    scripts numerados e idempotentes: 01-06 crean el proyecto desde cero, 10-11 despliegan
brand/                    prompts y generador del logo y las maquetas «A · Sereno» (make brand)
data/                     SOURCES.md (procedencia de cada dato y recurso) y DATASHEET.md
docs/                     ONBOARDING.md, diagramas, capturas y escudo-chat-run.mp4 (corrida completa, respaldo)
```

## Decisiones técnicas

| Decisión | Por qué | Alternativas descartadas |
| --- | --- | --- |
| **Un solo servicio de Cloud Run con Grúa y Talleres adentro, pero solo por A2A.** Los tres agentes están montados en `escudo-api` bajo `/a2a/escudo`, `/a2a/tow` y `/a2a/repair`, cada uno con su Agent Card. | Un despliegue, un arranque en frío y una cuenta de servicio. El agente Escudo solo conoce URLs base (`A2A_PEERS`), lee la Agent Card, indexa por *skill* y llama la URL RPC que declara la tarjeta (`agents/peers.py`); nunca importa código del socio. `tests/test_discovery.py` lo verifica, incluido que no haya rutas de socios escritas en `escudo_agent.py`. | Tres servicios separados (más despliegues y arranques en frío sin enseñar nada nuevo). Llamar las funciones de los socios directamente (escondería la lección de A2A). |
| **El chat no guarda estado en el proceso (hasta 3 instancias).** | La transcripción vive en Firestore (`chat_sessions`) y se reconstruye en cada turno; mientras hay una aprobación pendiente, el chat la vigila en Firestore, así que cuando el teléfono aprueba —llegue esa petición a la instancia que llegue— el chat envía `approval_resolved` y el asistente sigue solo. Por eso `escudo-api` escala hasta 3 instancias (`--session-affinity` mantiene cada WebSocket en su instancia). `min-instances` es 0 por defecto y `make stage-on` lo sube a 1 el día de la charla; `--no-cpu-throttling` y `--timeout=3600` mantienen vivo el WebSocket. | Una sola instancia con avisos en memoria (un punto único de falla, y obliga a `max-instances=1`). Pub/Sub para repartir avisos (más piezas sin necesidad: Firestore ya tiene el estado). |
| **Chat escrito en vez de voz.** | En el emulador el micrófono en vivo nunca funcionó —solo audio pregrabado— y las caídas del socket de Gemini Live agregaban riesgo en el escenario. El texto lo lee el público, se ensaya de forma determinista (`make chat-scenario`) y usa el mismo agente, las mismas herramientas MCP y las mismas pausas de aprobación: la lección de la charla no cambia. El modelo es `GEMINI_MODEL` con `thinking_level=LOW`. | Voz con Gemini Live (`Runner.run_live`). |
| **Un cliente propio por corrida de escenario.** | `POST /admin/runs` (token de admin) crea un cliente efímero: su póliza, un usuario de Firebase `run_<id>` con el claim `customer_id` y un `run_id`. Los corredores mandan `X-Escudo-Run-Id` y la vista `/presenter?run=<id>` sigue solo esa corrida (sin `?run`, la del reclamo más reciente). Sin cabecera, cada evento lleva la corrida de la clienta, así que un aparte por MCP o WebMCP cae en la misma línea de tiempo. Dos ensayos a la vez —o un ensayo mientras el teléfono del escenario está conectado— no se pisan. `make reset` borra esos clientes, sus usuarios y sus dispositivos; la clienta del escenario sigue siendo la sembrada. La cuenta `escudo-api` tiene `roles/firebaseauth.admin` para esto (`infra/04`). | Un solo cliente compartido (las corridas se pisaban). |
| **CI con GitHub Actions.** | `.github/workflows/test.yml` corre `make test` en cada push a `main` y en cada PR: `pytest` del backend, `vitest` de la web y `testDebugUnitTest` de Android con JDK 17, solo en modos simulados (sin llamadas a la nube), con caché de uv, npm y Gradle. Un disparador de Cloud Build para GitHub necesita una conexión OAuth manual en la consola (no hay camino por CLI ni API); Actions no necesita nada más que el repo. | Disparador de Cloud Build. |
| **Aprobación atada a herramienta + hash de argumentos, de un solo uso.** | `approvals.args_hash` es SHA-256 del JSON canónico `{tool, args}` (sin `approval_id` ni nulos). `redeem` comprueba, dentro de una actualización atómica, cliente, herramienta, hash y estado, y marca la aprobación `used`. Una aprobación para «Taller Carrocería Diez el martes» no sirve para otro taller ni otro horario ni dos veces. Antes de pedirla, el servidor corre el *precheck* para no pedir aprobación de algo que fallaría. Una aprobación mal usada (otra herramienta u otros datos) no se quema. | Pedirle al modelo en el prompt que «pregunte antes» (un prompt no es un control). Exponer `approve` como herramienta (el agente se aprobaría a sí mismo). Un token genérico de «sí» sin atar a la acción mostrada. |
| **Aprobar exige una firma del teléfono, no solo el token (`escudo-approval:v1`).** | Un agente con el token de la clienta (MCP, chat, A2A) podría llamar `POST /approvals/{id}/approve` él mismo. Por eso la app registra en `POST /devices` una llave EC P-256 del Android Keystore (desbloqueada por BiometricPrompt) y firma `escudo-approval:v1:{approval_id}:{args_hash}:approve`; el servidor (`backend/escudo/devices.py`) verifica dueño, dispositivo registrado y firma, y si algo falla responde 403 sin cambiar nada. El runner de escenarios hace de teléfono con un dispositivo de prueba en software (`escudo/test_device.py`). | Confiar en el token (cualquier agente con él aprobaría). Un PIN o código enviado por push (se puede reenviar a un agente). |
| **Secreto compartido opcional para A2A (`A2A_SHARED_SECRET`).** | Grúa y Talleres solo responden RPC con la cabecera `X-Escudo-A2A-Key` (comparada con `hmac.compare_digest` en `main.partner_key_guard`). Sus Agent Cards siguen públicas —el descubrimiento debe funcionar— y declaran el esquema `securitySchemes.partnerKey`, así el llamador aprende *cómo* autenticarse desde la tarjeta. El agente Escudo (`/a2a/escudo`) responde sobre los reclamos de una clienta, así que exige credencial: el token de Firebase de la clienta (actúa como ella) o la clave de socio más `X-Escudo-Customer-Id` (`main.EscudoAgentGuard`); su tarjeta declara ambos esquemas. Igual que REST, MCP y el chat, actúa como el `customer_id` del token verificado, nunca como una clienta fija. El secreto vive en Secret Manager (`escudo-a2a-secret`, `infra/06-secrets.sh`) y Cloud Run lo monta; vacío en local y en pruebas. | OAuth o mTLS entre socios: correcto entre empresas reales, desproporcionado para socios que viven en el mismo servicio. |
| **Espejo del teléfono dibujado desde el estado del backend.** | El panel derecho de `/presenter` (`web/src/components/presenter/PhoneMirror.tsx`) muestra lo que la app enseña —aprobación pendiente primero, luego el reclamo— a partir de los mismos datos de Firestore. No depende de USB, del emulador ni de un proceso de captura, y siempre coincide con la línea de tiempo. | Captura de pantalla con `scrcpy` como fuente principal (queda como opción para el píxel exacto: una ventana `scrcpy` encima del panel). Un stream propio desde la app. |
| **El servidor elige el reclamo, no el modelo.** | En cada turno del chat el servidor le recuerda al asistente el reclamo activo, y `gateway.call_capability` corrige cualquier `claim_id` que un agente invente o tome de otra clienta al reclamo abierto más reciente (`repository.active_claim`). Un modelo que alucina un id (`clm_96051512`) no rompe el escenario. Además cada llamada al modelo del chat se corta a los 30 s y se reintenta. | Confiar en que el prompt baste (falló en un ensayo real). Rechazar el id inventado (el escenario se detenía). |
| **Actores con su nombre real.** | En la línea de tiempo, el chat es «Asistente Escudo»; quien llama por MCP, WebMCP o AppFunctions (`adb` en el escenario) es «Agente externo». Ninguna superficie dice «Gemini» si no lo es. El presentador también muestra cuándo un agente descubre a otro (Agent Card) o lista las herramientas (`tools/list`), y marca con «automático» los pasos que no necesitan aprobación. | Etiquetar todo como «Gemini». |
| **Respaldo sin red rotulado.** | `make up-offline` sirve el build de producción desde la laptop, con fuentes propias (`next/font/local`), y `/presenter?demo=1` reproduce una corrida real por chat grabada (`run-6958m5`, 37 eventos). La pantalla dice **REPETICIÓN GRABADA** y «Reclamo grabado»: nunca «en vivo». Último recurso: `docs/escudo-chat-run.mp4`. | Reproducir sin rótulo (engaña al público). Depender de Google Fonts (falla sin red). |
| **Reducción de fotos en dos lugares.** | En el teléfono, `PhotoLoader` deja cada foto en ≤ 1600 px, JPEG 80 (subir fotos crudas de 12 MP tardaba ~40 s). En el backend, `damage_assessor.for_assessment` las lleva a 1024 px, JPEG 80, solo para el modelo: tres fotos tardaban 86–90 s y bajaron a 6–10 s con el mismo veredicto. Los originales recibidos se guardan en Cloud Storage en paralelo con la evaluación. El perito además usa `thinking_level=LOW` y `MEDIA_RESOLUTION_MEDIUM`. | Mandar las fotos a resolución completa (lento y sin mejor resultado). |
| **Todo corre en la nube, más el teléfono.** Ver la tabla siguiente. | La charla no necesita la laptop para nada propio: ningún modelo local ni medición de latencia en el hardware del escenario. Así el escenario no depende de la red del lugar más allá del teléfono y el navegador, y cualquiera puede repetir la demo con las URLs desplegadas. | Correr el backend en la laptop (un componente más que puede fallar en el escenario). |

### Dónde corre cada componente

| Componente | Corre en | Por qué |
| --- | --- | --- |
| `escudo-api` (REST, MCP, chat, agentes A2A, perito) | Cloud Run `us-central1`, 0–3 instancias (1 en reposo con `make stage-on`), cuenta de servicio `escudo-api` | Sin estado en el proceso: el chat y sus aprobaciones viven en Firestore |
| `escudo-web` (portal, WebMCP, `/presenter`) | Cloud Run `us-central1`, 0–2 instancias (1 con `make stage-on`), cuenta de servicio `escudo-web` (Firestore solo lectura) | El presentador no necesita login: el servidor lee Firestore y envía SSE |
| Firestore, Storage, Auth, FCM | Firebase (proyecto `ai-experiments-487722`, base `escudo`) | Usados como SDKs, nunca Firebase Hosting |
| Gemini (perito, chat, agente Escudo) | Vertex AI (`global`) | Modelos por variable de entorno |
| Escudo para Android | El teléfono de la presentadora; en ensayos, el emulador `Pixel_9_API_36_1` | La huella y las AppFunctions son del dispositivo |
| Laptop | Solo un navegador (Chrome con `/presenter` y el portal con WebMCP) y, en ensayos, el emulador | Ningún componente de Escudo corre en la laptop, salvo el respaldo sin red (`make up-offline`) |

## Versiones de las APIs en vista previa

Verificadas el 2026-09-30. El detalle está en [`backend/VERSIONS.md`](backend/VERSIONS.md),
[`android/VERSIONS.md`](android/VERSIONS.md) y [`web/VERSIONS.md`](web/VERSIONS.md).

| Pieza | Versión |
| --- | --- |
| Agent Development Kit (`google-adk[a2a]`) | 2.10.0 |
| A2A Python SDK (`a2a-sdk[http-server]`) | 1.2.1 — protocolo A2A 1.0, Agent Card en `/.well-known/agent-card.json`, binding JSON-RPC |
| MCP Python SDK (`mcp`) | 2.2.0 — `MCPServer`, streamable HTTP sin estado en `/mcp` |
| Google Gen AI SDK (`google-genai`) | 2.25.0 (Vertex AI) |
| Gemini | `GEMINI_MODEL=gemini-3.8-flash` (global), `IMAGE_MODEL=gemini-3.1-flash-lite-image` |
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
| Chat | `wss://escudo-api-611681112050.us-central1.run.app/assistant/chat` (cabecera `Authorization`) |
| Portal | https://escudo-web-611681112050.us-central1.run.app/ |
| Presentador | https://escudo-web-611681112050.us-central1.run.app/presenter (`?run=<id>` sigue una corrida; `?demo=1` reproduce una grabada sin nube, rotulada REPETICIÓN GRABADA) |

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
recursos generados en [`data/SOURCES.md`](data/SOURCES.md); hoja de datos en [`data/DATASHEET.md`](data/DATASHEET.md).
