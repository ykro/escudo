#!/usr/bin/env bash
# APIs the Escudo API uses (idempotent: already-enabled APIs are left as they are).
source "$(dirname "$0")/env.sh"

say "Enabling APIs"
gcloud services enable \
  run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  aiplatform.googleapis.com firestore.googleapis.com storage.googleapis.com \
  fcm.googleapis.com identitytoolkit.googleapis.com iam.googleapis.com firebase.googleapis.com \
  secretmanager.googleapis.com
