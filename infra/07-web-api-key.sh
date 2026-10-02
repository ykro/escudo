#!/usr/bin/env bash
# Escudo's own Firebase web API key ("Escudo web key"), separate from the project's shared
# auto-created browser key so other apps can restrict theirs without breaking Escudo.
#   - API targets: only what the web client's firebase/auth uses (sign-in, token refresh, installations).
#   - Referrers: the escudo-web URLs and the local web on :3217.
# The key string goes to Secret Manager (escudo-web-api-key); infra/11-deploy-web.sh and the scenario
# runners read it from there. Never in git.
#
# A referrer check only limits casual reuse of a key that ships in every browser: anyone can send any
# Referer header. What Escudo enforces is server-side: every call acts as the customer_id of the
# verified Firebase ID token, and approving needs the phone's signature.
source "$(dirname "$0")/env.sh"

NAME="Escudo web key"
SECRET=escudo-web-api-key
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
REFERRERS="https://escudo-web-${PROJECT_NUMBER}.${REGION}.run.app/*,https://escudo-web-z63pqhhmxq-uc.a.run.app/*,http://localhost:3217/*,http://127.0.0.1:3217/*"
TARGETS=(--api-target=service=identitytoolkit.googleapis.com
         --api-target=service=securetoken.googleapis.com
         --api-target=service=firebaseinstallations.googleapis.com)

say "Firebase web API key for escudo-web"
KEY_ID="$(gcloud services api-keys list --filter="displayName='${NAME}'" --format='value(uid)' --limit=1)"
if [ -z "$KEY_ID" ]; then
  gcloud services api-keys create --display-name="$NAME" --allowed-referrers="$REFERRERS" "${TARGETS[@]}" \
    --quiet >/dev/null 2>&1
  KEY_ID="$(gcloud services api-keys list --filter="displayName='${NAME}'" --format='value(uid)' --limit=1)"
  echo "  created ${NAME} (${KEY_ID})"
else
  # Re-apply the restrictions so the script also repairs drift.
  gcloud services api-keys update "$KEY_ID" --allowed-referrers="$REFERRERS" "${TARGETS[@]}" --quiet >/dev/null 2>&1
  echo "  ${NAME} (${KEY_ID}): restrictions re-applied"
fi
KEY="$(gcloud services api-keys get-key-string "$KEY_ID" --format='value(keyString)')"

exists gcloud secrets describe "$SECRET" || {
  gcloud secrets create "$SECRET" --replication-policy=automatic --labels=app=escudo >/dev/null
  echo "  created secret $SECRET"
}
CURRENT="$(gcloud secrets versions access latest --secret="$SECRET" 2>/dev/null || true)"
if [ "$CURRENT" != "$KEY" ]; then
  printf '%s' "$KEY" | gcloud secrets versions add "$SECRET" --data-file=- >/dev/null
  echo "  $SECRET: new version"
else
  echo "  $SECRET: up to date"
fi
echo "  referrers: ${REFERRERS//,/ }"
