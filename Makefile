# Escudo — agent-ready car insurer. `make help` lists every target (from the `##` comments).
SHELL := /bin/bash
-include .env
-include .env.local
export

BACKEND := backend
DEPLOYED_URL := https://escudo-api-611681112050.us-central1.run.app
ESCUDO_BASE_URL ?= http://127.0.0.1:8080
# `make reset|scenario CLOUD=1` targets the deployed service instead of the local one.
ifeq ($(CLOUD),1)
ESCUDO_BASE_URL := $(DEPLOYED_URL)
endif
# One admin token for local servers (make up-mock / make up); in the cloud it comes from Secret Manager.
ADMIN_TOKEN ?= local-admin
LOCAL := $(filter http://127.0.0.1% http://localhost%,$(ESCUDO_BASE_URL))
ENV_FILE := $(if $(wildcard .env),--env-file ../.env,)

# AppFunctions + FCM need the API 36.1 emulator image (Android 16 QPR2). Every Android target
# finds that emulator's adb serial at run time (android/scripts/emulator_serial.sh).
ANDROID_AVD ?= Pixel_9_API_36_1
EMULATOR := $${ANDROID_HOME:-$$HOME/Library/Android/sdk}/emulator/emulator

.PHONY: help up up-mock up-offline web seed reset test scenario chat-scenario scenario-live stage-on stage-off infra deploy deploy-api deploy-web \
	photos brand android-build android-test android-install android-emulator android-serial

help: ## Lista todos los comandos
	@grep -hE '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | awk -F'\t' '{printf "  make %-18s %s\n", $$1, $$2}'

# --- run --------------------------------------------------------------------------
up: ## API local (Firestore + Gemini con ADC) en :8080
	cd $(BACKEND) && uv run $(ENV_FILE) uvicorn escudo.main:create_app --factory --host 0.0.0.0 --port 8080

up-mock: ## API local sin nube (MOCK_AI=1 MOCK_AUTH=1) en :8080
	cd $(BACKEND) && MOCK_AI=1 MOCK_AUTH=1 ADMIN_TOKEN=$(ADMIN_TOKEN) uv run uvicorn escudo.main:create_app --factory --host 0.0.0.0 --port 8080

web: ## Portal + /presenter local en :3217 (contra el API desplegado)
	cd web && npm run dev

up-offline: ## Respaldo sin red: build de producción local en :3217, fuentes propias; /presenter?demo=1
	web/scripts/serve-offline.sh

# --- data -------------------------------------------------------------------------
seed: ## Póliza, talleres y grúas en Firestore + login del cliente en Firebase Auth (.env.local)
	cd $(BACKEND) && uv run $(ENV_FILE) python -m escudo.admin seed

reset: ## Estado inicial en < 10 s en ESCUDO_BASE_URL (CLOUD=1: el desplegado)
	@token="$${ADMIN_TOKEN}"; \
	if [ -z "$(LOCAL)" ]; then token=$$(gcloud secrets versions access latest --secret=escudo-admin-token --project=ai-experiments-487722); fi; \
	out=$$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST -H "X-Admin-Token: $$token" $(ESCUDO_BASE_URL)/admin/reset); \
	code=$${out% *}; secs=$${out#* }; \
	if [ "$$code" = "204" ]; then echo "reset OK en $$secs s ($(ESCUDO_BASE_URL))"; \
	else echo "reset falló: HTTP $$code en $(ESCUDO_BASE_URL) (¿está arriba? ¿ADMIN_TOKEN?)"; exit 1; fi

photos: ## Regenera las fotos de choque de muestra con IMAGE_MODEL (backend/samples/photos)
	cd $(BACKEND) && uv run $(ENV_FILE) python scripts/generate_sample_photos.py

brand: ## Regenera logo y maquetas de la dirección «A · Sereno» con IMAGE_MODEL (brand/)
	cd $(BACKEND) && uv run $(ENV_FILE) python ../brand/generate_brand.py $(ARGS)

# --- test -------------------------------------------------------------------------
test: ## Pruebas: backend (pytest), Android (unitarias) y web (vitest)
	cd $(BACKEND) && MOCK_AI=1 MOCK_AUTH=1 uv run pytest -q
	@if [ -f android/Makefile ]; then $(MAKE) -C android test; fi
	@if [ -f web/package.json ]; then cd web && npm test --silent; fi

# Each run gets its own customer (POST /admin/runs needs the admin token: Secret Manager in the cloud).
RUN_ADMIN = $(if $(LOCAL),$${ADMIN_TOKEN},$$(gcloud secrets versions access latest --secret=escudo-admin-token --project=ai-experiments-487722))

scenario: ## Escenario completo vía MCP con su propio cliente, aprobaciones firmadas (modo prueba; CLOUD=1, APPROVE_DELAY=4)
	cd $(BACKEND) && ADMIN_TOKEN=$(RUN_ADMIN) uv run $(ENV_FILE) python -m escudo.scenario --base-url $(ESCUDO_BASE_URL) --test-mode --auto-approve

chat-scenario: ## Escenario por chat escrito con su propio cliente: 3 aprobaciones firmadas, < 3 min (CLOUD=1)
	cd $(BACKEND) && ADMIN_TOKEN=$(RUN_ADMIN) uv run $(ENV_FILE) python -m escudo.chat_scenario --base-url $(ESCUDO_BASE_URL) --test-mode

scenario-live: ## Mismo escenario, pero espera a que apruebes cada paso en el teléfono
	cd $(BACKEND) && uv run $(ENV_FILE) python -m escudo.scenario --base-url $(ESCUDO_BASE_URL) --shared

# --- cloud ------------------------------------------------------------------------
infra: ## Crea o verifica todo en GCP desde cero (infra/01..06, idempotente)
	@for s in infra/0[1-9]-*.sh; do bash "$$s" || exit 1; done

deploy: deploy-api deploy-web ## Despliega escudo-api y escudo-web en Cloud Run

deploy-api: ## Despliega solo escudo-api
	bash infra/10-deploy-api.sh

deploy-web: ## Despliega solo escudo-web
	bash infra/11-deploy-web.sh

# Talk days: one warm instance of each service (no cold start on stage). Other days: scale to zero.
stage-on: ## Día de charla: escudo-api y escudo-web con min-instances=1 (sin arranque en frío)
	@for svc in escudo-api escudo-web; do gcloud run services update $$svc --region=us-central1 --project=ai-experiments-487722 --min-instances=1 --quiet >/dev/null && echo "$$svc: min-instances=1"; done

stage-off: ## Fuera de la charla: min-instances=0 (escala a cero, sin costo en reposo)
	@for svc in escudo-api escudo-web; do gcloud run services update $$svc --region=us-central1 --project=ai-experiments-487722 --min-instances=0 --quiet >/dev/null && echo "$$svc: min-instances=0"; done

# --- Android (escudo/android has its own Makefile) -------------------------------
android-build: ## APKs de depuración (sabores fake y live)
	$(MAKE) -C android build

android-test: ## Pruebas unitarias de Android
	$(MAKE) -C android test

android-install: ## Instala y abre la app en el emulador (FLAVOR=live|fake)
	$(MAKE) -C android install FLAVOR=$(or $(FLAVOR),live) ANDROID_AVD=$(ANDROID_AVD)

android-emulator: ## Arranca (o reutiliza) el emulador Pixel_9_API_36_1 y muestra su serial
	@if ! android/scripts/emulator_serial.sh $(ANDROID_AVD) >/dev/null 2>&1; then \
		$(EMULATOR) -avd $(ANDROID_AVD) -no-snapshot-save >/dev/null 2>&1 & \
	fi; \
	android/scripts/emulator_serial.sh --wait 180 $(ANDROID_AVD)

android-serial: ## Serial adb del emulador Pixel_9_API_36_1 en ejecución
	@android/scripts/emulator_serial.sh $(ANDROID_AVD)
