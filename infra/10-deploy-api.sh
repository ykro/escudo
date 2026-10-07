#!/usr/bin/env bash
# Build and deploy the Escudo API to Cloud Run (service escudo-api).
# The build context is staged: backend/ + contracts/ (the event schema is validated at runtime).
source "$(dirname "$0")/env.sh"
ROOT="$(cd "$INFRA_DIR/.." && pwd)"
GEN="$INFRA_DIR/.generated"
mkdir -p "$GEN"

SERVICE=escudo-api
SA="${SERVICE}@${PROJECT_ID}.iam.gserviceaccount.com"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
URL="https://${SERVICE}-${PROJECT_NUMBER}.${REGION}.run.app"   # deterministic Cloud Run URL

# Model names and data settings come from .env.example (defaults) and .env (overrides).
set -a
source "$ROOT/.env.example"
[ -f "$ROOT/.env" ] && source "$ROOT/.env"
set +a

# ADMIN_TOKEN and A2A_SHARED_SECRET come from Secret Manager (infra/06-secrets.sh), mounted below.
exists gcloud secrets describe escudo-admin-token && exists gcloud secrets describe escudo-a2a-secret \
  || { echo "Run infra/06-secrets.sh first (make infra)"; exit 1; }

say "Staging build context"
STAGE="$GEN/build-api"
rm -rf "$STAGE" && mkdir -p "$STAGE"
rsync -a --exclude .venv --exclude __pycache__ --exclude .pytest_cache --exclude tests --exclude samples \
  "$ROOT/backend/" "$STAGE/"
mkdir -p "$STAGE/contracts" && cp "$ROOT/contracts/"*.json "$ROOT/contracts/"*.md "$ROOT/contracts/"*.yaml "$STAGE/contracts/"

ENV_VARS="GOOGLE_CLOUD_PROJECT=${PROJECT_ID},GOOGLE_CLOUD_LOCATION=${GOOGLE_CLOUD_LOCATION},GOOGLE_GENAI_USE_ENTERPRISE=true"
ENV_VARS+=",GEMINI_MODEL=${GEMINI_MODEL}"
ENV_VARS+=",FIRESTORE_DATABASE=${FIRESTORE_DATABASE},CLAIMS_BUCKET=${CLAIMS_BUCKET},DEMO_CUSTOMER_ID=${DEMO_CUSTOMER_ID}"
ENV_VARS+=",PUBLIC_BASE_URL=${URL},A2A_PEERS=${URL}/a2a/tow;${URL}/a2a/repair,MOCK_AI=0,MOCK_AUTH=0"

# Scales to zero between talks (min-instances 0, no cost at rest). Opening /presentador wakes it
#   (escudo-web's /api/presenter/wake) and startup CPU boost shortens that cold start. First generation
#   execution environment: measured 2026-10-06, a new instance was ready in ~13 s on gen1 against ~20 s
#   on gen2 (2 vCPU didn't help: the time is container start and Python imports). Up to 3
#   instances: chat transcripts and approval waits live in Firestore, so any instance can serve any
#   request. no-cpu-throttling + 1 h timeout: chat WebSockets stay open during the talk.
deploy_run "$SERVICE" "$STAGE" \
  --service-account="$SA" \
  --allow-unauthenticated \
  --min-instances=0 --max-instances=3 --cpu-boost --concurrency=80 --execution-environment=gen1 \
  --cpu=1 --memory=1Gi --no-cpu-throttling --timeout=3600 --session-affinity \
  --set-env-vars="^@^$(echo "$ENV_VARS" | tr ',' '@' | tr ';' ',')" \
  --set-secrets="ADMIN_TOKEN=escudo-admin-token:latest,A2A_SHARED_SECRET=escudo-a2a-secret:latest"

say "Deployed: $URL"
echo "  MCP:         $URL/mcp"
echo "  Agent Cards: $URL/a2a/{escudo,tow,repair}/.well-known/agent-card.json"
echo "  Chat:        ${URL/https/wss}/assistant/chat"
echo "  ADMIN_TOKEN: gcloud secrets versions access latest --secret=escudo-admin-token"
