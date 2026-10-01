#!/usr/bin/env bash
# One service account for escudo-api, with the least it needs:
#   Firestore: only the `escudo` database (IAM condition), Storage: only the claims bucket,
#   Vertex AI: call models, FCM: send the approval push.
source "$(dirname "$0")/env.sh"

SA_NAME=escudo-api
SA="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
BUCKET="${CLAIMS_BUCKET:-${PROJECT_ID}-escudo-claims}"
DB="${FIRESTORE_DATABASE:-escudo}"

say "Service account ${SA}"
if ! exists gcloud iam service-accounts describe "$SA"; then
  gcloud iam service-accounts create "$SA_NAME" --display-name="Escudo API (Cloud Run)"
  sleep 5  # IAM is eventually consistent
fi

grant() {  # grant <role> [condition]
  gcloud projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA" --role="$1" \
    --condition="${2:-None}" --quiet >/dev/null
  echo "  $SA -> $1"
}

say "Project roles"
grant roles/datastore.user \
  "expression=resource.name==\"projects/${PROJECT_ID}/databases/${DB}\",title=escudo-db-only,description=Firestore database ${DB} only"
grant roles/aiplatform.user
grant roles/firebasecloudmessaging.admin   # the narrowest predefined role with cloudmessaging.messages.create

say "Bucket gs://${BUCKET}"
gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
  --member="serviceAccount:$SA" --role=roles/storage.objectUser >/dev/null
echo "  $SA -> roles/storage.objectUser (bucket only)"
