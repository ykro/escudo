#!/usr/bin/env bash
# Firebase on the project + the Firebase Auth email/password provider (the seeded customer, `make seed`),
# through the admin REST APIs, no console clicks. Only that field is updated: the Firebase project is
# shared with other demos, so other providers are left exactly as they are.
source "$(dirname "$0")/env.sh"

TOKEN="$(gcloud auth print-access-token)"
api() {  # api <METHOD> <URL> [JSON]
  curl -sS -X "$1" "$2" -H "Authorization: Bearer ${TOKEN}" -H "x-goog-user-project: ${PROJECT_ID}" \
    -H "Content-Type: application/json" ${3:+-d "$3"} -w '\n%{http_code}'
}
status() { tail -n1 <<<"$1"; }

say "Firebase on ${PROJECT_ID}"
out="$(api GET "https://firebase.googleapis.com/v1beta1/projects/${PROJECT_ID}")"
if [ "$(status "$out")" = "200" ]; then
  echo "  already a Firebase project"
else
  out="$(api POST "https://firebase.googleapis.com/v1beta1/projects/${PROJECT_ID}:addFirebase" '{}')"
  echo "  addFirebase -> HTTP $(status "$out") (long-running; re-run this script in a minute)"
fi

say "Firebase Auth (Identity Toolkit)"
CONFIG="https://identitytoolkit.googleapis.com/admin/v2/projects/${PROJECT_ID}/config"
out="$(api GET "$CONFIG")"
if [ "$(status "$out")" != "200" ]; then
  # A project that never opened Authentication has no config yet: initialize it first.
  out="$(api POST "https://identitytoolkit.googleapis.com/v2/projects/${PROJECT_ID}/identityPlatform:initializeAuth" '{}')"
  echo "  initializeAuth -> HTTP $(status "$out")"
fi
out="$(api PATCH "${CONFIG}?updateMask=signIn.email.enabled,signIn.email.passwordRequired" \
  '{"signIn":{"email":{"enabled":true,"passwordRequired":true}}}')"
if [ "$(status "$out")" = "200" ]; then
  echo "  email/password: on"
else
  echo "  could not update the Auth config:"; sed '$d' <<<"$out"; exit 1
fi
