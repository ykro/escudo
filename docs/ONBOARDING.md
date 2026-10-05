# Escudo — guía de incorporación

Todo lo necesario para correr, probar, desplegar y ensayar Escudo desde cero. Para el panorama
(qué enseña, arquitectura, decisiones) lee primero el [README](../README.md), [ARQUITECTURA.md](ARQUITECTURA.md) y [DECISIONES.md](DECISIONES.md). Los contratos
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
| Python | 3.13 (`backend/.python-version`) | Backend |
| uv | 0.8 (probado con 0.8.22) | Entorno y dependencias de Python (`uv run` las instala solo) |
| Node.js / npm | 24 (probado con 24.21.0 / npm 12.0.2; la imagen usa `node:24-slim`) | Web |
| JDK | 17 (`jvmToolchain(17)`) | Android |
| Android SDK | compileSdk/targetSdk 37, minSdk 31; Gradle 9.8.0 (wrapper), AGP 9.4.1 | Android |
| Imagen del emulador | `system-images;android-36.1;google_apis_playstore;arm64-v8a` (r4) | AppFunctions (`cmd app_function` existe desde API 36.1) y FCM (necesita Play) |
| Google Cloud SDK | probado con 587.0.0 | ADC, `infra/*.sh`, Cloud Build, Cloud Run |
| Firebase CLI | probado con 15.6.0 | `make -C android google-services` |
| Google Chrome | 150 o más (probado con 154.0.8037.59) | WebMCP y la vista del presentador |
| scrcpy | opcional | Solo si se quiere el espejo píxel a píxel de la app en el proyector (se conecta al emulador) |

`make test` corre las pruebas de Android, así que también necesita JDK 17 y el Android SDK con
`sdk.dir` configurado (sección 2, antes de la primera prueba). En GitHub,
`.github/workflows/test.yml` corre el mismo `make test` en cada push a `main` y en cada PR (modos
simulados, sin nube, con caché de uv, npm y Gradle).

## 2. Preparación local

```bash
git clone <repo> escudo && cd escudo
cp .env.example .env                 # variables; ver la sección 3 para los secretos
cd backend && uv sync && cd ..       # opcional: `uv run` lo hace la primera vez
cd web && npm ci && cp .env.example .env.local && cd ..
```

- `.env` (raíz, no se versiona): proyecto, modelos (`GEMINI_MODEL`, `IMAGE_MODEL`), base de datos y bucket, `A2A_PEERS`, modos `MOCK_AI` /
  `MOCK_AUTH`, `ADMIN_TOKEN` y `FIREBASE_WEB_API_KEY`. Cada variable está explicada en `.env.example`.
  Ningún modelo está escrito en el código: todos salen de aquí.
- `.env.local` (raíz, no se versiona): lo escribe `make seed` con el correo y la contraseña de la clienta.
  El `Makefile` incluye `.env` y `.env.local`.
- `web/.env.local` (no se versiona): configuración pública del portal (`NEXT_PUBLIC_ESCUDO_API_URL`,
  `NEXT_PUBLIC_FIREBASE_API_KEY`, …) y, para el feed del presentador en el servidor, `GOOGLE_CLOUD_PROJECT`
  y `FIRESTORE_DATABASE`.
