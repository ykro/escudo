#!/usr/bin/env bash
# Shared settings for every infra script. Source it: `source infra/env.sh`.
# APP must be set by the repo's infra/app.env before sourcing this file.
set -euo pipefail

INFRA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
[ -f "$INFRA_DIR/app.env" ] && source "$INFRA_DIR/app.env"

export PROJECT_ID="${PROJECT_ID:-ai-experiments-487722}"
export REGION="${REGION:-us-central1}"
export TRAIN_REGION="${TRAIN_REGION:-europe-west4}"
export CLOUDSDK_CORE_PROJECT="$PROJECT_ID"   # scope gcloud without touching the global config
export AR_REPO="${AR_REPO:-charlas2027}"
export AR="${REGION}-docker.pkg.dev/${PROJECT_ID}/${AR_REPO}"
: "${APP:?set APP in infra/app.env}"

say() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
exists() { "$@" >/dev/null 2>&1; }

# deploy_run <service> <source-dir> [extra gcloud run deploy flags...]
# Builds with Cloud Build into Artifact Registry and deploys to Cloud Run.
deploy_run() {
  local service="$1" src="$2"; shift 2
  local image="${AR}/${service}:$(date +%Y%m%d-%H%M%S)"
  say "Building ${service} from ${src}"
  gcloud builds submit "$src" --tag "$image" --region "$REGION" --quiet
  say "Deploying ${service}"
  gcloud run deploy "$service" --image "$image" --region "$REGION" --quiet "$@"
  gcloud run services describe "$service" --region "$REGION" --format='value(status.url)'
}
