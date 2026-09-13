# Отчёт о выполнении и проверке (2026-02-20)

Проведены: запуск стека, живой прогон, проверка метрик и API, тесты. Итог по компонентам и результатам.

---

## 1. Что сделано (из плана next-steps-plan)

| Компонент | Статус | Проверка |
|-----------|--------|----------|
| **Документ next-steps-plan.md** | Готов | План без крупных блоков: быстрые задачи, средние, автоматизация, GitHub. |
| **GET /metrics** | Реализован и проверен | Prometheus-формат: factory_runs_total, factory_run_duration_ms_*, factory_tool_calls_total, factory_llm_calls_total. |
| **Prometheus + Grafana в compose** | Добавлены | Profile `observability`: сервисы prometheus (9090), grafana (3000). |
| **GET /factory/runs/{runId}** | Реализован и проверен | Возвращает ArtifactRunRecord (runId, workflowState, updatedAt, repositoryVersion и т.д.). 404 при отсутствии. |
| **ArtifactRegistry.get()** | Реализован | FileArtifactRegistry и NoopArtifactRegistry. |
| **Скрипт run_live_run.sh** | Готов, исправлен под macOS | Использует `sed '$d'` вместо `head -n -1`. Успешно выполнен. |
| **CI job live-run** | Добавлен | В .github/workflows/ci.yml: build, compose up, run_live_run.sh, compose down. |
| **Tool create_github_repo** | Реализован | GitHubApiClient + ветка в SandboxToolExecutor. GITHUB_TOKEN из env. |
| **Runbook** | Обновлён | Описание живого прогона и скрипта. |
| **deploy/README** | Обновлён | Секции Observability и GitHub (GITHUB_TOKEN, create_github_repo). |

---

## 2. Запуск и проверки (на хосте)

- **Стек:** `docker compose -f deploy/docker-compose.yml up -d`. Фабрика поднята на порту **8081** (на момент проверки 8080 был занят; в compose по умолчанию снова 8080 для CI и доков).
- **Health:** `curl http://localhost:8081/health` → `{"status":"ok"}`.
- **Живой прогон:** `FACTORY_URL=http://localhost:8081 ./scripts/run_live_run.sh` → **OK**. Получены runId, status=accepted, GET /factory/runs/{runId} вернул 200.
- **Метрики:** `curl http://localhost:8081/metrics` — вывод в формате Prometheus, счётчики runs, duration, tool_calls, llm_calls.
- **GET /factory/runs/{runId}:** Ответ с workflowState=DONE, repositoryVersion, imageVersion, sbomVersion, signatureVersion.
- **Тесты:** `./gradlew test` → **BUILD SUCCESSFUL**.

---

## 3. Состояние контейнеров

| Сервис | Образ | Статус |
|--------|--------|--------|
| **factory** | deploy-factory | Up, healthy (8081→8080) |
| **minio** | minio/minio:latest | Up, healthy (9000, 9001) |
| **opa** | openpolicyagent/opa:0.62.0 | Exited (1). Фабрика при недоступности OPA использует fallback allow, живой прогон прошёл. |

OPA при необходимости перезапустить или проверить политики в `policies/opa/`.

---

## 4. Что не входит в этот отчёт / обновления после отчёта

- Крупные блоки (Temporal, RAG, углубление supply chain) — по плану не делались.
- **FactoryRunTest** с @Ignore — статус не менялся (e2e через Docker/runbook и CI job live-run).

**Добавлено после отчёта:** tool **apply_patch** (repo_name, patch_content), unit-тест; учёт **токенов LLM** (usage из ответа → FactoryMetrics, счётчики factory_llm_input/output_tokens_total в /metrics); tool **push_repo_to_github** (repo_name, owner?), вызов в workflow после create_github_repo при GITHUB_TOKEN; в build-образ добавлен git для тестов.

---

## 5. Итог

- План (next-steps без крупных блоков) выполнен в части: метрики, GET runs, живой прогон, CI live-run, tool create_github_repo, документация.
- Стек поднимается, живой прогон и тесты проходят. Метрики и GET /factory/runs/{runId} работают.
- Для повторной проверки: освободить 8080 (или оставить в compose 8081), выполнить `docker compose -f deploy/docker-compose.yml up -d` и `FACTORY_URL=http://localhost:8081 ./scripts/run_live_run.sh` (или без порта при 8080).
