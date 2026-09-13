#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <path-to-artifact-manifest.json> [factory-url]"
  exit 1
fi

MANIFEST_PATH="$1"
FACTORY_URL="${2:-${FACTORY_URL:-http://localhost:9080}}"

if [[ ! -f "$MANIFEST_PATH" ]]; then
  echo "Manifest file not found: $MANIFEST_PATH"
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required"
  exit 1
fi

REQUEST_JSON="$(jq -c '{
  goal: .inputs.goal,
  constraints: (.inputs.constraints // []),
  target_stack: .inputs.targetStack,
  contracts: (.inputs.contracts // {})
}' "$MANIFEST_PATH")"

echo "Replaying run from manifest: $MANIFEST_PATH"
echo "Factory URL: $FACTORY_URL"

RESPONSE="$(curl -fsS -X POST "$FACTORY_URL/factory/run" \
  -H "Content-Type: application/json" \
  -d "$REQUEST_JSON")"

RUN_ID="$(echo "$RESPONSE" | jq -r '.runId // empty')"
STATUS="$(echo "$RESPONSE" | jq -r '.status // empty')"

echo "Replay response: $RESPONSE"
if [[ -n "$RUN_ID" ]]; then
  echo "Replayed runId: $RUN_ID (status=$STATUS)"
fi
