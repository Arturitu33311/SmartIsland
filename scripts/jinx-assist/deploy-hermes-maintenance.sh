#!/usr/bin/env bash
# scripts/jinx-assist/deploy-hermes-maintenance.sh — installs the Telegram retry and the time-based
# session reset on the Hermes server as systemd *user* timers. New files only (backup if present).
# It does not touch the gateway, its .env or its service; see docs/ASSISTANT.md for the route fix.
#   deploy-hermes-maintenance.sh            install scripts + units, enable timers
#   deploy-hermes-maintenance.sh --no-enable  copy only
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
host="${JINX_HERMES_SSH:-server}"
stamp="$(date +%Y%m%d-%H%M%S)"
enable=1; [ "${1:-}" = "--no-enable" ] && enable=0
scripts=/home/alan/.hermes/scripts
units=/home/alan/.config/systemd/user

for f in hermes_telegram_retry.py hermes_session_time_reset.py; do
  ssh -o BatchMode=yes "$host" "[ -e '$scripts/$f' ] && cp -p '$scripts/$f' '$scripts/$f.bak-$stamp' || true"
  scp -q "$here/server/$f" "$host:$scripts/$f"
done
ssh -o BatchMode=yes "$host" "mkdir -p '$units'"
for u in hermes-telegram-retry.service hermes-telegram-retry.timer \
         hermes-session-time-reset.service hermes-session-time-reset.timer; do
  ssh -o BatchMode=yes "$host" "[ -e '$units/$u' ] && cp -p '$units/$u' '$units/$u.bak-$stamp' || true"
  scp -q "$here/server/systemd/$u" "$host:$units/$u"
done
if [ "$enable" = 1 ]; then
  ssh -o BatchMode=yes "$host" 'export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user daemon-reload &&
    systemctl --user enable --now hermes-telegram-retry.timer hermes-session-time-reset.timer &&
    systemctl --user list-timers --no-pager | grep -E "hermes-(telegram-retry|session-time-reset)"'
fi
echo "ok: mantenimiento de Hermes desplegado en $host"
