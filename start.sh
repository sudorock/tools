#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Allow .env (gitignored) to supply secrets like OPENAI_API_KEY when launched
# outside an interactive shell (e.g. from launchd).
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

docker compose up -d qdrant

echo "Waiting for Qdrant..."
until curl -fsS http://127.0.0.1:7533/readyz >/dev/null 2>&1; do
  sleep 2
done

exec clojure -M:dev -m tools.main
