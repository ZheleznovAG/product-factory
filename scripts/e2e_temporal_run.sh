#!/usr/bin/env bash
# E2E прогон фабрики в Temporal-режиме:
# 1) поднимает temporal-профиль (или использует уже запущенный стек),
# 2) запускает POST /factory/run,
# 3) опрашивает GET /factory/runs/{runId} до STAGED/DONE.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

FACTORY_URL="${FACTORY_URL:-http://localhost:9080}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/docker-compose.yml}"
TIMEOUT_SECONDS="${FACTORY_RUN_TIMEOUT:-120}"

# Требование Temporal-режима для фабрики.
export TEMPORAL_ADDRESS="temporal:7233"

if ! command -v docker >/dev/null 2>&1; then
  echo "Ошибка: docker не найден в PATH."
  exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Ошибка: curl не найден в PATH."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Ошибка: Docker daemon не запущен."
  exit 1
fi

have_jq=0
if command -v jq >/dev/null 2>&1; then
  have_jq=1
fi

json_get() {
  local json="$1"
  local key="$2"
  if [ "$have_jq" -eq 1 ]; then
    echo "$json" | jq -r --arg k "$key" '.[$k] // empty'
  else
    echo "$json" | sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p"
  fi
}

container_health() {
  local container_id="$1"
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true
}

compose_ps_q() {
  local service="$1"
  docker compose -f "$COMPOSE_FILE" ps -q "$service" 2>/dev/null || true
}

wait_factory_health() {
  local max_wait=150
  local waited=0
  while [ "$waited" -lt "$max_wait" ]; do
    if curl -sf "$FACTORY_URL/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
    waited=$((waited + 3))
  done
  return 1
}

is_stack_ready=0
temporal_cid="$(compose_ps_q temporal)"
factory_cid="$(compose_ps_q factory)"

if [ -n "$temporal_cid" ] && [ -n "$factory_cid" ]; then
  temporal_health="$(container_health "$temporal_cid")"
  factory_health="$(container_health "$factory_cid")"
  factory_temporal_address="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$factory_cid" 2>/dev/null | sed -n 's/^TEMPORAL_ADDRESS=//p' | tail -n1)"
  if [ "$temporal_health" = "healthy" ] && [ "$factory_health" = "healthy" ] && [ "$factory_temporal_address" = "$TEMPORAL_ADDRESS" ]; then
    is_stack_ready=1
  fi
fi

if [ "$is_stack_ready" -eq 1 ]; then
  echo "Temporal-стек уже запущен и готов."
else
  echo "Запуск стека с Temporal через docker compose..."
  docker compose -f "$COMPOSE_FILE" --profile temporal up -d
  if ! wait_factory_health; then
    echo "Фабрика не отвечает на $FACTORY_URL/health после запуска compose."
    echo "Проверьте логи: docker compose -f $COMPOSE_FILE logs factory temporal"
    exit 1
  fi
fi

echo "POST $FACTORY_URL/factory/run"
post_response="$(curl -sS -w "\n%{http_code}" -X POST "$FACTORY_URL/factory/run" \
  -H "Content-Type: application/json" \
  -d '{"goal":"Temporal E2E run","constraints":[],"target_stack":"web-app"}' \
  --max-time 30)"
post_code="$(echo "$post_response" | tail -n1)"
post_body="$(echo "$post_response" | sed '$d')"

if [ "$post_code" != "200" ] && [ "$post_code" != "202" ]; then
  echo "POST /factory/run вернул HTTP $post_code"
  echo "$post_body"
  exit 1
fi

run_id="$(json_get "$post_body" runId)"
if [ -z "$run_id" ]; then
  echo "Не удалось извлечь runId из ответа:"
  echo "$post_body"
  exit 1
fi

echo "runId=$run_id"

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
last_body=""
last_state=""
attempt=0

while true; do
  attempt=$((attempt + 1))
  get_response="$(curl -sS -w "\n%{http_code}" "$FACTORY_URL/factory/runs/$run_id" || true)"
  get_code="$(echo "$get_response" | tail -n1)"
  get_body="$(echo "$get_response" | sed '$d')"
  last_body="$get_body"

  if [ "$get_code" = "200" ]; then
    state="$(json_get "$get_body" workflowState)"
    last_state="$state"
    echo "[$attempt] workflowState=${state:-<empty>}"
    if [ "$state" = "STAGED" ] || [ "$state" = "DONE" ]; then
      echo "Успех: run $run_id достиг состояния $state."
      if [ "$have_jq" -eq 1 ]; then
        echo "$get_body" | jq .
      else
        echo "$get_body"
      fi
      exit 0
    fi
  else
    echo "[$attempt] GET /factory/runs/$run_id -> HTTP $get_code"
  fi

  now="$(date +%s)"
  if [ "$now" -ge "$deadline" ]; then
    echo "Таймаут: run $run_id не достиг STAGED/DONE за ${TIMEOUT_SECONDS} сек."
    [ -n "$last_state" ] && echo "Последний workflowState: $last_state"
    [ -n "$last_body" ] && echo "Последний ответ: $last_body"
    exit 1
  fi

  sleep_for=$((5 + RANDOM % 6))
  sleep "$sleep_for"
done
