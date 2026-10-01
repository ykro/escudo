# Escudo — guía de incorporación

Todo lo necesario para correr, probar, desplegar y ensayar Escudo desde cero. Para el panorama
(qué enseña, arquitectura, decisiones) lee primero el [README](../README.md). Los contratos
(`contracts/tools.md`, `contracts/openapi.yaml`, `contracts/events.schema.json`) son la fuente de verdad
de lo que hablan las tres partes.

Contenido:

1. [Requisitos](#1-requisitos)
2. [Preparación local](#2-preparación-local)
3. [Credenciales y ADC](#3-credenciales-y-adc)
4. [Firebase y la clienta sembrada](#4-firebase-y-la-clienta-sembrada)
5. [Google Cloud con `infra/`](#5-google-cloud-con-infra)
6. [Correr en local o en la nube](#6-correr-en-local-o-en-la-nube)
7. [Pruebas](#7-pruebas)
8. [Emulador Android](#8-emulador-android)
9. [Mostrar AppFunctions y WebMCP](#9-mostrar-appfunctions-y-webmcp)
10. [Recorrido por el código: una llamada a través de todas las capas](#10-recorrido-por-el-código-una-llamada-a-través-de-todas-las-capas)
11. [Problemas frecuentes](#11-problemas-frecuentes)
12. [Lista del día de la charla](#12-lista-del-día-de-la-charla)

---

## 1. Requisitos

Versiones con las que se construyó y probó el proyecto (2026-09-30). Las librerías quedan fijadas en
`backend/uv.lock`, `web/package-lock.json` y `android/gradle/libs.versions.toml`.

| Herramienta | Versión | Para qué |
| --- | --- | --- |
| macOS | con `say` y voz `Paulina` | Solo para regenerar las grabaciones de voz (`android/testdata/voice/generate.sh`) |
| Python | 3.13 (`backend/.python-version`) | Backend |
| uv | 0.8 (probado con 0.8.22) | Entorno y dependencias de Python (`uv run` las instala solo) |
| Node.js / npm | 24 (probado con 24.21.0 / npm 12.0.2; la imagen usa `node:24-slim`) | Web |
| JDK | 17 (`jvmToolchain(17)`) | Android |
| Android SDK | compileSdk/targetSdk 37, minSdk 31; Gradle 9.8.0 (wrapper), AGP 9.4.1 | Android |
| Imagen del emulador | `system-images;android-36.1;google_apis_playstore;arm64-v8a` (r4) | AppFunctions (`cmd app_function` existe desde API 36.1) y FCM (necesita Play) |
| Google Cloud SDK | probado con 587.0.0 | ADC, `infra/*.sh`, Cloud Build, Cloud Run |
| Firebase CLI | probado con 15.6.0 | `make -C android google-services` |
| Google Chrome | 150 o más (probado con 154.0.8037.59) | WebMCP y la vista del presentador |
| ffmpeg | cualquiera reciente | Solo para regenerar las grabaciones de voz |
| scrcpy | opcional | Solo si se quiere el espejo píxel a píxel del teléfono |

`make test` corre las pruebas de Android, así que también necesita JDK 17 y el Android SDK.

## 2. Preparación local

```bash
git clone <repo> escudo && cd escudo
cp .env.example .env                 # variables; ver la sección 3 para los secretos
cd backend && uv sync && cd ..       # opcional: `uv run` lo hace la primera vez
cd web && npm ci && cp .env.example .env.local && cd ..
```

- `.env` (raíz, no se versiona): proyecto, modelos (`GEMINI_MODEL`, `GEMINI_LIVE_MODEL`,
  `GEMINI_LIVE_LOCATION`, `IMAGE_MODEL`), base de datos y bucket, `A2A_PEERS`, modos `MOCK_AI` /
  `MOCK_AUTH`, `ADMIN_TOKEN` y `FIREBASE_WEB_API_KEY`. Cada variable está explicada en `.env.example`.
  Ningún modelo está escrito en el código: todos salen de aquí.
- `.env.local` (raíz, no se versiona): lo escribe `make seed` con el correo y la contraseña de la clienta.
  El `Makefile` incluye `.env` y `.env.local`.
- `web/.env.local` (no se versiona): configuración pública del portal (`NEXT_PUBLIC_ESCUDO_API_URL`,
  `NEXT_PUBLIC_FIREBASE_API_KEY`, …) y, para el feed del presentador en el servidor, `GOOGLE_CLOUD_PROJECT`
  y `FIRESTORE_DATABASE`.
- `android/local.properties` (no se versiona): ver la [sección 8](#8-emulador-android).

Comprobación rápida sin nube:

```bash
make up-mock     # terminal 1
make scenario    # terminal 2: debe terminar con «aprobaciones: 3 (esperadas 3) · OK»
```

## 3. Credenciales y ADC

El backend y los scripts usan Application Default Credentials; no hay llaves de cuenta de servicio en el repo.

```bash
gcloud auth login
gcloud auth application-default login
gcloud auth application-default set-quota-project ai-experiments-487722
```

- `GOOGLE_CLOUD_QUOTA_PROJECT=ai-experiments-487722` (en `.env`) hace que las llamadas de administración
  de Firebase Auth de `make seed` se facturen al proyecto; con ADC de usuario sin proyecto de cuota fallan.
- `infra/env.sh` exporta `CLOUDSDK_CORE_PROJECT`, así que los scripts no tocan tu configuración global de gcloud.
- Secretos que se generan solos y viven en `infra/.generated/` (no se versiona): `admin_token`
  (protege `POST /admin/reset`) y `a2a_secret` (Escudo ↔ socios). Los crea `infra/10-deploy-api.sh` la
  primera vez.
- `FIREBASE_WEB_API_KEY`: la llave web de Firebase (no es secreta, pero no se versiona). Hace falta para
  `make scenario` contra la nube y para `make deploy-web`:

  ```bash
  gcloud services api-keys get-key-string b6c3370b-e8d5-4007-81fc-087e18079c0d --project ai-experiments-487722
  ```

## 4. Firebase y la clienta sembrada

Escudo usa el proyecto de Firebase `ai-experiments-487722` solo como SDKs:

| Servicio | Uso |
| --- | --- |
| Auth | Correo y contraseña (la clienta sembrada, en la app Android y el portal) y **anónimo** (botón «Entrar con la cuenta de demostración» del portal y `make scenario` cuando no hay credenciales). Ambos proveedores deben estar activos en la consola de Firebase |
| Firestore | Base con nombre `escudo` (modo nativo, `nam5`). Colecciones: `customers`, `policies`, `repair_shops`, `tow_providers`, `claims`, `approvals`, `events`, `devices` |
| Cloud Storage | Bucket `ai-experiments-487722-escudo-claims` (`us-central1`): fotos en `claims/{claim_id}/{n}.jpg` |
| FCM | Push de aprobación al canal `approvals` de la app |
| App Android | `app.escudo.android` (`1:611681112050:android:cecc13cdf49afe50f6e56b`) |

**Sembrar** (una vez, y cada vez que quieras restaurar la póliza sin borrar reclamos):

```bash
make seed
```

`python -m escudo.admin seed` hace dos cosas:

1. Escribe en Firestore la clienta `cus_demo` (Andrea Morales), la póliza `POL-GT-2027-0042` (Toyota
   Corolla 2022, placa P-512KLM; daños propios, terceros, grúa y auto de reemplazo 7 días; deducible
   USD 500), tres talleres con horarios en los próximos días hábiles y dos grúas. Todo es sintético y
   lleva `origin`.
2. Crea o actualiza el usuario de Firebase Auth `cus_demo_login` (`andrea.morales@example.com`) con el
   *custom claim* `customer_id=cus_demo`, y escribe correo y contraseña en `.env.local`. La contraseña se
   conserva entre corridas para que la app instalada siga funcionando.

Cualquier otro usuario verificado de Firebase (por ejemplo, uno anónimo) actúa como la misma clienta
(`DEMO_CUSTOMER_ID`, ver `backend/escudo/auth.py`): Escudo es una demo de una sola clienta.

El servicio desplegado además siembra una base vacía al arrancar (`seed.seed_if_empty`).

**Reiniciar** la demo (borra reclamos, aprobaciones y eventos y vuelve a sembrar; los dispositivos
registrados se conservan para que el teléfono no tenga que volver a registrarse):

```bash
make reset CLOUD=1    # servicio desplegado (usa infra/.generated/admin_token)
make reset            # API local en ESCUDO_BASE_URL (con make up-mock el token es local-admin:
                      #   make reset ADMIN_TOKEN=local-admin si tu .env tiene otro)
```

## 5. Google Cloud con `infra/`

Proyecto `ai-experiments-487722`, región `us-central1`, repositorio de Artifact Registry `charlas2027`.
Todos los scripts cargan `infra/env.sh` (que lee `infra/app.env`, `APP=escudo`) y son idempotentes.

Recursos que los scripts usan y que se crean una sola vez a mano (ya existen en el proyecto):

```bash
gcloud firestore databases create --database=escudo --location=nam5 --type=firestore-native --project ai-experiments-487722
gcloud storage buckets create gs://ai-experiments-487722-escudo-claims --location=us-central1 --project ai-experiments-487722
gcloud artifacts repositories create charlas2027 --repository-format=docker --location=us-central1 --project ai-experiments-487722
```

Además, en la consola de Firebase: proveedores de Auth *Correo/contraseña* y *Anónimo*, y la app Android
`app.escudo.android` registrada.

En orden:

| Paso | Script | Qué hace |
| --- | --- | --- |
| 1 | `bash infra/01-apis.sh` | Activa Cloud Run, Cloud Build, Artifact Registry, Vertex AI, Firestore, Storage, FCM, Identity Toolkit e IAM |
| 2 | `bash infra/02-service-account.sh` | Cuenta `escudo-api` con lo mínimo: `datastore.user` solo sobre la base `escudo` (condición IAM), `aiplatform.user`, `firebasecloudmessaging.admin` y `storage.objectUser` solo sobre el bucket de reclamos |
| 3 | `bash infra/03-web-service-account.sh` | Cuenta `escudo-web`: `datastore.viewer` solo sobre la base `escudo` |
| 4 | `make seed` | Datos y login de la clienta (sección 4) |
| 5 | `make deploy-api` (`infra/10-deploy-api.sh`) | Prepara un contexto con `backend/` + `contracts/`, construye con Cloud Build y despliega `escudo-api`: `--min-instances=1 --max-instances=1 --concurrency=80 --cpu=1 --memory=1Gi --no-cpu-throttling --timeout=3600 --session-affinity`. Genera `ADMIN_TOKEN` y `A2A_SHARED_SECRET` en `infra/.generated/` y fija `PUBLIC_BASE_URL` y `A2A_PEERS` a la URL determinista del servicio |
| 6 | `make deploy-web` (`infra/11-deploy-web.sh`) | Escribe `.env.production` con la configuración pública (`NEXT_PUBLIC_*`), construye y despliega `escudo-web` (`--min-instances=1 --max-instances=2`, 3600 s de timeout para el stream SSE) |

`make deploy` corre los pasos 5 y 6. Los modelos y datos que se pasan al servicio salen de
`.env.example` y, encima, de `.env`.

URLs resultantes: ver la tabla «URLs desplegadas» del README.

## 6. Correr en local o en la nube

| Modo | Backend | Datos y modelos | Para qué |
| --- | --- | --- | --- |
| **Sin nube** | `make up-mock` | En memoria; perito y socios deterministas; voz responde eco y 100 ms de silencio; cualquier llamador es la clienta | Desarrollo, pruebas, ensayar sin red |
| **Local con nube** | `make up` | Firestore `escudo`, Storage, Gemini y FCM reales con tu ADC; tokens de Firebase verificados | Depurar el backend con los servicios reales |
| **Nube** | `make deploy` | Lo mismo, en Cloud Run | La charla |

Notas:

- `make up` y el servicio desplegado comparten la misma base de datos: lo que hagas en local aparece en
  el presentador desplegado.
- Con `make up`, `PUBLIC_BASE_URL` y `A2A_PEERS` apuntan a `127.0.0.1:8080`, así que el agente Escudo
  descubre a sus socios en tu propio proceso, por HTTP, igual que en la nube.
- `make scenario` usa `ESCUDO_BASE_URL` (por defecto local; `CLOUD=1` para el desplegado). Con
  `FIREBASE_WEB_API_KEY` en `.env` inicia sesión como la clienta sembrada (o anónima si no hay
  credenciales en `.env.local`). `--auto-approve` solo se acepta junto con `--test-mode`: en una corrida
  real aprueba la clienta. `APPROVE_DELAY=4` deja la pausa en pantalla 4 s.
- `make scenario-live` es el mismo guion, pero espera a que apruebes en el teléfono (sondea `GET /approvals`).
- Web local: `make web` levanta el portal en `http://localhost:3217`. `/presenter` lee Firestore en el
  servidor con tu ADC; `/presenter?demo=1` reproduce una corrida grabada
  (`web/src/lib/fixtures/scenario-events.json`) sin nube.
- App contra backend local: sabor `live` con `escudo.apiUrl=http://10.0.2.2:8080` y
  `escudo.mockAuth=true` en `local.properties` (solo debug permite HTTP a `10.0.2.2`). O el sabor `fake`
  (`make android-install FLAVOR=fake`), que no usa red.
- Hablar con el asistente sin teléfono (texto por el mismo socket):

  ```bash
  cd backend && uv run --env-file ../.env python scripts/assistant_probe.py \
    --base-url https://escudo-api-611681112050.us-central1.run.app "¿Qué cubre mi póliza?"
  ```

## 7. Pruebas

```bash
make test
```

| Parte | Comando | Qué cubre |
| --- | --- | --- |
| Backend | `cd backend && MOCK_AI=1 MOCK_AUTH=1 uv run pytest -q` (33 pruebas) | Toda acción con aprobación es rechazada sin `approval_id` válido, por REST y MCP (`test_approvals.py`); aprobación de otra herramienta, otros argumentos, rechazada o ya usada; no existe herramienta de aprobar; la tabla de autonomía cubre exactamente el registro y coincide con el contrato (`test_autonomy.py`); Agent Cards en la ruta well-known, descubrimiento sin rutas escritas a mano y secreto de socios (`test_discovery.py`); eventos válidos contra el esquema y etiquetas de superficie (`test_events.py`); `make reset` y el protocolo de voz simulado (`test_reset_and_voice.py`); el escenario completo por MCP con 3 aprobaciones (`test_scenario.py`) |
| Android | `make android-test` (54 pruebas, sabores `fake` y `live`) | Mapeo de AppFunctions, `ApprovalFlow`, protocolo del asistente, lector WAV, repositorio, cliente HTTP, push, línea de tiempo, transcripción, mensajes de error |
| Web | `cd web && npm test` (14 pruebas) | Línea de tiempo del presentador y herramientas WebMCP (exactamente cuatro, todas de solo lectura, ninguna aprueba) |
| Web e2e | `cd web && npm run e2e` | Presentador en modo demo con Chrome: pausa en ámbar, 3 aprobaciones, el reloj se detiene al firmar |
| WebMCP real | `cd web && E2E_WEBMCP=1 npm run e2e` | Chrome con el flag de WebMCP, contra el API desplegado: el portal registra cuatro herramientas y responden |
| Voz contra la nube | `make -C android live-test` (emulador) | `01_choque.wav` por `FileSource` → Live transcribe, llama una herramienta y responde con audio |
| Escenario por MCP | `make scenario CLOUD=1` | El flujo completo contra el servicio desplegado, cronometrado (< 180 s, 3 aprobaciones) |
| Escenario por voz | `make voice-scenario` | El flujo completo por voz en el emulador, con huella (sección 8) |

Las pruebas que necesitan nube están fuera de `make test`; `make test` corre sin red.

## 8. Emulador Android

### 8.1 Crear el AVD (una vez)

```bash
sdkmanager "system-images;android-36.1;google_apis_playstore;arm64-v8a"
avdmanager create avd -n Pixel_9_API_36_1 -k "system-images;android-36.1;google_apis_playstore;arm64-v8a" -d pixel_9
```

Hace falta la imagen 36.1 (Android 16 QPR2): en 36.0 el servicio de AppFunctions existe pero
`adb shell cmd app_function` no. La variante *Play* trae Google Play Services, necesario para FCM.

### 8.2 Arrancarlo

`make voice-scenario` habla con `emulator-5556` por defecto (`ANDROID_SERIAL`). Arranca el emulador en ese puerto:

```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_9_API_36_1 -port 5556 -no-snapshot-save &
```

`make android-emulator` también lo arranca, en el primer puerto libre (`emulator-5554` si es el único);
en ese caso corre `ANDROID_SERIAL=emulator-5554 make voice-scenario`. Para usar el micrófono de la laptop
agrega `-allow-host-audio` (capta muy poco; para ensayar usa las grabaciones).

### 8.3 PIN y huella (una vez)

```bash
adb shell locksettings set-pin 1234
adb shell am start -a android.settings.FINGERPRINT_ENROLL
# en la pantalla de registro, toca el sensor virtual hasta «Fingerprint added»:
adb emu finger touch 1     # repetir varias veces
```

`BiometricVerifier` acepta biometría fuerte con el PIN como respaldo; `voice_scenario.sh` aprueba
tocando el sensor con `adb emu finger touch 1`.

### 8.4 `google-services.json` y `local.properties`

```bash
make -C android google-services      # firebase apps:sdkconfig ANDROID … -o app/google-services.json
```

Sin ese archivo la app compila igual, pero solo sirve el sabor `fake` (sin Auth ni FCM).

`android/local.properties` (copia correo y contraseña de `.env.local`, que escribe `make seed`):

```properties
sdk.dir=/Users/<tú>/Library/Android/sdk
escudo.apiUrl=https://escudo-api-611681112050.us-central1.run.app
escudo.customerEmail=andrea.morales@example.com
escudo.customerPassword=<ESCUDO_CUSTOMER_PASSWORD de .env.local>
# Solo para un backend local con MOCK_AUTH=1 (make up-mock), desde el emulador:
# escudo.apiUrl=http://10.0.2.2:8080
# escudo.mockAuth=true
```

También se aceptan las variables `ESCUDO_API_URL`, `ESCUDO_CUSTOMER_EMAIL`, `ESCUDO_CUSTOMER_PASSWORD`
y `ESCUDO_MOCK_AUTH`. La URL de voz se deriva de `escudo.apiUrl` (`wss://…/assistant/live`).

### 8.5 Instalar y preparar

```bash
make android-install                                   # sabor live, abre la app
adb shell pm grant app.escudo.android android.permission.POST_NOTIFICATIONS
adb shell pm grant app.escudo.android android.permission.READ_MEDIA_IMAGES   # reportAccident con recentPhotoCount
adb shell pm grant app.escudo.android android.permission.RECORD_AUDIO
```

Al abrirla la primera vez, la app inicia sesión sola con la clienta, registra su token FCM
(`POST /devices`) y pide permiso de notificaciones. En la charla no hay pantalla de login.

Las tres fotos de ensayo en la galería (las usa el reporte de `make voice-scenario`):

```bash
adb push backend/samples/photos/crash_1.jpg backend/samples/photos/crash_2.jpg backend/samples/photos/crash_3.jpg /sdcard/Pictures/
adb shell content call --uri content://media --method scan_volume --arg external_primary
```

### 8.6 Escenario por voz: `make voice-scenario`

```bash
make reset CLOUD=1
make voice-scenario            # o ANDROID_SERIAL=emulator-5554 make voice-scenario
```

`android/scripts/voice_scenario.sh`:

1. Reporta el accidente desde la app con las tres fotos más recientes de la galería y espera la evaluación.
2. Hace que el asistente «escuche» `01_choque`, `02_taller`, `04_acepto` y `05_firma`
   (`--es voice_input_file <nombre>`), en tiempo real, por el mismo camino que el micrófono.
3. Cada vez que el asistente pide aprobación, abre `escudo://approvals`, toca «Aprobar con huella» y
   toca el sensor virtual hasta que la aprobación pasa.
4. Termina cuando la pantalla Reclamo dice «Reclamo firmado y enviado».

No duerme tiempos fijos: espera los marcadores de logcat `EscudoScenario` (`report_done`, `voice_end`,
`assistant_idle`, `approval_required`, `approval_outcome`). Medido el 2026-09-30: 168 s de punta a punta,
3 aprobaciones. Las capturas de una corrida están en `android/docs/screenshots/voice/`.

Otras formas de hacer hablar al asistente (solo debug):

```bash
adb shell am start -n app.escudo.android/.MainActivity --es voice_input_file 01_choque
```

o mantener presionado el título «Asistente Escudo» para elegir una grabación, o el ícono de teclado para escribir.

## 9. Mostrar AppFunctions y WebMCP

### AppFunctions con `adb`

Gemini con AppFunctions de terceros está en vista previa privada y no está disponible para esta app,
así que en el escenario se muestran con las herramientas de Android (Android 16 QPR2+):

```bash
make -C android appfunctions     # lista los tres ids
F=app.escudo.android.appfunctions.BaseEscudoAppFunctionService

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#getCoverage' --parameters '{\"incidentType\": \"collision\"}' --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#reportAccident' \
  --parameters '{\"description\": \"Me chocaron por detrás en un semáforo\", \"recentPhotoCount\": 1, \"locationLabel\": \"Calzada Roosevelt, zona 11\"}' \
  --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#getClaimStatus' --parameters '{}' --brief-yaml"
```

Cada llamada viaja con `X-Escudo-Surface: appfunctions`, así que aparece en `/presenter` con su insignia.
Para mostrar que una función se puede apagar:
`adb shell cmd app_function set-enabled --package app.escudo.android --function "$F#reportAccident" --state disable`
(y `--state default` para volver). Más detalle en [`android/APPFUNCTIONS.md`](../android/APPFUNCTIONS.md).

### WebMCP en Chrome

1. Una vez: `chrome://flags/#enable-webmcp-testing` → **Enabled** → relanzar Chrome (150 o más).
2. Abrir https://escudo-web-611681112050.us-central1.run.app/entrar y pulsar **Entrar con la cuenta de
   demostración**. El pie del portal dice «WebMCP · 4 herramientas disponibles…».
3. DevTools (`⌥⌘I`) → **Application** → **WebMCP**: aparecen `getCoverage`, `getDeductible`,
   `findRepairShops` y `getClaimStatus` con su descripción y esquema. Elegir `getDeductible` → **Run tool**
   → `{"deductible_usd": 500, …}`; `/presenter` muestra el paso con la insignia **WebMCP**.

## 10. Recorrido por el código: una llamada a través de todas las capas

Seguimos `accept_deductible` en el escenario por voz: la primera llamada termina en una pausa; la
segunda, tras la huella, se ejecuta.

**Teléfono → servidor (voz)**

1. `android/.../assistant/VoiceSource.kt`: `MicrophoneSource` (o `FileSource` con `04_acepto.wav`) entrega
   PCM16 16 kHz en fragmentos de 40 ms.
2. `assistant/LiveAssistantSession.kt` los envía como frames binarios por el WebSocket
   `wss://…/assistant/live` con `Authorization: Bearer <ID token>`; al soltar el botón agrega 2 s de silencio
   (`AssistantProtocol.TRAILING_SILENCE`) para que la VAD cierre el turno.
3. `backend/escudo/adapters/assistant_live.py`: `assistant_live()` verifica el token
   (`auth.Authenticator.verify`), arma un `Runner` de ADK con `build_assistant_agent()` y pasa el audio a un
   `LiveRequestQueue`. `GEMINI_LIVE_MODEL` corre en `GEMINI_LIVE_LOCATION`.

**Agente → herramienta (MCP)**

4. Gemini Live decide llamar `accept_deductible(claim_id)`. El agente no tiene acceso directo al dominio:
   su `McpToolset` llama por HTTP a `http://127.0.0.1:{PORT}/mcp` con el token de la clienta y
   `X-Escudo-Actor: escudo_assistant`.
5. `adapters/mcp_server.py`: `FirebaseTokenVerifier` valida el bearer; la herramienta `accept_deductible`
   llama `invoke()` → `caller_for()` arma el `Caller` (superficie `mcp`, actor `escudo_assistant`).

**La única puerta**

6. `gateway.call_capability`: busca la capacidad en `capabilities.REGISTRY`, valida la entrada con su modelo
   Pydantic, escribe el evento `tool_call` y corre el *precheck*
   (`capabilities/accept_deductible.py`: hay evaluación y el deducible no estaba aceptado).
7. `autonomy.enforce`: la regla es `NEEDS_APPROVAL` y no hay `approval_id`, así que llama
   `describe_approval` («Aceptar pagar el deducible de USD 500 del reclamo …») y
   `approvals.request`, que guarda `approvals/{id}` con `status: pending`, `tool` y
   `args_hash = sha256({tool, args})`. **No cambia nada del reclamo.**
8. De vuelta en el gateway: evento `approval_requested` (actor `backend`) y
   `push.FcmPush.approval_requested` envía el push a los dispositivos de la clienta. La respuesta es
   `{"status": "pending_approval", "approval_id": "apr_…", …}`.

**El servidor le cuenta al teléfono**

9. `assistant_live._to_client` ve la respuesta de la función, envía `tool_result` y, como contiene
   `pending_approval`, `approval_required` al teléfono; el asistente dice que le llegó una notificación.
10. En el teléfono, `push/EscudoMessagingService.kt` recibe el FCM y `ApprovalNotifications` muestra la
    notificación con el enlace `escudo://approvals/{approval_id}`. La pantalla del asistente muestra la
    banda ámbar «Tu asistente espera tu aprobación».

**La decisión humana**

11. `ui/approvals/ApprovalsScreen.kt` → `approvals/ApprovalFlow.approve()` → `BiometricVerifier`
    (`BiometricPrompt`, biometría fuerte o PIN). Solo si pasa, `EscudoRepository.approve()` →
    `POST /approvals/{id}/approve` con `{"device_id": …}`.
12. `adapters/rest.py` `_decide()` → `approvals.decide()` cambia `pending → approved` (transacción de
    Firestore), escribe `approval_granted` y llama `AssistantHub.notify()`, que mete en la sesión de voz
    abierta: «[Aviso de Escudo] La clienta aprobó accept_deductible … approval_id=…».

**El reintento**

13. El asistente repite `accept_deductible(claim_id, approval_id)` por MCP. En `autonomy.enforce`, ahora
    `approvals.redeem()` comprueba, dentro de una transacción: misma clienta, misma herramienta, mismo
    `args_hash`, estado `approved`; y marca `used`.
14. `capabilities/accept_deductible.run()` actualiza el reclamo (`deductible_accepted`, estado
    `deductible_accepted`); el gateway escribe `tool_result` con `approval_id`.

**El presentador**

15. Cada evento quedó en Firestore `events`. `web/src/lib/server/presenterFeed.ts` lo recibe por
    `onSnapshot` y `app/api/presenter/stream/route.ts` lo envía por SSE; `PresenterView` lo dibuja y
    `PhoneMirror` cambia de «Aprobación» al estado del reclamo.

El mismo recorrido, desde el paso 6, aplica a cualquier superficie: REST (`adapters/rest.py`), MCP externo,
el agente A2A (`agents/escudo_agent.build_a2a_agent`) o una AppFunction (que llama REST con
`X-Escudo-Surface: appfunctions`).

## 11. Problemas frecuentes

| Síntoma | Causa y solución |
| --- | --- |
| `RuntimeError: GEMINI_MODEL is not set (or run with MOCK_AI=1)` | Falta `.env` o la variable. `cp .env.example .env`, o usa `make up-mock` |
| `make seed` falla con un error de cuota o de proyecto en Identity Toolkit | ADC de usuario sin proyecto de cuota: `gcloud auth application-default set-quota-project ai-experiments-487722` (o `GOOGLE_CLOUD_QUOTA_PROJECT` en `.env`) |
| `make reset` → `reset falló: HTTP 401` | Token equivocado. Local con `make up-mock`: `make reset ADMIN_TOKEN=local-admin`. Nube: `make reset CLOUD=1` (lee `infra/.generated/admin_token`; si no existe, aún no despliegas desde esta máquina) |
| `make reset` → `HTTP 000` | El API no está arriba en `ESCUDO_BASE_URL` |
| `approval_invalid (wrong_args)` | El agente reintentó con otros argumentos (por ejemplo, otro horario). Hay que pedir una nueva aprobación; la anterior no se quemó |
| `approval_invalid (not_approved)` | El agente reintentó antes de que la clienta aprobara. Esperar el aviso |
| La voz no cierra el turno | Usa push-to-talk (al soltar se envían 2 s de silencio) o el teclado. Las grabaciones propias deben ser WAV PCM16 LE, 16 kHz, mono |
| El asistente responde «model not found» o se cae al conectar | `GEMINI_LIVE_LOCATION` debe ser `us-central1`; `global` no sirve para Live |
| No llega el push de aprobación | La app no registró su token (abrirla una vez con sesión iniciada), falta `google-services.json`, falta el permiso de notificaciones, o el emulador no es una imagen *Play*. Mientras tanto, la pestaña Aprobaciones también muestra la pendiente |
| «Ningún socio disponible ofrece ese servicio» (`peer_unavailable`) | `A2A_PEERS` no apunta a las Agent Cards (`{base}/.well-known/agent-card.json`) o el secreto `X-Escudo-A2A-Key` no coincide |
| `adb shell cmd app_function` → `No shell command implementation.` | Imagen API 36.0. Usa `Pixel_9_API_36_1` (36.1) |
| `reportAccident` por AppFunction: «Escudo cannot read the gallery yet» | `adb shell pm grant app.escudo.android android.permission.READ_MEDIA_IMAGES` |
| `make voice-scenario` → «not on screen» o no encuentra el dispositivo | El emulador no está en `emulator-5556`: `ANDROID_SERIAL=emulator-5554 make voice-scenario`. Revisa también que haya tres fotos en la galería y que la app esté instalada con sesión iniciada |
| `make voice-scenario` → «no biometric prompt» o «approval not granted» | No hay huella registrada (sección 8.3) |
| El portal dice «WebMCP no está activo en este navegador» | Activa `chrome://flags/#enable-webmcp-testing` y relanza Chrome 150+ |
| `/presenter` vacío en local | `make web` lee Firestore con tu ADC; usa `/presenter?demo=1` sin nube |
| La evaluación de fotos tarda mucho | Normal en la primera llamada fría; el perito reintenta tras 45 s. Usa las fotos de ensayo o fotos ya reducidas por la app |

## 12. Lista del día de la charla

**La víspera**

- [ ] `make test` en verde.
- [ ] `curl https://escudo-api-611681112050.us-central1.run.app/health` → `{"status":"ok"}`; Agent Cards responden.
- [ ] `make reset CLOUD=1` y `make scenario CLOUD=1 APPROVE_DELAY=4`: «aprobaciones: 3 (esperadas 3) · OK».
- [ ] Teléfono: app `live` instalada, sesión iniciada, notificaciones permitidas, huella registrada,
      `READ_MEDIA_IMAGES` concedido, fotos del choque en la galería.
- [ ] Un push de prueba llega al teléfono (correr `make scenario-live` y aprobar una vez).
- [ ] Chrome: flag de WebMCP activo; portal con sesión; DevTools → Application → WebMCP lista 4 herramientas.
- [ ] Emulador de respaldo listo (`make voice-scenario` completo una vez).

**Antes de subir**

- [ ] `make reset CLOUD=1` (< 10 s).
- [ ] `/presenter` abierto en Chrome a pantalla completa en el proyector; «Aprobaciones 0 de 3».
- [ ] Teléfono cargado, en no molestar salvo Escudo, pantalla del Asistente Escudo abierta.
- [ ] Terminal con los comandos `adb … cmd app_function` listos (sección 9).
- [ ] Si se quiere el espejo exacto: ventana `scrcpy --window-title "Escudo" --always-on-top` sobre el panel del teléfono.

**Si algo falla en vivo**

- Sin voz: el ícono de teclado en el asistente usa el mismo socket.
- Sin teléfono: `make scenario CLOUD=1 APPROVE_DELAY=4` reproduce el flujo por MCP y el presentador lo muestra igual.
- Sin red: `/presenter?demo=1` reproduce una corrida grabada.
- Estado sucio: `make reset CLOUD=1`.
