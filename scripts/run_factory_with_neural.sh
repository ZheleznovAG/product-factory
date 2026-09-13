#!/usr/bin/env bash
# Запуск фабрики с нейросервисом: gateway + контейнер фабрики, один прогон, audit.
# Перед стартом: останавливает старый контейнер и освобождает порты 8080 и 8090.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
[ -f .env ] && set -a && . .env && set +a

# Подключаем общие функции (остановка контейнера, освобождение портов)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/common.sh"

GATEWAY_PORT="${NEURAL_GATEWAY_PORT:-8090}"
FACTORY_PORT="${FACTORY_PORT:-8080}"
FACTORY_NEURAL_URL="${FACTORY_NEURAL_URL:-http://host.docker.internal:$GATEWAY_PORT}"
CONTAINER_NAME="${FACTORY_CONTAINER_NAME:-product-factory-run}"

# 1. Останавливаем контейнер и освобождаем порты (чтобы не было «address already in use»)
echo "=== 1. Подготовка: контейнер и порты ==="
stop_container "$CONTAINER_NAME"
free_port "$FACTORY_PORT"
free_port "$GATEWAY_PORT"
sleep 1
echo ""

# 2. Запуск Neural Gateway
echo "=== 2. Запуск Neural Gateway на порту $GATEWAY_PORT ==="
echo "   NEURAL_BACKEND=${NEURAL_BACKEND:-codex} (задайте openai/proxy при необходимости)"
if ! command -v uvicorn >/dev/null 2>&1; then
  echo "   Установка зависимостей: python3 -m pip install -r scripts/neural_gateway/requirements.txt"
  (cd scripts/neural_gateway && python3 -m pip install -q -r requirements.txt)
fi
export NEURAL_BACKEND="${NEURAL_BACKEND:-codex}"
export NEURAL_GATEWAY_PORT="$GATEWAY_PORT"
export NEURAL_GATEWAY_REPO_ROOT="$REPO_ROOT"
(cd scripts/neural_gateway && python3 -m uvicorn main:app --host 0.0.0.0 --port "$GATEWAY_PORT") &
GATEWAY_PID=$!
trap "kill $GATEWAY_PID 2>/dev/null || true; stop_container '$CONTAINER_NAME' 2>/dev/null || true" EXIT

echo "   Ждём готовности gateway..."
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -s "http://127.0.0.1:$GATEWAY_PORT/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -s "http://127.0.0.1:$GATEWAY_PORT/health" >/dev/null || { echo "Gateway не поднялся на порту $GATEWAY_PORT"; exit 1; }
echo "   Gateway готов."
echo ""

# 3. Запуск фабрики в контейнере
echo "=== 3. Запуск фабрики (порт $FACTORY_PORT, NEURAL_SERVICE_URL=$FACTORY_NEURAL_URL) ==="
docker run -d --rm -p "${FACTORY_PORT}:8080" \
  -v "$REPO_ROOT/archetypes:/app/archetypes" \
  -e "NEURAL_SERVICE_URL=$FACTORY_NEURAL_URL" \
  --name "$CONTAINER_NAME" product-factory

echo "   Ждём готовности фабрики..."
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -sS "http://localhost:${FACTORY_PORT}/health/ready" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -sS "http://localhost:${FACTORY_PORT}/health/ready" >/dev/null || { echo "Фабрика не поднялась на порту $FACTORY_PORT"; exit 1; }
echo "   Фабрика готова."

NEURAL_STATUS=$(curl -sS "http://localhost:${FACTORY_PORT}/health/neural")
echo "   Нейросервис (из фабрики): $NEURAL_STATUS"
if echo "$NEURAL_STATUS" | grep -q '"neural_service":"unavailable"'; then
  echo "   Внимание: фабрика не достучалась до gateway — планировщик уйдёт в stub."
fi
echo ""

# 4. Прогон
echo "=== 4. POST /factory/run ==="
RESPONSE=$(curl -sS -X POST "http://localhost:${FACTORY_PORT}/factory/run" \
  -H "Content-Type: application/json" \
  -d '{"goal": "Create catalog API from archetype", "constraints": ["use catalog-service"]}')
echo "$RESPONSE"
RUN_ID=$(echo "$RESPONSE" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')
STATUS=$(echo "$RESPONSE" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')
echo ""
echo "   runId: $RUN_ID"
echo "   status: $STATUS"
echo ""

# 5. Audit
echo "=== 5. Записи audit: agent_planner_call, agent_codegen_call ==="
docker exec "$CONTAINER_NAME" cat /app/audit.log 2>/dev/null | grep -E "agent_planner_call|agent_codegen_call" | tail -5
echo ""
echo "=== Записи по runId ==="
docker exec "$CONTAINER_NAME" cat /app/audit.log 2>/dev/null | grep "$RUN_ID" | head -20
echo ""
echo "Готово. Контейнер: $CONTAINER_NAME (остановить: docker stop $CONTAINER_NAME)."
