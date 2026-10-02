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

## Configuración (una vez, antes de la charla)

1. `google-services.json` (no se versiona):

   ```bash
   make google-services   # firebase apps:sdkconfig ANDROID <app id> --project ai-experiments-487722
   ```

   App registrada en Firebase: `app.escudo.android` (`1:611681112050:android:cecc13cdf49afe50f6e56b`).
   Sin el archivo la app compila y corre con la API falsa, sin Auth ni FCM.
   `app/google-services.json.example` muestra la forma.

2. `local.properties` (no se versiona):

   ```properties
   sdk.dir=/Users/<tú>/Library/Android/sdk
   escudo.apiUrl=https://escudo-api-<hash>-uc.a.run.app
   escudo.customerEmail=<correo del cliente sembrado>
   escudo.customerPassword=<contraseña>
   # Solo para un backend local con MOCK_AUTH=1 (make up-mock), desde el emulador:
   # escudo.apiUrl=http://10.0.2.2:8080
   # escudo.mockAuth=true
   ```

   También se aceptan las variables `ESCUDO_API_URL`, `ESCUDO_CUSTOMER_EMAIL`,
   `ESCUDO_CUSTOMER_PASSWORD`, `ESCUDO_MOCK_AUTH`.

3. Abrir la app una vez: inicia sesión sola con el correo del cliente, registra el token FCM
   (`POST /devices`) y pide permiso de notificaciones. En el escenario no hay pantalla de login.

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
```

Ensayo de aprobación sin backend: sabor `fake`, reportar un accidente con foto crea una
aprobación pendiente; `adb shell am start -d escudo://approvals/<id>` abre la pantalla como lo
hace la notificación. En el emulador, PIN con `adb shell locksettings set-pin 1234`.

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
  teclado) y aprueba cada pausa con la huella virtual (`adb emu finger touch 1`) hasta el reclamo
  firmado. Espera marcadores `EscudoScenario` de logcat, no tiempos fijos. Antes: `make reset CLOUD=1`
  en la raíz. Medido el 2026-10-01: **106 s** (35 s son automatización de la UI del reporte),
  3 aprobaciones firmadas con huella.
- Preparar la huella del emulador (una vez): PIN `adb shell locksettings set-pin 1234`, luego
  `adb shell am start -a android.settings.FINGERPRINT_ENROLL` y tocar el sensor con
  `adb emu finger touch 1` hasta «Fingerprint added».
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
