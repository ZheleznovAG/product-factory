#!/usr/bin/env bash
set -euo pipefail

TARGET_URL="${1:-http://127.0.0.1:18080/health/ready}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-20}"
SLEEP_SECONDS="${SLEEP_SECONDS:-3}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

for ((attempt=1; attempt<=MAX_ATTEMPTS; attempt++)); do
  code="$(curl -s -o "$BODY_FILE" -w "%{http_code}" "$TARGET_URL" || true)"
  if [[ "$code" == "200" ]]; then
    echo "Smoke check passed: $TARGET_URL returned HTTP 200"
    exit 0
  fi
  echo "Attempt $attempt/$MAX_ATTEMPTS: expected 200, got $code; retrying in ${SLEEP_SECONDS}s"
  sleep "$SLEEP_SECONDS"
done

echo "Smoke check failed for $TARGET_URL"
if [[ -f "$BODY_FILE" ]]; then
  echo "Last response body:"
  cat "$BODY_FILE"
fi
exit 1
