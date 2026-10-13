#!/usr/bin/env bash
# Hourly entry point for cron. Paper mode unless every live gate passes.
#   crontab -e  ->  7 * * * * /path/to/Devops/scripts/run_bot.sh >> /path/to/Devops/state/cron.log 2>&1
# Live mode reads API keys from a file OUTSIDE the repo that only you can read:
#   ~/.config/cryptobot/env  (chmod 600) containing:
#     KRAKEN_API_KEY=...
#     KRAKEN_API_SECRET=...
#     CRYPTOBOT_LIVE=I_AUTHORIZE_REAL_MONEY
#     CRYPTOBOT_NTFY_TOPIC=<long-random-topic>   (optional alerts)
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE="${HOME}/.config/cryptobot/env"
MODE_FLAG=""
if [[ -f "$ENV_FILE" ]]; then
  perms=$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE")
  if [[ "$perms" != "600" && "$perms" != "400" ]]; then
    echo "Refusing to load $ENV_FILE: permissions $perms (must be 600 or 400)"; exit 1
  fi
  set -a; source "$ENV_FILE"; set +a
  MODE_FLAG="--live"   # still refused unless config + approval + env phrase all agree
fi
exec python3 -m cryptobot run $MODE_FLAG
