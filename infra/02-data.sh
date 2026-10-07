#!/usr/bin/env bash
# Data and image storage Escudo needs, created only if missing (safe to re-run):
#   Firestore (Native) database `escudo` in nam5, the claims bucket, the Artifact Registry repo.
source "$(dirname "$0")/env.sh"

DB="${FIRESTORE_DATABASE:-escudo}"
BUCKET="${CLAIMS_BUCKET:-${PROJECT_ID}-escudo-claims}"

say "Firestore database ${DB}"
if exists gcloud firestore databases describe --database="$DB"; then
  echo "  exists"
else
  gcloud firestore databases create --database="$DB" --location=nam5 --type=firestore-native
fi

say "Bucket gs://${BUCKET} (private: claim photos)"
if exists gcloud storage buckets describe "gs://${BUCKET}"; then
  echo "  exists"
else
  gcloud storage buckets create "gs://${BUCKET}" --location="$REGION" --uniform-bucket-level-access --public-access-prevention
fi
# Claim photos only matter during one demo run («Empezar de nuevo» deletes the claims, not the
# photos): objects older than 3 days are deleted so rehearsals don't pile up.
LIFECYCLE="$(mktemp)"
echo '{"rule":[{"action":{"type":"Delete"},"condition":{"age":3}}]}' > "$LIFECYCLE"
gcloud storage buckets update "gs://${BUCKET}" --lifecycle-file="$LIFECYCLE" >/dev/null && echo "  lifecycle: claim photos deleted after 3 days"
rm -f "$LIFECYCLE"

say "Artifact Registry ${AR_REPO} (${REGION})"
if exists gcloud artifacts repositories describe "$AR_REPO" --location="$REGION"; then
  echo "  exists"
else
  gcloud artifacts repositories create "$AR_REPO" --repository-format=docker --location="$REGION" \
    --description="Talk demo images (charlas 2027)"
fi
