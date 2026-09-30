# Escudo para Android

App del cliente de Escudo: póliza, estado del reclamo, aprobaciones con biometría, Asistente
Escudo por voz y reporte de accidentes. Expone `reportAccident`, `getCoverage` y
`getClaimStatus` como AppFunctions para agentes del sistema (ver [APPFUNCTIONS.md](APPFUNCTIONS.md)).
Versiones en [VERSIONS.md](VERSIONS.md).

## Qué enseña

- **Funciones, no pantallas**: pantallas y AppFunctions llaman al mismo `EscudoRepository`.
  Cada llamada viaja con `X-Escudo-Surface` / `X-Escudo-Actor` (`android`/`customer` o
  `appfunctions`/`gemini`) para que la línea de tiempo del presentador muestre quién actuó.
- **Aprobar es humano**: `ApprovalFlow` pide BiometricPrompt (con PIN de respaldo) antes de
  `POST /approvals/{id}/approve`. Aprobar nunca es una AppFunction.

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
```

Ensayo de aprobación sin backend: sabor `fake`, reportar un accidente con foto crea una
aprobación pendiente; `adb shell am start -d escudo://approvals/<id>` abre la pantalla como lo
hace la notificación. En el emulador, PIN con `adb shell locksettings set-pin 1234`.

## Protocolos con el backend

- REST: `contracts/openapi.yaml`.
- Voz (`/assistant/live`): audio binario PCM16 16 kHz de subida y 24 kHz de bajada; mensajes
  JSON `ready`, `transcript`, `tool_call`, `approval_required`, `interrupted`, `turn_complete`,
  `error`. El botón es push-to-talk; al soltarlo se envían 800 ms de silencio para que la
  detección automática de actividad cierre el turno. Ver `assistant/AssistantProtocol.kt`.
- Push de aprobación: notificación + datos `{"type": "approval_request", "approval_id", …}`,
  canal `approvals`; al tocarla abre `escudo://approvals/<id>`.

## Mapa

```
app/src/main/kotlin/app/escudo/android/
  data/            API (HTTP y falsa), repositorio, eventos de la línea de tiempo, fotos
  appfunctions/    reportAccident, getCoverage, getClaimStatus + mapeo de entradas/salidas
  approvals/       ApprovalFlow + BiometricVerifier
  assistant/       sesión de voz (WebSocket), micrófono y parlante
  push/            FCM: registro del dispositivo y notificación de aprobación
  ui/theme/        paleta, tipografía y formas (todo el estilo en un solo lugar)
  ui/…             pantallas: póliza, reclamo, aprobaciones, asistente, reportar
```
