#!/usr/bin/env bash
# scripts/jinx-assist/deploy-server.sh — copies the Hermes bridge to the server (new file only, backup if it existed).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
host="${JINX_HERMES_SSH:-server}"
dest="/home/alan/.hermes/scripts/jinx_hermes_chat.py"
stamp="$(date +%Y%m%d-%H%M%S)"
ssh -o BatchMode=yes "$host" "[ -e '$dest' ] && cp -p '$dest' '$dest.bak-$stamp' || true"
scp -q "$here/server/jinx_hermes_chat.py" "$host:$dest"
echo '{"op":"history","n":1}' | ssh -o BatchMode=yes "$host" \
  "/home/alan/.hermes/hermes-agent/venv/bin/python $dest" | head -c 120; echo
echo "ok: puente de Hermes desplegado en $host:$dest"
