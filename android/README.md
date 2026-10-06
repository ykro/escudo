# Escudo para Android

App del cliente de Escudo: póliza, estado del reclamo, aprobaciones con biometría, Asistente
Escudo (chat escrito) y reporte de accidentes. Expone `reportAccident`, `getCoverage` y
`getClaimStatus` como AppFunctions para agentes del sistema (ver [APPFUNCTIONS.md](APPFUNCTIONS.md)).
Versiones en [VERSIONS.md](VERSIONS.md).

## Diseño

Dirección visual «A · Sereno» (`design/a-sereno/`: tokens, logo, maqueta y licencia de la fuente).
Todo el estilo vive en `ui/theme`: pino `#0F5C4D` como semilla de Material 3, contenedores arena,
fondo niebla, tipografía Onest (variable, empaquetada en `res/font`). El ámbar se reserva para las
pausas de aprobación. Solo tema claro. El ícono adaptable redibuja la marca (escudo + camino) en
vectores, con versión monocromática.

## Qué enseña

- **Funciones, no pantallas**: pantallas y AppFunctions llaman al mismo `EscudoRepository`.
  Cada llamada viaja con `X-Escudo-Surface` / `X-Escudo-Actor` (`android`/`customer` o
  `appfunctions`/`gemini`) para que la línea de tiempo del presentador muestre quién actuó.
- **Aprobar es humano, y desde este teléfono** (`escudo-approval:v1`, ver `contracts/tools.md`): en el
  primer arranque la app crea una llave EC P-256 en el Android Keystore (`escudo-approval`), que solo se
  usa tras una huella o el PIN en ese momento (`setUserAuthenticationParameters(0, …)`), y registra su
  llave pública en `POST /devices` (`{device_id, fcm_token, platform, public_key}`). Para aprobar o
  rechazar, `ApprovalFlow` pasa una `Signature("SHA256withECDSA")` a BiometricPrompt como `CryptoObject`,
  firma `escudo-approval:v1:{approval_id}:{args_hash}:approve|reject` y envía `{device_id, signature}`.
  Un agente con el token del cliente no puede aprobar: no tiene la llave del teléfono (el servidor
  responde `403 approval_forbidden`). Aprobar nunca es una AppFunction.

## Configuración (antes de la charla)

```bash
make prep    # o `make android-prep` en la raíz, que además arranca el emulador
```

`scripts/prep_emulator.sh` deja el emulador listo para la charla y solo hace lo que falta (después se
cierra el emulador normalmente: su snapshot de arranque rápido guarda todo, y el día de la charla basta
con abrirlo):

- PIN `1234` y pantalla desbloqueada (la huella necesita un PIN).
- `local.properties` (no se versiona) escrito desde `../.env.local`, que crea `make seed` en la raíz:
  `sdk.dir`, `escudo.apiUrl` (el API desplegado), `escudo.customerEmail` y `escudo.customerPassword`.
- `app/google-services.json` (no se versiona) con `make google-services`
  (`firebase apps:sdkconfig ANDROID 1:611681112050:android:cecc13cdf49afe50f6e56b --project ai-experiments-487722`).
  Sin ese archivo la app compila y corre con la API falsa, sin Auth ni FCM.
- App `live` instalada, permisos `POST_NOTIFICATIONS` y `READ_MEDIA_IMAGES`, las tres fotos del choque en
  la galería (`escudo_crash_1..3.jpg`) y una huella registrada (maneja la pantalla de registro de Ajustes
  con `adb emu finger touch 1`).
- El AppFunction Testing Agent de Google (`scripts/prep_appfunctions_agent.sh`) instalado y corriendo
  con su `ShellIdentityInstrumentation`, que le da el permiso de sistema para llamar las AppFunctions de
  Escudo con taps.
- Abre la app: inicia sesión sola con la clienta y registra su token FCM y su llave de aprobación
  (`POST /devices`). La app lo vuelve a registrar cada vez que arranca. En el escenario no hay pantalla
  de login.

Para un backend local con `MOCK_AUTH=1` (`make up-mock`), agrega a `local.properties`
`escudo.apiUrl=http://10.0.2.2:8080` y `escudo.mockAuth=true`. También se aceptan las variables
`ESCUDO_API_URL`, `ESCUDO_CUSTOMER_EMAIL`, `ESCUDO_CUSTOMER_PASSWORD`, `ESCUDO_MOCK_AUTH`.

