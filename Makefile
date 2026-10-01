# Escudo — agent-ready car insurer. Run `make help`.
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
ADMIN_TOKEN_FILE := infra/.generated/admin_token
LOCAL := $(filter http://127.0.0.1% http://localhost%,$(ESCUDO_BASE_URL))
ENV_FILE := $(if $(wildcard .env),--env-file ../.env,)

.PHONY: help up up-mock web seed reset test scenario scenario-live deploy deploy-api deploy-web photos android-build android-test android-install android-emulator voice-scenario

help:
	@echo "make up          API local (Firestore + Gemini, ADC) en :8080"
	@echo "make up-mock     API local sin nube (MOCK_AI=1 MOCK_AUTH=1)"
	@echo "make seed        póliza, talleres y grúas en Firestore + login del cliente en Firebase Auth (.env.local)"
	@echo "make reset       vuelve al estado inicial (< 10 s) en ESCUDO_BASE_URL (CLOUD=1: el desplegado)"
	@echo "make test        pruebas (backend; Android y web si existen)"
	@echo "make scenario    escenario completo vía MCP, aprobaciones automáticas (modo prueba; CLOUD=1, APPROVE_DELAY=4)"
	@echo "make web         portal + /presenter local en :3217 (contra el API desplegado)"
	@echo "make deploy      despliega escudo-api y escudo-web en Cloud Run (deploy-api / deploy-web)"
	@echo "make android-build | android-test | android-install [FLAVOR=live|fake]"
	@echo "make android-emulator   arranca el emulador de AppFunctions/FCM ($(ANDROID_AVD))"

up:
	cd $(BACKEND) && uv run $(ENV_FILE) uvicorn escudo.main:create_app --factory --host 0.0.0.0 --port 8080

up-mock:
	cd $(BACKEND) && MOCK_AI=1 MOCK_AUTH=1 ADMIN_TOKEN=local-admin uv run uvicorn escudo.main:create_app --factory --host 0.0.0.0 --port 8080

web:
	cd web && npm run dev

seed:
	cd $(BACKEND) && uv run $(ENV_FILE) python -m escudo.admin seed

reset:
	@token="$${ADMIN_TOKEN}"; \
	if [ -z "$(LOCAL)" ] && [ -f $(ADMIN_TOKEN_FILE) ]; then token=$$(cat $(ADMIN_TOKEN_FILE)); fi; \
	out=$$(curl -s -o /dev/null -w '%{http_code} %{time_total}' -X POST -H "X-Admin-Token: $$token" $(ESCUDO_BASE_URL)/admin/reset); \
	code=$${out% *}; secs=$${out#* }; \
	if [ "$$code" = "204" ]; then echo "reset OK en $$secs s ($(ESCUDO_BASE_URL))"; \
	else echo "reset falló: HTTP $$code en $(ESCUDO_BASE_URL) (¿está arriba? ¿ADMIN_TOKEN?)"; exit 1; fi

test:
	cd $(BACKEND) && MOCK_AI=1 MOCK_AUTH=1 uv run pytest -q
	@if [ -f android/Makefile ]; then $(MAKE) -C android test; else echo "(android/: sin pruebas todavía)"; fi
	@if [ -f web/package.json ]; then cd web && npm test --silent; fi

scenario:
	cd $(BACKEND) && uv run $(ENV_FILE) python -m escudo.scenario --base-url $(ESCUDO_BASE_URL) --test-mode --auto-approve \
		$(if $(FIREBASE_WEB_API_KEY),--sign-in,)

# Same flow, but waits for the customer to approve each step on the phone.
scenario-live:
	cd $(BACKEND) && uv run $(ENV_FILE) python -m escudo.scenario --base-url $(ESCUDO_BASE_URL) $(if $(FIREBASE_WEB_API_KEY),--sign-in,)

deploy: deploy-api deploy-web

deploy-api:
	bash infra/10-deploy-api.sh

deploy-web:
	bash infra/11-deploy-web.sh

photos:
	cd $(BACKEND) && uv run $(ENV_FILE) python scripts/generate_sample_photos.py

# --- Android (escudo/android has its own Makefile) -------------------------------
# AppFunctions + FCM need the API 36.1 emulator image (Android 16 QPR2).
ANDROID_AVD ?= Pixel_9_API_36_1

android-build: ; $(MAKE) -C android build
android-test: ; $(MAKE) -C android test
android-install: ; $(MAKE) -C android install FLAVOR=$(or $(FLAVOR),live)

android-emulator:
	$${ANDROID_HOME:-$$HOME/Library/Android/sdk}/emulator/emulator -avd $(ANDROID_AVD) -no-snapshot-save >/dev/null 2>&1 &

voice-scenario: ## Voice-driven scenario on emulator-5556 (3 approvals, fingerprint)
	$(MAKE) -C android voice-scenario
