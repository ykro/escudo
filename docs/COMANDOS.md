# Escudo: comandos

> [README](../README.md) · [English](COMANDOS.en.md)

## Inicio rápido completo

Sin nube (todo simulado: `MOCK_AI=1 MOCK_AUTH=1`, almacén en memoria, agentes socios deterministas):

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" >> android/local.properties   # una vez: make test incluye Android (make android-prep lo escribe completo)
make up-mock          # API en http://127.0.0.1:8080 (ADMIN_TOKEN=local-admin)
make reset            # en otra terminal: vuelve al estado inicial (mismo token)
make scenario         # el escenario completo por MCP, con las 3 aprobaciones firmadas por un dispositivo de prueba
make chat-scenario    # el mismo flujo por chat, con el asistente simulado
make test             # backend (pytest), Android (unitarias) y web (vitest)
```

Contra la nube (requiere ADC y `.env`; ver [docs/ONBOARDING.md](ONBOARDING.md)):

```bash
cp .env.example .env  # y completa los secretos
make reset CLOUD=1    # vuelve el servicio desplegado al estado inicial
make scenario CLOUD=1 APPROVE_DELAY=4   # cliente propio para esta corrida; imprime …/presenter?run=<id>
make chat-scenario CLOUD=1              # lo mismo, escribiéndole al asistente por chat
```

Cada corrida de `make scenario` y `make chat-scenario` crea su propio cliente ficticio
(`POST /admin/runs`: póliza, login de Firebase y `run_id`), así que varias corridas a la vez no chocan
entre sí ni con la clienta del escenario, que es la sembrada. `make reset` borra esos clientes.

Día de la charla, en este orden (el detalle está en [DEMO.md](DEMO.md)):

```bash
make stage-on         # escudo-api y escudo-web con una instancia caliente (despliega una revisión nueva)
make android-prep     # arranca el emulador y lo deja listo: PIN, app live, permisos, fotos, huella y token
make warmup           # después de stage-on: toca cada superficie pública (todo 200)
make reset CLOUD=1    # estado inicial
make preflight        # verificación final: nube caliente, Firestore inicial, emulador listo y registrado
# … la charla …
make stage-off        # min-instances=0
```

Sin red en el escenario: `make up-offline` sirve la web en producción desde la laptop y
`http://127.0.0.1:3217/presenter?demo=1` reproduce una corrida real grabada, rotulada
**REPETICIÓN GRABADA**. Último respaldo: el video [`docs/escudo-chat-run.mp4`](escudo-chat-run.mp4).

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
| `make scenario-live` | El escenario por MCP con la clienta sembrada (`--shared`); espera a que apruebe cada paso en la app del emulador |
| `make infra` | Crea o verifica todo en GCP desde un proyecto vacío: `infra/01..07` (APIs, base `escudo`, bucket, Artifact Registry, Firebase Auth por API, cuentas de servicio, Secret Manager, llave web propia de Firebase) |
| `make deploy` | `deploy-api` + `deploy-web`; conservan el `min-instances` actual de cada servicio |
| `make deploy-api` | `infra/10-deploy-api.sh`: construye y despliega `escudo-api` |
| `make deploy-web` | `infra/11-deploy-web.sh`: construye y despliega `escudo-web` |
| `make warmup` | Antes de subir: toca API, Agent Cards, portal y `/presenter` desplegados e imprime estado y latencia (falla si algo no da 200) |
| `make stage-on` | Día de charla: `min-instances=1` en `escudo-api` y `escudo-web` (sin arranque en frío) |
| `make preflight` | Última verificación antes de subir: `min-instances=1` y respuesta rápida en ambos servicios, seed en Firestore sin reclamos ni aprobaciones de `cus_demo`, emulador desbloqueado con app, permisos, huella y fotos, y su dispositivo registrado en Firestore (token FCM + llave de aprobación). Cada falla imprime el comando que la arregla |
| `make stage-off` | Fuera de la charla: `min-instances=0` (escalan a cero; los despliegues siguientes lo respetan) |
| `make photos` | Regenera las fotos de ensayo del choque con `IMAGE_MODEL` |
| `make brand` | Regenera logo y maquetas «A · Sereno» con `IMAGE_MODEL` desde `brand/prompts/` (`ARGS="--force logo"`) |
| `make android-build` | `./gradlew assembleDebug` (sabores `live` y `fake`) |
| `make android-test` | Pruebas unitarias de Android (ambos sabores) |
| `make android-install` | Instala y abre la app (`FLAVOR=live` por defecto, `FLAVOR=fake` sin red) |
| `make android-emulator` | Arranca (o reutiliza) el AVD `Pixel_9_API_36_1` (Android 16 QPR2, necesario para `cmd app_function`) y muestra su serial |
| `make android-prep` | Arranca el emulador y lo deja listo sin pasos manuales (idempotente): PIN 1234 y pantalla desbloqueada, `android/local.properties` desde `.env.local`, `google-services.json`, app `live` instalada, permisos de notificaciones y galería, las tres fotos como `escudo_crash_1..3.jpg`, una huella registrada (manejando la pantalla de registro de Ajustes) y la app abierta para que registre su token |
| `make android-serial` | Serial adb de ese emulador; todos los comandos de Android lo detectan solos |

`android/Makefile` agrega `make -C android chat-scenario` (escenario por chat en el emulador, con huella
virtual y cronometrado), `google-services` (descarga `google-services.json`) y `appfunctions` (lista las AppFunctions en el dispositivo).
