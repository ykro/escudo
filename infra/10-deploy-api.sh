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

# Admin token for POST /admin/reset: generated once, kept out of git.
[ -s "$GEN/admin_token" ] || openssl rand -hex 24 > "$GEN/admin_token"
ADMIN_TOKEN_VALUE="$(cat "$GEN/admin_token")"

say "Staging build context"
STAGE="$GEN/build-api"
rm -rf "$STAGE" && mkdir -p "$STAGE"
rsync -a --exclude .venv --exclude __pycache__ --exclude .pytest_cache --exclude tests --exclude samples \
  "$ROOT/backend/" "$STAGE/"
mkdir -p "$STAGE/contracts" && cp "$ROOT/contracts/"*.json "$ROOT/contracts/"*.md "$ROOT/contracts/"*.yaml "$STAGE/contracts/"

ENV_VARS="GOOGLE_CLOUD_PROJECT=${PROJECT_ID},GOOGLE_CLOUD_LOCATION=${GOOGLE_CLOUD_LOCATION},GOOGLE_GENAI_USE_ENTERPRISE=true"
ENV_VARS+=",GEMINI_MODEL=${GEMINI_MODEL},GEMINI_LIVE_MODEL=${GEMINI_LIVE_MODEL},GEMINI_LIVE_LOCATION=${GEMINI_LIVE_LOCATION}"
ENV_VARS+=",FIRESTORE_DATABASE=${FIRESTORE_DATABASE},CLAIMS_BUCKET=${CLAIMS_BUCKET},DEMO_CUSTOMER_ID=${DEMO_CUSTOMER_ID}"
ENV_VARS+=",PUBLIC_BASE_URL=${URL},A2A_PEERS=${URL}/a2a/tow;${URL}/a2a/repair,MOCK_AI=0,MOCK_AUTH=0,ADMIN_TOKEN=${ADMIN_TOKEN_VALUE}"

# min-instances=1: no cold start on stage. max-instances=1: the voice session and the approval that
#   nudges it live in the same process. no-cpu-throttling + 1 h timeout: WebSocket voice sessions.
deploy_run "$SERVICE" "$STAGE" \
  --service-account="$SA" \
  --allow-unauthenticated \
  --min-instances=1 --max-instances=1 --concurrency=80 \
  --cpu=1 --memory=1Gi --no-cpu-throttling --timeout=3600 --session-affinity \
  --set-env-vars="^@^$(echo "$ENV_VARS" | tr ',' '@' | tr ';' ',')"

say "Deployed: $URL"
echo "  MCP:         $URL/mcp"
echo "  Agent Cards: $URL/a2a/{escudo,tow,repair}/.well-known/agent-card.json"
echo "  Voice:       ${URL/https/wss}/assistant/live"
echo "  ADMIN_TOKEN: $GEN/admin_token"
