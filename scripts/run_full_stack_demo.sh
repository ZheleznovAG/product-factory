#!/usr/bin/env bash
# Полный прогон с S3 (MinIO), Prometheus, Grafana: поднимает стек, создаёт бакет, выполняет run, выводит ссылки.
# Требует: Docker, из корня репо. Опционально: .env с GITHUB_TOKEN для создания репо на GitHub.

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"
[ -f .env ] && set -a && . ./.env && set +a

if ! docker info >/dev/null 2>&1; then
  echo "Ошибка: Docker daemon не запущен. Запустите Docker Desktop (или сервис Docker) и повторите."
  exit 1
fi

# Порты зафиксированы в compose: фабрика 9080, Grafana 3001, Prometheus 9090. Без переменных.
FACTORY_URL="http://localhost:9080"
COMPOSE_FILE="deploy/docker-compose.yml"
ENV_FILE="${REPO_ROOT}/.env"
COMPOSE_ENV=""
[ -f "$ENV_FILE" ] && COMPOSE_ENV="--env-file $ENV_FILE"

echo "=== 1. Остановка старого стека ==="
docker compose -f "$COMPOSE_FILE" $COMPOSE_ENV down 2>/dev/null || true

echo "=== 2. Запуск стека с observability (MinIO, OPA, Prometheus, Grafana) ==="
docker compose -f "$COMPOSE_FILE" $COMPOSE_ENV --profile observability up -d
# Ждём готовности MinIO и фабрики
echo "Ожидание MinIO и фабрики (до 90 сек; фабрика на JVM может грузиться 30–60 сек)..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:9000/minio/health/live >/dev/null 2>&1; then
    echo "MinIO готов."
    break
  fi
  sleep 2
done
for i in $(seq 1 45); do
  if curl -sf "$FACTORY_URL/health" >/dev/null 2>&1; then
    echo "Фабрика готова."
    break
  fi
  [ $((i % 5)) -eq 0 ] && echo "  ... фабрика ещё не отвечает (${i}0 сек)"
  sleep 2
done
curl -sf "$FACTORY_URL/health" || {
  echo "Фабрика не отвечает на $FACTORY_URL"
  echo "Проверьте логи: docker compose -f $COMPOSE_FILE logs factory"
  exit 1
}

echo "=== 3. Создание бакета artifacts в MinIO (если нет) ==="
NET=$(docker compose -f "$COMPOSE_FILE" ps -q factory 2>/dev/null | head -1 | xargs docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
if [ -n "$NET" ]; then
  docker run --rm --network "$NET" minio/mc alias set myminio http://minio:9000 minioadmin minioadmin 2>/dev/null && \
  docker run --rm --network "$NET" minio/mc mb myminio/artifacts --ignore-existing 2>/dev/null && echo "Бакет artifacts готов." || echo "Создайте бакет вручную: http://localhost:9001 → Buckets → Create bucket → artifacts"
else
  echo "Создайте бакет вручную: http://localhost:9001 → Buckets → Create bucket → artifacts"
fi

echo "=== 4. Запуск прогона (задача посложнее) ==="
RESP=$(curl -s -X POST "$FACTORY_URL/factory/run" \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Микросервис каталога товаров: REST API на Ktor, health endpoints, миграции БД, тесты и Docker",
    "constraints": ["Kotlin", "Ktor", "без внешних очередей"],
    "target_stack": "catalog-service",
    "budget": { "token_budget": 100000, "tool_calls_budget": 30, "wall_clock_seconds": 600 }
  }')
echo "$RESP" | python3 -m json.tool 2>/dev/null || echo "$RESP"
RUN_ID=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('runId',''))" 2>/dev/null)
if [ -z "$RUN_ID" ]; then
  echo "Нет runId в ответе. Проверьте фабрику и повторите."
  exit 1
fi
echo "runId: $RUN_ID"

echo "=== 5. Ожидание завершения (до 90 сек) ==="
for i in $(seq 1 18); do
  sleep 5
  RECORD=$(curl -s "$FACTORY_URL/factory/runs/$RUN_ID")
  STATE=$(echo "$RECORD" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('workflowState',''))" 2>/dev/null)
  echo "  [$i] workflowState: $STATE"
  if [ "$STATE" = "DONE" ]; then break; fi
  if echo "$RECORD" | grep -q '"workflowState":"REJECTED"'; then break; fi
done

echo ""
echo "=== 6. Результат run ==="
curl -s "$FACTORY_URL/factory/runs/$RUN_ID" | python3 -m json.tool

echo ""
echo "=== 7. Ссылки и пути для просмотра ==="
echo "Фабрика:        $FACTORY_URL"
echo "Health:         $FACTORY_URL/health"
echo "Метрики:        $FACTORY_URL/metrics"
echo "Prometheus:     http://localhost:9090"
echo "Grafana:        http://localhost:3001  (admin/admin), Data source Prometheus: http://prometheus:9090"
echo "MinIO Console:  http://localhost:9001  (minioadmin/minioadmin), бакет artifacts"
echo "API run:        $FACTORY_URL/factory/runs/$RUN_ID"
echo "Audit log:      в volume factory-data, внутри контейнера /app/data/audit.log (или см. docker compose exec factory cat /app/data/audit.log)"
echo "Реестр:         в volume factory-data, /app/data/artifact-registry/$RUN_ID.json"
echo "События по run: docker compose -f $COMPOSE_FILE exec factory grep '\"runId\":\"$RUN_ID\"' /app/data/audit.log || true"
echo ""
echo "Готово. Откройте Grafana, добавьте Prometheus и постройте дашборд по метрикам factory_*."
