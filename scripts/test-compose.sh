#!/bin/sh
# Use a fresh project and volume so the first model download is part of the test.
set -eu
cd "$(dirname "$0")/.."
export COMPOSE_PROJECT_NAME="search-check-$$"
export DATA_DIR=./data/sample PORT="${TEST_PORT:-18080}"
base="http://127.0.0.1:$PORT"
python="${PYTHON:-python3}"
cleanup() { docker compose down --volumes >/dev/null; }
trap cleanup EXIT
trap 'exit 130' INT TERM
docker compose up --build -d
attempt=0
until curl -fsS "$base/api/health" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 80 ]; then docker compose logs --tail=25; exit 1; fi
    sleep 1
done
"$python" scripts/check_api.py --url "$base" --require-model "$@"
"$python" scripts/check_ui.py --url "$base"
