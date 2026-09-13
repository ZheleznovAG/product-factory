#!/usr/bin/env bash
set -euo pipefail

FACTORY_URL="${FACTORY_URL:-http://localhost:8080}"
RUN_ENDPOINT="${FACTORY_URL%/}/factory/run"

REQUEST_BODY='{
  "goal": "Create Kotlin Ktor catalog service repository from archetype",
  "constraints": ["use archetype catalog-service", "enable health endpoints"],
  "productSpec": "catalog-service-v1",
  "contracts": {
    "target_stack": "jvm-ktor"
  }
}'

echo "POST ${RUN_ENDPOINT}"
echo
echo "Request:"
printf '%s\n' "${REQUEST_BODY}"
echo

if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required"
  exit 1
fi

RESPONSE="$(curl -sS -X POST "${RUN_ENDPOINT}" \
  -H "Content-Type: application/json" \
  -d "${REQUEST_BODY}")"

echo "Response:"
printf '%s\n' "${RESPONSE}"
echo

RUN_ID="$(printf '%s' "${RESPONSE}" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')"
STATUS="$(printf '%s' "${RESPONSE}" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"

if [ -n "${RUN_ID}" ]; then
  echo "runId: ${RUN_ID}"
fi
if [ -n "${STATUS}" ]; then
  echo "status: ${STATUS}"
fi

cat <<'EOF'

Проверка результата:
1) audit log: grep <runId> audit.log (или файл из AUDIT_LOG_PATH)
2) найдите событие artifact_registry в audit log
3) если запускалcя create_repo_from_archetype, проверьте созданную директорию репозитория
EOF