## Sabores

| Sabor | API | Uso |
| --- | --- | --- |
| `live` | `escudo-api` en `ESCUDO_API_URL`, Firebase Auth | La charla |
| `fake` | En memoria (`FakeEscudoApi`), asistente simulado | Desarrollo y ensayos sin red |

## Comandos

```bash
make build                 # ./gradlew assembleDebug (ambos sabores)
make test                  # ./gradlew testDebugUnitTest (ambos sabores)
make install               # instala live y abre la app; FLAVOR=fake para la API falsa
make appfunctions          # lista las AppFunctions de Escudo en el dispositivo (API 36.1+)
make chat-scenario         # escenario completo por chat en el emulador contra la nube, cronometrado
make prep                  # deja el emulador listo para la charla (idempotente)
```

Ensayo de aprobación sin backend: sabor `fake`, reportar un accidente con foto crea una
aprobación pendiente; `adb shell am start -d escudo://approvals/<id>` abre la pantalla como lo
hace la notificación.

## Asistente Escudo (chat escrito)

El asistente es un chat escrito sobre el mismo agente y las mismas herramientas MCP
(`wss://…/assistant/chat`, ver `contracts/tools.md`). La app muestra las respuestas en streaming,
chips en línea con lo que el agente está haciendo («Buscando talleres…») y una tarjeta ámbar en línea
cuando el agente se detiene por una aprobación (el chip de esa acción queda en ámbar, «esperando tu
aprobación», sin puntos de «escribiendo»: el agente está detenido a propósito, y solo pasa a ✓ cuando
el reintento aprobado tiene éxito); «Revisar y aprobar» abre la aprobación con huella y, al
aprobar, la conversación continúa sola (`approval_resolved`). Si la conexión se cae (Cloud Run corta
los WebSockets a los 60 min), la app se reconecta con el mismo `session_id` y la conversación sigue.

- Escenario completo cronometrado: `make chat-scenario` (`scripts/chat_scenario.sh`). Reporta el
  accidente con 3 fotos, escribe al asistente (extra de depuración
  `adb shell am start -n app.escudo.android/.MainActivity --es chat_message '…'`, el mismo camino que el
  teclado; la descripción del accidente entra con `--es report_description '…'`, porque `adb input text`
  no escribe acentos) y aprueba cada pausa con la huella virtual (`adb emu finger touch 1`) hasta el reclamo
  firmado. Espera marcadores `EscudoScenario` de logcat, no tiempos fijos. Antes: **Empezar de nuevo**
  en la pantalla Póliza (o `make reset CLOUD=1` en la raíz). Medido el 2026-10-01: **106 s** (35 s son automatización de la UI del reporte),
  3 aprobaciones firmadas con huella.
- PIN y huella del emulador: `make prep` (arriba).
- Capturas: `docs/screenshots/chat/`.

## Protocolos con el backend

- REST: `contracts/openapi.yaml`.
- Chat (`/assistant/chat`): tramas JSON `ready`, `text` (deltas `final:false`, mensaje completo `final:true`),
  `tool_call`, `tool_result`, `approval_required`, `approval_resolved`, `turn_complete`, `error`; autenticación solo
  con el encabezado `Authorization`. Ver `assistant/ChatProtocol.kt`.
- Push de aprobación: notificación + datos `{"type": "approval_request", "approval_id", …}`,
  canal `approvals`; al tocarla abre `escudo://approvals/<id>`.

## Mapa

```
app/src/main/kotlin/app/escudo/android/
  data/            API (HTTP y falsa), repositorio, eventos de la línea de tiempo, fotos
  appfunctions/    reportAccident, getCoverage, getClaimStatus + mapeo de entradas/salidas
  approvals/       ApprovalFlow + BiometricVerifier
  assistant/       chat con el Asistente Escudo (WebSocket, protocolo, sesión falsa)
  push/            FCM: registro del dispositivo y notificación de aprobación
  ui/theme/        paleta, tipografía y formas (todo el estilo en un solo lugar)
  ui/…             pantallas: póliza, reclamo, aprobaciones, asistente, reportar
```
