#!/usr/bin/env bash
# Build and deploy the customer portal + presenter view to Cloud Run (service escudo-web).
source "$(dirname "$0")/env.sh"
ROOT="$(cd "$INFRA_DIR/.." && pwd)"
GEN="$INFRA_DIR/.generated"
mkdir -p "$GEN"

SERVICE=escudo-web
SA="${SERVICE}@${PROJECT_ID}.iam.gserviceaccount.com"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
API_URL="https://escudo-api-${PROJECT_NUMBER}.${REGION}.run.app"

set -a
source "$ROOT/.env.example"
[ -f "$ROOT/.env" ] && source "$ROOT/.env"
set +a
: "${FIREBASE_WEB_API_KEY:?set FIREBASE_WEB_API_KEY in .env (see .env.example)}"

say "Staging build context"
STAGE="$GEN/build-web"
rm -rf "$STAGE" && mkdir -p "$STAGE"
rsync -a --exclude node_modules --exclude .next --exclude .env.local --exclude test-results --exclude playwright-report \
  "$ROOT/web/" "$STAGE/"
# Public client config, inlined by `next build` (a Firebase web API key is not a secret).
cat > "$STAGE/.env.production" <<ENV
NEXT_PUBLIC_ESCUDO_API_URL=${API_URL}
NEXT_PUBLIC_FIREBASE_API_KEY=${FIREBASE_WEB_API_KEY}
NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=${PROJECT_ID}.firebaseapp.com
NEXT_PUBLIC_FIREBASE_PROJECT_ID=${PROJECT_ID}
ENV

# min-instances 0 by default; `make stage-on` keeps one warm on talk days. no-cpu-throttling + 1 h
# timeout: the presenter keeps a Server-Sent Events stream open for the whole talk.
MIN_INSTANCES="${MIN_INSTANCES:-0}"
deploy_run "$SERVICE" "$STAGE" \
  --service-account="$SA" \
  --allow-unauthenticated \
  --min-instances="$MIN_INSTANCES" --max-instances=2 \
  --cpu=1 --memory=512Mi --no-cpu-throttling --timeout=3600 \
  --set-env-vars="GOOGLE_CLOUD_PROJECT=${PROJECT_ID},FIRESTORE_DATABASE=${FIRESTORE_DATABASE}"

URL="https://${SERVICE}-${PROJECT_NUMBER}.${REGION}.run.app"
say "Deployed: $URL"
echo "  Portal:     $URL/"
echo "  Presenter:  $URL/presenter"
