#!/usr/bin/env bash
# Живой прогон: POST /factory/run, проверка ответа и при необходимости GET /factory/runs/{runId}.
# Фабрика должна быть уже запущена (например docker compose up). По умолчанию 9080 (compose).
set -euo pipefail

FACTORY_URL="${FACTORY_URL:-http://localhost:9080}"
TIMEOUT="${FACTORY_RUN_TIMEOUT:-120}"

echo "=== Live run: $FACTORY_URL ==="
if ! curl -sf "$FACTORY_URL/health" >/dev/null; then
  echo "Фабрика не отвечает на $FACTORY_URL/health. Запустите фабрику (например: docker compose -f deploy/docker-compose.yml up -d)."
  exit 1
fi

RESPONSE=$(curl -sS -w "\n%{http_code}" -X POST "$FACTORY_URL/factory/run" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Live run check","constraints":[],"target_stack":"web-app"}' \
  --max-time "$TIMEOUT")
HTTP_CODE=$(echo "$RESPONSE" | tail -n 1)
HTTP_BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" != "200" ]; then
  echo "POST /factory/run вернул HTTP $HTTP_CODE"
  echo "$HTTP_BODY"
  exit 1
fi

RUN_ID=$(echo "$HTTP_BODY" | sed -n 's/.*"runId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$RUN_ID" ]; then
  echo "В ответе не найден runId"
  echo "$HTTP_BODY"
  exit 1
fi

echo "runId=$RUN_ID"
STATUS=$(echo "$HTTP_BODY" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
echo "status=$STATUS"

# Опционально: запрос информации о run
if command -v curl >/dev/null 2>&1; then
  RUN_INFO=$(curl -sS -w "\n%{http_code}" --max-time 5 "$FACTORY_URL/factory/runs/$RUN_ID" || true)
  RUN_INFO_CODE=$(echo "$RUN_INFO" | tail -n 1)
  if [ "$RUN_INFO_CODE" = "200" ]; then
    echo "GET /factory/runs/$RUN_ID: OK"
  fi
fi

echo "=== Live run OK ==="
exit 0