- `android/local.properties` (no se versiona): `make android-prep` lo escribe completo desde `.env.local` ([sección 8.3](#83-dejarlo-listo-make-android-prep)).

**Android SDK antes de `make test`.** Gradle necesita saber dónde está el SDK. Con Android Studio
instalado basta con:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties   # o exporta ANDROID_HOME
```

Comprobación rápida sin nube:

```bash
make up-mock          # terminal 1 (ADMIN_TOKEN=local-admin por defecto)
make reset            # terminal 2: «reset OK» (mismo ADMIN_TOKEN que up-mock)
make scenario         # «aprobaciones: 3 (esperadas 3) · OK»
make chat-scenario    # el mismo flujo por chat, con el asistente simulado: «… · OK»
make test             # backend, Android y web
```

`ADMIN_TOKEN` tiene un solo valor por defecto (`local-admin`) en el `Makefile` y en `.env.example`, así
que `make up-mock`, `make up`, `make reset`, `make scenario` y `make chat-scenario` coinciden sin
configurar nada. Si cambias `ADMIN_TOKEN` en `.env`, cambia para todos a la vez.

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
- Secretos en Secret Manager, montados por Cloud Run: `escudo-admin-token` (protege `POST /admin/reset`)
  y `escudo-a2a-secret` (Escudo ↔ socios). Los crea `infra/06-secrets.sh` con un valor aleatorio;
  `make reset CLOUD=1` lee el token de ahí. Nada de esto vive en el repo ni en variables de entorno.
- Llave web de Firebase propia de Escudo («Escudo web key», `infra/07-web-api-key.sh`): solo
  Identity Toolkit, Secure Token e Installations, y solo para los referrers de `escudo-web` y de la web
  local en `:3217`. Su valor vive en Secret Manager (`escudo-web-api-key`): `make deploy-web` la inyecta
  en el build y `make scenario|chat-scenario CLOUD=1` la leen de ahí. Para correr en local, cópiala a
  `FIREBASE_WEB_API_KEY` en `.env` y a `NEXT_PUBLIC_FIREBASE_API_KEY` en `web/.env.local`:

  ```bash
  gcloud secrets versions access latest --secret=escudo-web-api-key --project ai-experiments-487722
  ```

  Los corredores de escenario inician sesión por REST y mandan `Referer: https://escudo-web-…/` para
  pasar ese filtro. El filtro de referrers solo evita el reuso casual de una llave que todo navegador
  recibe (cualquiera puede mandar ese encabezado); no es una frontera de seguridad. Lo que Escudo sí
  aplica está en el servidor: cada llamada actúa como el `customer_id` del ID token verificado y aprobar
  exige la firma del teléfono.

## 4. Firebase y la clienta sembrada

Escudo usa el proyecto de Firebase `ai-experiments-487722` solo como SDKs:

| Servicio | Uso |
| --- | --- |
| Auth | Correo y contraseña: la clienta sembrada, en la app Android, el portal y `make scenario`. `infra/03-firebase.sh` activa el proveedor por la API de administración de Identity Toolkit |
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

Cada superficie (REST, MCP, el chat y el agente A2A de Escudo) actúa como la clienta que dice el token
verificado (`customer_id`, ver `backend/escudo/auth.py`). Un usuario sin ese claim es su propio cliente:
no ve póliza ni reclamos. Solo con `MOCK_AUTH=1` (pruebas locales) no se verifica nada: un bearer
`mock-customer:<customer_id>` (el que devuelve `POST /admin/runs` en ese modo) actúa como ese cliente y
cualquier otro llamador es `DEMO_CUSTOMER_ID`.

El servicio desplegado además siembra una base vacía al arrancar (`seed.seed_if_empty`).

**Reiniciar** la demo (borra reclamos, aprobaciones y eventos y vuelve a sembrar; los dispositivos
registrados se conservan para que el emulador no tenga que volver a registrarse):

```bash
make reset CLOUD=1    # servicio desplegado (lee escudo-admin-token de Secret Manager)
make reset            # API local en ESCUDO_BASE_URL (mismo ADMIN_TOKEN que make up-mock / make up)
```

## 5. Google Cloud con `infra/`

Proyecto `ai-experiments-487722`, región `us-central1`, repositorio de Artifact Registry `charlas2027`.
Todos los scripts cargan `infra/env.sh` (que lee `infra/app.env`, `APP=escudo`) y son idempotentes.

Desde un proyecto vacío, `make infra` corre los pasos 1 a 7 en orden; cada script crea lo que falta y
deja igual lo que ya existe. La app Android `app.escudo.android` y su `google-services.json` los maneja
`android/` (`make -C android google-services`).

En orden:

| Paso | Script | Qué hace |
| --- | --- | --- |
| 1 | `bash infra/01-apis.sh` | Activa Cloud Run, Cloud Build, Artifact Registry, Vertex AI, Firestore, Storage, FCM, Identity Toolkit, Firebase e IAM |
| 2 | `bash infra/02-data.sh` | Crea si faltan la base Firestore `escudo` (nativa, `nam5`), el bucket privado `ai-experiments-487722-escudo-claims` y el repositorio de Artifact Registry `charlas2027` |
| 3 | `bash infra/03-firebase.sh` | Agrega Firebase al proyecto si falta y activa el proveedor de Auth correo/contraseña con la API de administración de Identity Toolkit (`PATCH admin/v2/projects/…/config`, solo ese campo: el proyecto se comparte con otras demos) |
| 4 | `bash infra/04-service-account.sh` | Cuenta `escudo-api` con lo mínimo: `datastore.user` solo sobre la base `escudo` (condición IAM), `aiplatform.user`, `firebasecloudmessaging.admin` y `storage.objectUser` solo sobre el bucket de reclamos |
| 5 | `bash infra/05-web-service-account.sh` | Cuenta `escudo-web`: `datastore.viewer` solo sobre la base `escudo` |
| 6 | `bash infra/06-secrets.sh` | Secretos `escudo-admin-token` y `escudo-a2a-secret` en Secret Manager; `escudo-api` solo puede leer esos dos |
| 7 | `bash infra/07-web-api-key.sh` | Llave web de Firebase «Escudo web key» (APIs y referrers restringidos) y su valor en el secreto `escudo-web-api-key` (sección 3) |
| 8 | `make seed` | Datos y login de la clienta (sección 4) |
| 9 | `make deploy-api` (`infra/10-deploy-api.sh`) | Prepara un contexto con `backend/` + `contracts/`, construye con Cloud Build y despliega `escudo-api`: `--max-instances=3 --concurrency=80 --cpu=1 --memory=1Gi --no-cpu-throttling --timeout=3600 --session-affinity`; `--min-instances` conserva el valor actual del servicio (0 si es nuevo; `MIN_INSTANCES=…` lo fuerza). Monta `ADMIN_TOKEN` y `A2A_SHARED_SECRET` desde Secret Manager (`--set-secrets`) y fija `PUBLIC_BASE_URL` y `A2A_PEERS` a la URL determinista del servicio |
| 10 | `make deploy-web` (`infra/11-deploy-web.sh`) | Escribe `.env.production` con la configuración pública (`NEXT_PUBLIC_*`, la llave web desde `escudo-web-api-key`), construye y despliega `escudo-web` (`--max-instances=2`, 3600 s de timeout para el stream SSE; `--min-instances` también conserva el valor actual) |

`make deploy` corre los pasos 9 y 10. Los modelos y datos que se pasan al servicio salen de
`.env.example` y, encima, de `.env`.

Los dos servicios escalan a cero cuando nadie los usa. El día de la charla, `make stage-on` deja una
instancia caliente de cada uno (`min-instances=1`, sin arranque en frío) y `make stage-off` los vuelve
a cero al terminar (sección 12). Un despliegue no cambia ese estado: `infra/10` e `infra/11` leen el
`min-instances` actual del servicio y lo vuelven a aplicar, así que desplegar la víspera después de
`make stage-on` deja la instancia caliente, y desplegar otro día deja el servicio en cero.

URLs resultantes: ver «URLs desplegadas» en [ARQUITECTURA.md](ARQUITECTURA.md#urls-desplegadas).

## 6. Correr en local o en la nube

| Modo | Backend | Datos y modelos | Para qué |
| --- | --- | --- | --- |
| **Sin nube** | `make up-mock` | En memoria; perito, socios y asistente del chat deterministas (`ScriptedAssistant`); tokens `mock-customer:<id>` sin verificar | Desarrollo, pruebas, `make scenario` y `make chat-scenario` sin red |
| **Local con nube** | `make up` | Firestore `escudo`, Storage, Gemini y FCM reales con tu ADC; tokens de Firebase verificados | Depurar el backend con los servicios reales |
| **Nube** | `make deploy` | Lo mismo, en Cloud Run | La charla |

Notas:

- `make up` y el servicio desplegado comparten la misma base de datos: lo que hagas en local aparece en
  el presentador desplegado.
- Con `make up`, `PUBLIC_BASE_URL` y `A2A_PEERS` apuntan a `127.0.0.1:8080`, así que el agente Escudo
  descubre a sus socios en tu propio proceso, por HTTP, igual que en la nube.
- `make scenario` usa `ESCUDO_BASE_URL` (por defecto local; `CLOUD=1` para el desplegado). Cada corrida
  pide su propio cliente con `POST /admin/runs` (token de admin: `ADMIN_TOKEN` en local, Secret Manager
  con `CLOUD=1`), inicia sesión con ese usuario de Firebase (`FIREBASE_WEB_API_KEY`), registra un
  dispositivo de prueba nuevo, manda `X-Escudo-Run-Id` e imprime `…/presenter?run=<id>`. Así dos
  corridas a la vez no se pisan, ni pisan a la clienta del escenario. `--auto-approve` solo se acepta
  junto con `--test-mode`: en una corrida real aprueba la clienta. `APPROVE_DELAY=4` deja la pausa en
  pantalla 4 s. `make reset` borra los clientes de corrida, sus usuarios y sus dispositivos.
- `make chat-scenario` hace lo mismo por el chat: con su propio cliente, abre el reclamo y sube las tres
  fotos por REST (como la app), y luego le escribe al asistente en `wss://…/assistant/chat`; aprueba las
  tres pausas con el dispositivo de prueba y el asistente sigue solo después de cada una (< 3 min). Contra
  `make up-mock` responde el `ScriptedAssistant`, que recorre los mismos pasos (grúa, talleres, auto,
  taller con mejor calificación, deducible, firma) en unos 6 s.
- El reclamo lo pone el servidor, no el modelo. En cada turno el chat le recuerda al asistente el reclamo
  activo de la clienta, y en `gateway.call_capability` cualquier llamada de un agente (no de la propia
  clienta) con un `claim_id` que no existe o no es suyo se corrige al reclamo abierto más reciente
  (`repository.active_claim`; queda en el log «agent claim_id … replaced by the active claim …»). Un id inventado como
  `clm_96051512` ya no rompe la corrida.
- Línea de tiempo coherente: cada evento lleva el `run_id` de la corrida (`X-Escudo-Run-Id`) o, si no
  viene, el de la clienta (`Caller.timeline_run_id`), así que una llamada suelta por MCP, WebMCP o AppFunctions
  con la misma clienta cae en la misma línea de tiempo. `/presenter` sin `?run` sigue la corrida del
  reclamo más reciente y no salta a otra por un evento suelto.
- Cada llamada al modelo del chat tiene un límite de 30 s (`CHAT_MODEL_TIMEOUT_SECONDS`, con reintentos):
  si Gemini se queda colgado, el turno reintenta en vez de detener el escenario.
- `make scenario-live` usa la clienta sembrada (`--shared`) y espera a que apruebes en la app del emulador
  (sondea `GET /approvals`).
- Web local: `make web` levanta el portal en `http://localhost:3217`. `/presenter` lee Firestore en el
  servidor con tu ADC; `/presenter?demo=1` reproduce una corrida real por chat grabada
  (`web/src/lib/fixtures/scenario-events.json`, corrida `run-6958m5`) sin nube, rotulada
  **REPETICIÓN GRABADA** («Reclamo grabado», teléfono «grabado»): nunca dice «en vivo».
- Respaldo sin red: `make up-offline` construye la web en modo producción y la sirve desde la laptop en
  `http://127.0.0.1:3217` (`web/scripts/serve-offline.sh`). Las fuentes son propias
  (`web/src/app/fonts/`), así que ni la página ni el build piden nada a internet; abre
  `/presenter?demo=1`. `web/e2e/offline.spec.ts` lo comprueba con toda petición externa bloqueada. El
  último respaldo es el video `docs/escudo-chat-run.mp4` (83 s: la misma corrida grabada, también rotulada REPETICIÓN GRABADA).
- Cuenta para el portal: correo y contraseña de Andrea están en `.env.local` (los escribe `make seed`).
  La pantalla de entrada no lo menciona a propósito: es la de una aseguradora, no la de un ensayo.
- App contra backend local: sabor `live` con `escudo.apiUrl=http://10.0.2.2:8080` y
  `escudo.mockAuth=true` en `local.properties` (solo debug permite HTTP a `10.0.2.2`). O el sabor `fake`
  (`make android-install FLAVOR=fake`), que no usa red.
- Hablar con el asistente sin la app: `make chat-scenario CLOUD=1` (o cualquier cliente WebSocket con
  `Authorization: Bearer <ID token>` y los frames de `contracts/tools.md`).

## 7. Pruebas

```bash
make test
```

| Parte | Comando | Qué cubre |
| --- | --- | --- |
| Backend | `cd backend && MOCK_AI=1 MOCK_AUTH=1 uv run pytest -q` (64 pruebas) | Toda acción con aprobación es rechazada sin `approval_id` válido, por REST y MCP (`test_approvals.py`); aprobación de otra herramienta, otros argumentos, rechazada o ya usada; no existe herramienta de aprobar; la tabla de autonomía cubre exactamente el registro y coincide con el contrato (`test_autonomy.py`); Agent Cards en la ruta well-known, descubrimiento sin rutas escritas a mano y secreto de socios (`test_discovery.py`); eventos válidos contra el esquema, etiquetas de superficie y eventos de clientes solo `message` sobre reclamos propios (`test_events.py`); MCP, REST y el agente Escudo actúan como el `customer_id` del token verificado y `/a2a/escudo` rechaza llamadas anónimas (`test_identity.py`); aprobar exige firma del dispositivo registrado de la clienta: sin dispositivo, dispositivo ajeno o desconocido, firma mala o para otra aprobación → 403 sin cambios, repetir → rechazado (`test_device_approvals.py`); chat: solo cabecera `Authorization`, herramientas por las capacidades, pausa en la aprobación y reanudación sola tras aprobar, la conversación sobrevive a una reconexión, el asistente simulado firma el reclamo con 3 aprobaciones y un agente no puede apuntar a un reclamo inventado (`test_chat.py`); los momentos de descubrimiento (Agent Card, `tools/list`) y las llamadas sueltas de la misma clienta quedan en la línea de tiempo de su reclamo (`test_events.py`); cada corrida es su propio cliente y `make reset` los borra sin tocar el dispositivo del escenario (`test_runs.py`); llamadas de respaldo del perito (`test_hedging.py`); `make reset` (`test_reset.py`); el escenario completo por MCP con 3 aprobaciones (`test_scenario.py`); `make preflight`: lectura de la salida de adb (permisos, huellas, galería, bloqueo, `device_id`) y las revisiones de Firestore (seed, reclamos sobrantes, dispositivo registrado) (`test_preflight.py`) |
| Android | `make android-test` (sabores `fake` y `live`) | Mapeo de AppFunctions, `ApprovalFlow` y firma de aprobaciones, protocolo del chat, repositorio, cliente HTTP, push, línea de tiempo, mensajes de error |
| Web | `cd web && npm test` (18 pruebas) | Línea de tiempo del presentador (selección de corrida con `?run=` y, sin él, la del reclamo activo; etiqueta «automático») y herramientas WebMCP (exactamente cuatro, todas de solo lectura, ninguna aprueba) |
| Web e2e | `cd web && npm run e2e` | Presentador en modo demo con Chrome: rótulo REPETICIÓN GRABADA, pausa en ámbar, 3 aprobaciones, el reloj se detiene al firmar |
| Sin red | `make up-offline`, luego `cd web && E2E_BASE_URL=http://127.0.0.1:3217 npx playwright test e2e/offline.spec.ts` | La repetición funciona con toda petición fuera de la laptop abortada, sin «en vivo» y sin pedidos externos |
| WebMCP real | `cd web && E2E_WEBMCP=1 npm run e2e` | Chrome con el flag de WebMCP, contra el API desplegado: el portal registra cuatro herramientas y responden |
| Escenario por MCP | `make scenario CLOUD=1` | El flujo completo contra el servicio desplegado, con su propio cliente, cronometrado (< 180 s, 3 aprobaciones) |
| Escenario por chat | `make chat-scenario CLOUD=1` | El mismo flujo escribiéndole al asistente, con su propio cliente (< 180 s, 3 aprobaciones) |
| Escenario en el emulador | `make -C android chat-scenario` | El flujo completo por chat en la app, con huella (sección 8) |

Las pruebas que necesitan nube están fuera de `make test`; `make test` corre sin red.

## 8. Emulador Android

En la charla no hay teléfono físico: el emulador `Pixel_9_API_36_1` es el dispositivo del escenario,
no un respaldo. Todo lo de esta sección se hace en él.

### 8.1 Crear el AVD (una vez)

```bash
sdkmanager "system-images;android-36.1;google_apis_playstore;arm64-v8a"
avdmanager create avd -n Pixel_9_API_36_1 -k "system-images;android-36.1;google_apis_playstore;arm64-v8a" -d pixel_9
```

Hace falta la imagen 36.1 (Android 16 QPR2): en 36.0 el servicio de AppFunctions existe pero
`adb shell cmd app_function` no. La variante *Play* trae Google Play Services, necesario para FCM.

### 8.2 Arrancarlo

```bash
make android-emulator     # arranca Pixel_9_API_36_1 (o reutiliza el que ya corre) y muestra su serial
```

Todos los comandos de Android (`android-install`, `make -C android chat-scenario`, …) buscan el serial
del emulador `Pixel_9_API_36_1` en ejecución (`android/scripts/emulator_serial.sh`), así que da igual en
qué puerto arrancó. `make android-serial` lo muestra; `ANDROID_SERIAL=…` lo fuerza.

### 8.3 Dejarlo listo: `make android-prep`

```bash
make seed            # una vez: escribe en .env.local el correo y la contraseña de la clienta
make android-prep    # arranca el emulador y lo prepara; idempotente, se puede repetir
```

`android/scripts/prep_emulator.sh` revisa cada paso y solo hace lo que falta:

| Paso | Cómo |
| --- | --- |
| PIN `1234` y pantalla desbloqueada | `locksettings set-pin` si no hay PIN; despierta, quita el bloqueo y escribe el PIN; la pantalla no se apaga en 30 min |
| `android/local.properties` | Lo escribe desde `.env.local` (`sdk.dir`, URL del API desplegado, correo y contraseña de la clienta) si no tiene la contraseña |
| `google-services.json` | `make -C android google-services` si no existe (`firebase apps:sdkconfig ANDROID …`). Sin ese archivo la app compila, pero solo sirve el sabor `fake` (sin Auth ni FCM) |
| App `live` | `./gradlew installLiveDebug` (Gradle no hace nada si no hubo cambios) |
| Permisos | `pm grant` de `POST_NOTIFICATIONS` y `READ_MEDIA_IMAGES` (este último para `reportAccident` con `recentPhotoCount`) |
| Fotos del choque | Copia `backend/samples/photos/crash_1..3.jpg` a `Pictures/escudo_crash_1..3.jpg` si no están en la galería y la vuelve a escanear |
| Huella | Si no hay ninguna: abre el registro de Ajustes, escribe el PIN, acepta y toca el sensor virtual (`adb emu finger touch 1`) hasta que queda registrada |
| Token | Reinicia la app: inicia sesión sola con la clienta y registra su token FCM y su llave de aprobación (`POST /devices`); espera la línea `Device registered for approval pushes` en logcat |

`BiometricVerifier` acepta biometría fuerte con el PIN como respaldo; en el escenario se aprueba tocando
el sensor con `adb emu finger touch 1`. En la charla no hay pantalla de login.

Para un backend local con `MOCK_AUTH=1` (`make up-mock`), agrega a `android/local.properties`
`escudo.apiUrl=http://10.0.2.2:8080` y `escudo.mockAuth=true`. También se aceptan las variables
`ESCUDO_API_URL`, `ESCUDO_CUSTOMER_EMAIL`, `ESCUDO_CUSTOMER_PASSWORD` y `ESCUDO_MOCK_AUTH`. La URL del chat
se deriva de `escudo.apiUrl` (`wss://…/assistant/chat`).

### 8.4 Verificar: `make preflight`

`make preflight` (`backend/escudo/preflight.py`) revisa en una pasada lo que la charla necesita y sale con
error si algo falta; cada falla imprime el comando que la arregla:

- Nube: `escudo-api` y `escudo-web` con `min-instances=1`; `/health`, la Agent Card y `/presenter` responden
  200 en menos de 2 s.
- Emulador: corriendo, arranque completo, desbloqueado, app instalada, los dos permisos, al menos una huella
  y las tres fotos en la galería.
- Firestore: póliza `POL-GT-2027-0042` activa, 3 talleres con horarios, 2 grúas, y `cus_demo` sin reclamos
  ni aprobaciones (estado de `make reset CLOUD=1`).
- Registro: el `device_id` de la app (sus `shared_prefs`) está en `devices`, a nombre de `cus_demo`, activo,
  con token FCM y llave de aprobación.

### 8.5 Escenario por chat en el emulador

```bash
make reset CLOUD=1
make -C android chat-scenario   # usa el emulador Pixel_9_API_36_1 que esté corriendo
```

Reporta el accidente desde la app con las tres fotos de la galería, le escribe al Asistente Escudo,
aprueba cada pausa con la huella virtual y termina cuando el reclamo queda firmado; imprime el tiempo
real. Los detalles (marcadores de logcat, capturas) están en [`android/README.md`](../android/README.md).

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
  --parameters '{\"description\": \"Choque menor: me chocaron por detrás mientras esperaba en el semáforo. Nadie salió herido.\", \"recentPhotoCount\": 1, \"locationLabel\": \"Calzada Roosevelt, zona 11\"}' \
  --brief-yaml"

adb shell "cmd app_function execute-app-function --package app.escudo.android \
  --function '$F#getClaimStatus' --parameters '{}' --brief-yaml"
```

Cada llamada viaja con `X-Escudo-Surface: appfunctions`, así que aparece en `/presenter` con su insignia
y como **Agente externo** (quien la llama es `adb`, no Gemini).
Para mostrar que una función se puede apagar:
`adb shell cmd app_function set-enabled --package app.escudo.android --function "$F#reportAccident" --state disable`
(y `--state default` para volver). Más detalle en [`android/APPFUNCTIONS.md`](../android/APPFUNCTIONS.md).

### WebMCP en Chrome

1. Una vez: `chrome://flags/#enable-webmcp-testing` → **Enabled** → relanzar Chrome (150 o más).
2. Abrir https://escudo-web-611681112050.us-central1.run.app/entrar y entrar con la cuenta de Andrea
   (correo y contraseña en `.env.local`; la sesión queda guardada en el navegador, hazlo antes de la
   charla). El pie del portal dice «WebMCP · 4 herramientas disponibles…».
3. DevTools (`⌥⌘I`) → **Application** → **WebMCP**: aparecen `getCoverage`, `getDeductible`,
   `findRepairShops` y `getClaimStatus` con su descripción y esquema. Elegir `getDeductible` → **Run tool**
   → `{"deductible_usd": 500, …}`; `/presenter` muestra el paso con la insignia **WebMCP**, como
   **Agente externo**, en la línea de tiempo del reclamo activo de Andrea.

## 10. Recorrido por el código: una llamada a través de todas las capas

Seguimos `accept_deductible` en el escenario por chat: la primera llamada termina en una pausa; la
segunda, tras la huella, se ejecuta.

**Teléfono → servidor (chat)**

1. `android/.../assistant/ChatSession.kt` abre el WebSocket `wss://…/assistant/chat` con
   `Authorization: Bearer <ID token>` y envía `{"type":"text","text":"Sí, acepta el deducible"}`.
2. `backend/escudo/adapters/assistant_chat.py`: `assistant_chat()` verifica el token
   (`auth.Authenticator.verify`), carga la conversación de Firestore (`chat_sessions`) y `run_turn()` arma
   un `Runner` de ADK con `build_agent()` —un `LlmAgent` sobre `GEMINI_MODEL`— y reconstruye la
   conversación en una sesión nueva: ninguna instancia guarda estado entre turnos.

**Agente → herramienta (MCP)**

3. Gemini decide llamar `accept_deductible(claim_id)`. El agente no tiene acceso directo al dominio:
   su `McpToolset` llama por HTTP a `http://127.0.0.1:{PORT}/mcp` con el token de la clienta y
   `X-Escudo-Actor: escudo_assistant`.
4. `adapters/mcp_server.py`: `FirebaseTokenVerifier` valida el bearer; la herramienta `accept_deductible`
   llama `invoke()` → `caller_for()` arma el `Caller` (superficie `mcp`, actor `escudo_assistant`).

**La única puerta**

5. `gateway.call_capability`: busca la capacidad en `capabilities.REGISTRY`, valida la entrada con su modelo
   Pydantic, escribe el evento `tool_call` y corre el *precheck*
   (`capabilities/accept_deductible.py`: hay evaluación y el deducible no estaba aceptado).
6. `autonomy.enforce`: la regla es `NEEDS_APPROVAL` y no hay `approval_id`, así que llama
   `describe_approval` («Aceptar pagar el deducible de USD 500 del reclamo …») y
   `approvals.request`, que guarda `approvals/{id}` con `status: pending`, `tool` y
   `args_hash = sha256({tool, args})`. **No cambia nada del reclamo.**
7. De vuelta en el gateway: evento `approval_requested` (actor `backend`) y
   `push.FcmPush.approval_requested` envía el push a los dispositivos de la clienta. La respuesta es
   `{"status": "pending_approval", "approval_id": "apr_…", …}`.

**El servidor le cuenta al teléfono**

8. `assistant_chat.run_turn` ve la respuesta de la función, envía `tool_result` y, como contiene
   `pending_approval`, `approval_required` al teléfono; el asistente escribe que le llegó una notificación
   y el chat empieza a vigilar esa aprobación en Firestore.
9. En el teléfono, `push/EscudoMessagingService.kt` recibe el FCM y `ApprovalNotifications` muestra la
    notificación con el enlace `escudo://approvals/{approval_id}`. La pantalla del asistente muestra la
    banda ámbar «Tu asistente espera tu aprobación».

**La decisión humana**

10. `ui/approvals/ApprovalsScreen.kt` → `approvals/ApprovalFlow.approve()` → `BiometricVerifier`
    (`BiometricPrompt`, biometría fuerte o PIN). Solo si pasa, `EscudoRepository.approve()` →
    firma con la llave del Keystore `escudo-approval:v1:{approval_id}:{args_hash}:approve` y llama
    `POST /approvals/{id}/approve` con `{"device_id", "signature"}`.
11. `adapters/rest.py` `_decide()` → `approvals.decide()`: `devices.check_decision()` verifica dueño,
    dispositivo registrado y firma (si no, 403 sin cambios); luego cambia `pending → approved` (transacción de
    Firestore), escribe `approval_granted`. El chat que vigilaba esa aprobación —en esta instancia o en otra— ve el
    cambio en Firestore, envía `approval_resolved` y corre un turno nuevo con «[Aviso de Escudo] La clienta
    aprobó accept_deductible … approval_id=…».

**El reintento**

12. El asistente repite `accept_deductible(claim_id, approval_id)` por MCP. En `autonomy.enforce`, ahora
    `approvals.redeem()` comprueba, dentro de una transacción: misma clienta, misma herramienta, mismo
    `args_hash`, estado `approved`; y marca `used`.
13. `capabilities/accept_deductible.run()` actualiza el reclamo (`deductible_accepted`, estado
    `deductible_accepted`); el gateway escribe `tool_result` con `approval_id`.

**El presentador**

14. Cada evento quedó en Firestore `events`. `web/src/lib/server/presenterFeed.ts` lo recibe por
    `onSnapshot` y `app/api/presenter/stream/route.ts` lo envía por SSE; `PresenterView` lo dibuja y
    `PhoneMirror` cambia de «Aprobación» al estado del reclamo.

El mismo recorrido, desde el paso 5, aplica a cualquier superficie: REST (`adapters/rest.py`), MCP externo,
el agente A2A (`agents/escudo_agent.build_a2a_agent`) o una AppFunction (que llama REST con
`X-Escudo-Surface: appfunctions`).

## 11. Problemas frecuentes

| Síntoma | Causa y solución |
| --- | --- |
| `RuntimeError: GEMINI_MODEL is not set (or run with MOCK_AI=1)` | Falta `.env` o la variable. `cp .env.example .env`, o usa `make up-mock` |
| `make seed` falla con un error de cuota o de proyecto en Identity Toolkit | ADC de usuario sin proyecto de cuota: `gcloud auth application-default set-quota-project ai-experiments-487722` (o `GOOGLE_CLOUD_QUOTA_PROJECT` en `.env`) |
| `make reset` → `reset falló: HTTP 401` | Token distinto entre el servidor y `make reset`. Local: los dos toman `ADMIN_TOKEN` del `Makefile` o de `.env`; si arrancaste el servidor con otro valor, reinícialo. Nube: `make reset CLOUD=1` (lee `escudo-admin-token` de Secret Manager con tu gcloud; corre `infra/06-secrets.sh` si no existe) |
| `make reset` → `HTTP 000` | El API no está arriba en `ESCUDO_BASE_URL` |
| `approval_invalid (wrong_args)` | El agente reintentó con otros argumentos (por ejemplo, otro horario). Hay que pedir una nueva aprobación; la anterior no se quemó |
| `approval_invalid (not_approved)` | El agente reintentó antes de que la clienta aprobara. Esperar el aviso |
| No llega el push de aprobación | La app no registró su token (abrirla una vez con sesión iniciada), falta `google-services.json`, falta el permiso de notificaciones, o el emulador no es una imagen *Play*. Mientras tanto, la pestaña Aprobaciones también muestra la pendiente |
| «Ningún socio disponible ofrece ese servicio» (`peer_unavailable`) | `A2A_PEERS` no apunta a las Agent Cards (`{base}/.well-known/agent-card.json`) o el secreto `X-Escudo-A2A-Key` no coincide |
| `adb shell cmd app_function` → `No shell command implementation.` | Imagen API 36.0. Usa `Pixel_9_API_36_1` (36.1) |
| `reportAccident` por AppFunction: «Escudo cannot read the gallery yet» | `make android-prep` (concede `READ_MEDIA_IMAGES`) |
| `make -C android chat-scenario` → «No running emulator for AVD Pixel_9_API_36_1» o «not on screen» | Arranca el emulador con `make android-emulator` (otro AVD no cuenta). Revisa también que haya tres fotos en la galería y que la app esté instalada con sesión iniciada |
| `make -C android chat-scenario` → «no biometric prompt» o «approval not granted» | No hay huella registrada: `make android-prep` (sección 8.3) |
| El portal dice «WebMCP no está activo en este navegador» | Activa `chrome://flags/#enable-webmcp-testing` y relanza Chrome 150+ |
| `/presenter` vacío en local | `make web` lee Firestore con tu ADC; usa `/presenter?demo=1` sin nube |
| La evaluación de fotos tarda mucho | El perito lanza una llamada de respaldo cada 5 s (hasta 4, gana la primera; límite 45 s, después `assessment_unavailable`). Si `global` está lento para todos, ninguna gana antes: los tiempos quedan en los logs como `assessment_timing` |
| El chat no sigue solo después de aprobar | La aprobación se decidió con otra conversación cerrada: reabre el chat con la cabecera `X-Escudo-Chat-Session` (la app la guarda) o escribe «sigue» |
| `make scenario` → 401 en `/admin/runs` | Token de admin distinto al del servidor local (ver fila de `make reset`) o sin acceso a Secret Manager (`CLOUD=1`) |
| Portal: «No pudimos iniciar sesión», o el corredor recibe 403 `Requests from referer … are blocked` | La «Escudo web key» no acepta ese origen. Agrega el origen a sus referrers en `infra/07-web-api-key.sh` y vuelve a correrlo (en local, la web debe estar en `:3217`) |
| `make up-offline` → `EADDRINUSE 127.0.0.1:3217` | `make web` (u otro `next-server`) ya usa el puerto: ciérralo o usa `PORT=3218 make up-offline` |
| El chat se queda «pensando» más de 30 s | Gemini tardó; cada llamada se corta a los 30 s y se reintenta. Si se repite, `global` está lento: usa el respaldo (`make chat-scenario CLOUD=1` o `/presenter?demo=1`) |

## 12. Lista del día de la charla

**La víspera**

- [ ] `make stage-on`: `escudo-api` y `escudo-web` con una instancia caliente (`min-instances=1`).
- [ ] Ensayar el guion completo de [DEMO.md](DEMO.md) una vez.
- [ ] `make test` en verde (y el último run de CI en GitHub).
- [ ] `curl https://escudo-api-611681112050.us-central1.run.app/health` → `{"status":"ok"}`; Agent Cards responden.
- [ ] `make reset CLOUD=1`, `make scenario CLOUD=1 APPROVE_DELAY=4` y `make chat-scenario CLOUD=1`:
      «aprobaciones: 3 (esperadas 3) · OK» en ambos.
- [ ] `make android-prep`: el emulador `Pixel_9_API_36_1` (el dispositivo del escenario) queda con la app
      `live`, sesión, permisos, huella, las fotos del choque y su token registrado (sección 8.3).
- [ ] Un push de prueba llega al emulador (correr `make scenario-live` y aprobar una vez con
      `adb emu finger touch 1`).
- [ ] `make -C android chat-scenario` completo una vez en el emulador; después, `make reset CLOUD=1`.
- [ ] Chrome: flag de WebMCP activo; portal con sesión; DevTools → Application → WebMCP lista 4 herramientas.

**Antes de subir**

En este orden (`make stage-on` despliega una revisión nueva, así que el warmup va después):

- [ ] `make stage-on` (si no quedó de la víspera).
- [ ] `make android-prep`: arranca el emulador si no corre y repite lo que falte.
- [ ] `make warmup` → `warmup OK`.
- [ ] `make reset CLOUD=1` (< 10 s).
- [ ] `make preflight` → `preflight OK` (sección 8.4).
- [ ] `/presenter` abierto en Chrome a pantalla completa en el proyector; «Aprobaciones 0 de 3» (sin
      `?run`, sigue la corrida del reclamo más reciente: la del emulador).
- [ ] `make up-offline` probado en la laptop y `docs/escudo-chat-run.mp4` a mano.
- [ ] Laptop conectada a la corriente y en No molestar (que no aparezcan avisos en el proyector).
- [ ] Ventana del emulador a tamaño legible, app abierta en **Póliza**
      (el guion empieza ahí, ver [DEMO.md](DEMO.md)).
- [ ] Terminal en la raíz del repo con `export ANDROID_SERIAL=$(make -s android-serial)` y los comandos
      de la huella y de `adb … cmd app_function` listos (sección 9).
- [ ] Si se quiere el espejo exacto: ventana `scrcpy --serial "$(make -s android-serial)" --window-title "Escudo" --always-on-top`
      sobre el panel del espejo en `/presenter`.

**Si algo falla en vivo**

- El emulador se cuelga o no arranca a tiempo: `make chat-scenario CLOUD=1` (o
  `make scenario CLOUD=1 APPROVE_DELAY=4`) reproduce el
  flujo con su propio cliente; abre el `…/presenter?run=<id>` que imprime.
- Sin red: `make up-offline` y `http://127.0.0.1:3217/presenter?demo=1` (se ve **REPETICIÓN GRABADA**:
  dilo en voz alta). Si tampoco hay laptop confiable, el video `docs/escudo-chat-run.mp4`.
- Estado sucio: `make reset CLOUD=1`.

**Después de la charla**

- [ ] `make stage-off`: los dos servicios vuelven a `min-instances=0` (sin costo en reposo). Los
      despliegues siguientes respetan ese estado.
