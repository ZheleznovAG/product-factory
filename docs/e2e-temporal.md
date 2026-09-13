# E2E: factory run через Temporal

Документ описывает end-to-end проверку запуска workflow фабрики через Temporal без изменений API-контрактов.

См. также: [runbook.md](runbook.md), [../deploy/README.md](../deploy/README.md).

## 1) Запуск стека

```bash
docker compose -f deploy/docker-compose.yml --profile temporal up -d
```

Для сервиса фабрики должна быть задана переменная:

```bash
TEMPORAL_ADDRESS=temporal:7233
```

## 2) Старт E2E run

Вызвать API:

```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal":"E2E test","constraints":[],"target_stack":"web-app"}'
```

Ожидание для Temporal-режима:

- HTTP `202 Accepted`
- `status: "started"`
- в ответе есть `runId`

## 3) Проверка результата run

После завершения workflow проверить запись:

```bash
curl -s http://localhost:9080/factory/runs/{runId}
```

Если запись появляется не сразу, использовать polling до появления run в реестре:

```bash
for i in {1..30}; do
  curl -sf "http://localhost:9080/factory/runs/{runId}" && break
  sleep 2
done
```

## 4) Критерии успеха

- `POST /factory/run` вернул `202`
- по `GET /factory/runs/{runId}` есть запись после завершения workflow
- в артефакт-реестре создана запись для run
- при необходимости run подтверждён в Temporal UI: http://localhost:8081

## Примечание

Документ фиксирует только E2E-проверку режима Temporal. API-контракты (`POST /factory/run`, `GET /factory/runs/{runId}`) остаются без изменений.
