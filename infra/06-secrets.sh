#!/usr/bin/env bash
# Secrets for escudo-api in Secret Manager, mounted by Cloud Run (never plain env vars, never in git):
#   escudo-admin-token  -> ADMIN_TOKEN        (POST /admin/reset)
#   escudo-a2a-secret   -> A2A_SHARED_SECRET  (Escudo <-> Tow/Repair, X-Escudo-A2A-Key)
# Created once with a random value (or the value from a previous local infra/.generated file, so a
# migration keeps working clients); escudo-api may read these two secrets and nothing else.
source "$(dirname "$0")/env.sh"

SA="escudo-api@${PROJECT_ID}.iam.gserviceaccount.com"
GEN="$INFRA_DIR/.generated"

ensure_secret() {  # ensure_secret <name> <legacy-local-file>
  local name="$1" legacy="$2"
  if ! exists gcloud secrets describe "$name"; then
    gcloud secrets create "$name" --replication-policy=automatic --labels=app=escudo >/dev/null
    echo "  created $name"
  fi
  if [ -z "$(gcloud secrets versions list "$name" --filter='state=ENABLED' --format='value(name)' --limit=1)" ]; then
    if [ -s "$legacy" ]; then value="$(cat "$legacy")"; else value="$(openssl rand -hex 24)"; fi
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- >/dev/null
    echo "  $name: first version added"
  else
    echo "  $name: has a version"
  fi
  [ -f "$legacy" ] && rm -f "$legacy" && echo "  removed local copy $legacy"
  gcloud secrets add-iam-policy-binding "$name" --member="serviceAccount:$SA" \
    --role=roles/secretmanager.secretAccessor --quiet >/dev/null
  echo "  $SA -> secretAccessor on $name only"
}

say "Secret Manager"
ensure_secret escudo-admin-token "$GEN/admin_token"
ensure_secret escudo-a2a-secret "$GEN/a2a_secret"
