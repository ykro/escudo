#!/usr/bin/env bash
# The last-but-one fallback: the production build of the web served from this laptop, no network.
# Fonts are self-hosted (src/app/fonts), so neither the build nor the page fetches anything; open
#   http://127.0.0.1:3217/presenter?demo=1   (a recorded chat run, labelled REPETICIÓN GRABADA)
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${PORT:-3217}"
export NEXT_TELEMETRY_DISABLED=1
[ -d node_modules ] || npm ci --no-audit --no-fund --offline
npm run build
# standalone output does not include static assets or public/: copy them next to server.js
rm -rf .next/standalone/.next/static .next/standalone/public
cp -R .next/static .next/standalone/.next/static
cp -R public .next/standalone/public
echo "Escudo web sin red: http://127.0.0.1:${PORT}/presenter?demo=1"
PORT="$PORT" HOSTNAME=127.0.0.1 exec node .next/standalone/server.js
