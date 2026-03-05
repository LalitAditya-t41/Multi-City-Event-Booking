#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "$MODE" ]]; then
  echo "Usage: scripts/seed-local.sh [reset|append]"
  exit 1
fi

if [[ "$MODE" != "reset" && "$MODE" != "append" ]]; then
  echo "Invalid mode: $MODE"
  echo "Expected: reset or append"
  exit 1
fi

cd "$ROOT_DIR"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1"
    exit 1
  fi
}

require_cmd docker
require_cmd curl

echo "Checking required containers..."
for service in postgres mock-eventbrite; do
  if ! docker compose ps --status running --services | grep -qx "$service"; then
    echo "Service '$service' is not running. Start with: docker compose up -d"
    exit 1
  fi
done

if [[ "$MODE" == "reset" ]]; then
  echo "Resetting Java app DB data..."
  docker compose exec -T postgres psql -U eventplatform -d eventplatform -v ON_ERROR_STOP=1 -f - < "$ROOT_DIR/docker/seed/heavy_reset.sql"
fi

echo "Applying Java app heavy seed ($MODE)..."
docker compose exec -T postgres psql -U eventplatform -d eventplatform -v ON_ERROR_STOP=1 -f - < "$ROOT_DIR/docker/seed/heavy_append.sql"

echo "Applying mock-eventbrite seed ($MODE)..."
SEED_PAYLOAD=$(printf '{"profile":"heavy","mode":"%s"}' "$MODE")
HTTP_CODE=$(
  curl -sS -o /tmp/mock_seed_response.json -w "%{http_code}" -X POST "http://localhost:8888/mock/seed" \
    -H "Content-Type: application/json" \
    -d "$SEED_PAYLOAD"
)
if [[ "$HTTP_CODE" == "404" ]]; then
  echo "Mock endpoint /mock/seed is unavailable (HTTP 404)."
  echo "Rebuild the mock container to pick up latest code:"
  echo "  docker compose up -d --build mock-eventbrite"
  exit 1
fi
if [[ "$HTTP_CODE" -lt 200 || "$HTTP_CODE" -ge 300 ]]; then
  echo "Mock seeding failed with HTTP $HTTP_CODE"
  cat /tmp/mock_seed_response.json
  exit 1
fi

echo "Seed completed."
echo "Mock response:"
cat /tmp/mock_seed_response.json

echo "Quick checks:"
echo "- http://localhost:8080/api/v1/catalog/cities"
echo "- http://localhost:8888/mock/dashboard"
