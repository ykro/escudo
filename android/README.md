# Escudo para Android

App del cliente de Escudo: póliza, estado del reclamo, aprobaciones con biometría, Asistente
Escudo por voz y reporte de accidentes. Expone `reportAccident`, `getCoverage` y
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
make live-test             # prueba de voz instrumentada en el emulador contra la nube
```

Ensayo de aprobación sin backend: sabor `fake`, reportar un accidente con foto crea una
aprobación pendiente; `adb shell am start -d escudo://approvals/<id>` abre la pantalla como lo
hace la notificación. En el emulador, PIN con `adb shell locksettings set-pin 1234`.

## Voz en el emulador (sin teléfono)

El asistente escucha a través de una `VoiceSource`: `MicrophoneSource` (AudioRecord, producción) o
`FileSource`, que reproduce un WAV PCM16 16 kHz mono **en tiempo real** por el mismo camino:
fragmentos de 40 ms → WebSocket → Gemini Live → herramientas MCP → voz de respuesta.

- Grabaciones de prueba en español: `testdata/voice/*.wav` (generadas con `generate.sh`, origen en
  `SOURCES.md`). Solo las versiones debug las incluyen.
- Hacer que el asistente "escuche" una grabación (solo debug):

  ```bash
  adb shell am start -n app.escudo.android/.MainActivity --es voice_input_file 01_choque
  adb shell am start -n app.escudo.android/.MainActivity --es voice_input_file 02_taller
  # o un archivo propio (debug pide READ_MEDIA_AUDIO):
  adb shell pm grant app.escudo.android android.permission.READ_MEDIA_AUDIO
  adb shell am start -n app.escudo.android/.MainActivity --es voice_input_file /sdcard/Download/mi_voz.wav
  ```

  También hay un atajo oculto: mantener presionado el título «Asistente Escudo» abre la lista de grabaciones.
- Prueba automática contra la nube: `make live-test` (instrumentada, `LIVE_TESTS=1`): reproduce
  `01_choque.wav` y verifica que el servidor la transcribe, que llega una llamada a herramienta y
  que el asistente responde con audio. `make test` no la ejecuta (sigue sin red).
- En el escenario el presentador habla al micrófono de la laptop: el emulador lo usa si se inicia con
  `emulator -avd Pixel_9_API_36_1 -allow-host-audio`. Probado con `say` por los parlantes: el
  micrófono de la laptop capta muy poco (pico 0.013), así que el camino probado y para ensayos es
  `FileSource`; la voz real requiere hablar cerca del micrófono.
- Escenario completo por voz, cronometrado: `make voice-scenario` (`scripts/voice_scenario.sh`). Reporta
  el accidente con 3 fotos, habla con las grabaciones y aprueba cada pausa con la huella virtual
  (`adb emu finger touch 1`) hasta el reclamo firmado. No duerme tiempos fijos: espera los
  marcadores `EscudoScenario` de logcat (`voice_end`, `assistant_idle`, `approval_required`,
  `approval_outcome`, `report_done`). Antes: `make reset CLOUD=1` en la raíz. Medido el
  2026-09-30: **168 s** de punta a punta (41 s son automatización de la UI del reporte), 3 aprobaciones.
  Con `escudo-approval:v1` (2026-10-01): **223 s**, 3 aprobaciones firmadas; 36 s son automatización de
  la UI y ~25 s un timeout A2A al taller (`list_repair_options`) que el backend reintenta.
  Con los agentes socios corregidos (2026-10-01): **169 s**, 3 aprobaciones firmadas con huella.
- Las aprobaciones del escenario se firman con la llave del dispositivo: la huella virtual desbloquea
  la llave a través de BiometricPrompt (`CryptoObject`), igual que en un teléfono real.
- Preparar la huella del emulador (una vez): PIN `adb shell locksettings set-pin 1234`, luego
  Ajustes → Seguridad → Huella (`adb shell am start -a android.settings.FINGERPRINT_ENROLL`) y tocar
  el sensor con `adb emu finger touch 1` hasta «Fingerprint added».
- Al soltar el botón se envían 2 s de silencio: con 800 ms Live no detecta el fin del turno.

## Protocolos con el backend

- REST: `contracts/openapi.yaml`.
- Voz (`/assistant/live`): audio binario PCM16 16 kHz de subida y 24 kHz de bajada; mensajes
  JSON `ready`, `transcript`, `tool_call`, `approval_required`, `interrupted`, `turn_complete`,
  `error`. El botón es push-to-talk; al soltarlo se envían 2 s de silencio para que la
  detección automática de actividad cierre el turno. Ver `assistant/AssistantProtocol.kt`.
- El asistente también acepta texto (ícono de teclado junto al micrófono): útil en lugares ruidosos
  y para ensayar sin micrófono. Usa el mismo socket (`{"type":"text"}`).
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
