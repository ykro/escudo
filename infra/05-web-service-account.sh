#!/usr/bin/env bash
# escudo-web only reads the presenter timeline: Firestore `escudo`, read-only.
source "$(dirname "$0")/env.sh"

SA_NAME=escudo-web
SA="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
DB="${FIRESTORE_DATABASE:-escudo}"

say "Service account ${SA}"
if ! exists gcloud iam service-accounts describe "$SA"; then
  gcloud iam service-accounts create "$SA_NAME" --display-name="Escudo web (Cloud Run)"
  sleep 5
fi
gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role=roles/datastore.viewer \
  --condition="expression=resource.name==\"projects/${PROJECT_ID}/databases/${DB}\",title=escudo-db-read-only,description=Firestore database ${DB} read-only" \
  --quiet >/dev/null
echo "  $SA -> roles/datastore.viewer (database ${DB} only)"
