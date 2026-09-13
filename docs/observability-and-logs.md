# Дашборды, логи и наблюдение за системой

Краткий обзор: где смотреть, как работает фабрика (метрики, логи, health, артефакты).

---

## 1. Метрики (Prometheus-формат)

**Эндпоинт:** `GET http://localhost:9080/metrics` (при запуске через compose фабрика на 9080).

Всегда доступен без дополнительных сервисов. Формат — Prometheus text.

**Счётчики и сумма длительностей:**

| Метрика | Описание |
|--------|----------|
| `factory_runs_total{status="accepted"}` | Успешные runs (accepted). |
| `factory_runs_total{status="rejected"}` | Отклонённые runs. |
| `factory_run_duration_ms_sum` | Сумма длительностей всех runs (мс). |
| `factory_run_duration_ms_count` | Количество runs. |
| `factory_tool_calls_total` | Всего вызовов tools (create_repo_from_archetype, create_github_repo, push_repo_to_github, apply_patch и т.д.). |
| `factory_llm_calls_total{role="planner"}` | Вызовы LLM-планировщика. |
| `factory_llm_calls_total{role="codegen"}` | Вызовы LLM-кодогена. |
| `factory_llm_input_tokens_total` | Сумма входных токенов LLM. |
| `factory_llm_output_tokens_total` | Сумма выходных токенов LLM. |

**Быстрая проверка:** `curl -s http://localhost:9080/metrics`

---

## 2. Prometheus + Grafana (дашборды)

Включение: profile **observability** в compose:

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d
```

| Сервис | URL | Назначение |
|--------|-----|------------|
| **Prometheus** | http://localhost:9090 | Сбор метрик (scrape фабрики каждые 15s). |
| **Grafana** | http://localhost:3001 | Дашборды по метрикам. Логин: admin/admin. |

**Что уже настроено автоматически (provisioning):**
- В Grafana при старте подхватывается Data source **Prometheus** (URL `http://prometheus:9090`) и дашборды в папке «Product Factory». Ничего вручную добавлять не нужно.
- Базовый обзор: [deploy/grafana/provisioning/dashboards/factory-overview.json](../deploy/grafana/provisioning/dashboards/factory-overview.json)
- SLO и cost: [deploy/grafana/provisioning/dashboards/factory-slo-cost.json](../deploy/grafana/provisioning/dashboards/factory-slo-cost.json)
- Явный дашборд SLO/cost в Grafana: **Factory SLO & Cost** (порт Grafana: `3001`). Открытие: `http://localhost:3001` → **Dashboards** → **Product Factory** → **Factory SLO & Cost**.

### Дашборды Grafana: что смотреть и как открыть

- **Factory overview**: операционный обзор по runs, latency, tool/LLM calls.
- **Factory SLO & Cost**: SLO и cost-сигналы (success rate, latency, tokens, tool calls).
- Файлы provisioning:
  - [deploy/grafana/provisioning/dashboards/factory-overview.json](../deploy/grafana/provisioning/dashboards/factory-overview.json)
  - [deploy/grafana/provisioning/dashboards/factory-slo-cost.json](../deploy/grafana/provisioning/dashboards/factory-slo-cost.json)
- Как открыть:
  1. Поднять observability-профиль: `docker compose -f deploy/docker-compose.yml --profile observability up -d`
  2. Открыть `http://localhost:3001`
  3. Перейти: **Dashboards** → **Product Factory** → выбрать нужный дашборд

---

### Как пользоваться Grafana

1. Открой **http://localhost:3001**, войди: **admin** / **admin** (или логин/пароль из `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` в `.env`).
2. Меню слева: **Dashboards** → папка **Product Factory** → **Factory overview** или **Factory SLO & Cost**. На SLO/cost дашборде: success rate, latency, LLM input/output tokens, tool calls total.
3. Если дашборд пустой — проверь, что Prometheus собирает метрики: в Prometheus (http://localhost:9090) → **Status** → **Targets**: target `factory:8080` должен быть **UP**. Затем в Grafana в дашборде выбери Data source **Prometheus** в выпадающем списке (если есть переменная).
4. Добавить свой график: **Add** → **Visualization** → в запросе укажи, например, `factory_runs_total` или `rate(factory_tool_calls_total[5m])`.

### Как пользоваться Prometheus

1. Открой **http://localhost:9090**.
2. **Status** → **Targets**: должен быть один target **factory** (`factory:8080`) в состоянии **UP**. Если **DOWN** — фабрика не поднята или сеть между контейнерами недоступна.
3. **Graph** (или вкладка **Explore**): в поле запроса введи PromQL, например:
   - `factory_runs_total` — счётчики runs по статусам;
   - `factory_run_duration_ms_sum / factory_run_duration_ms_count` — средняя длительность;
   - `factory_llm_calls_total`, `factory_tool_calls_total`, `factory_llm_input_tokens_total`.

Конфиг Prometheus: [deploy/prometheus.yml](../deploy/prometheus.yml) (target `factory:8080` внутри сети).

---

## 2.1 Алерты (Prometheus rules и Alertmanager)

- Правила алертов лежат в [deploy/prometheus-rules.yml](../deploy/prometheus-rules.yml).
- Подключение rules в Prometheus уже настроено в [deploy/prometheus.yml](../deploy/prometheus.yml) через `rule_files`.
- В compose rules монтируются в контейнер Prometheus: `./prometheus-rules.yml:/etc/prometheus/prometheus-rules.yml:ro` (см. [deploy/docker-compose.yml](../deploy/docker-compose.yml)).

Важно: в текущем `deploy/docker-compose.yml` Alertmanager по умолчанию не поднимается.

Если нужен routing уведомлений, включите Alertmanager отдельно:
1. Добавьте сервис `alertmanager` в compose (или в override-файл) с конфигом `alertmanager.yml`.
2. Добавьте Prometheus-флаг `--alertmanager.url=http://alertmanager:9093`.
3. Перезапустите observability-стек и проверьте состояние правил в Prometheus (`http://localhost:9090` → **Alerts**).

Смежные runbook-разделы:
- [runbook: SLO и cost](runbook.md#slo-и-cost-контроль-и-реакция)
- [runbook: Eval regression gate](runbook.md#eval-regression-gate-trace-grading)

---

### Как пользоваться MinIO (артефакты)

1. Открой **http://localhost:9001**, войди: **minioadmin** / **minioadmin** (или `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` из `.env`).
2. В консоли: **Buckets** → бакет **artifacts**. Его создаёт скрипт `run_full_stack_demo.sh` при первом прогоне; если бакета нет — **Create bucket** → имя `artifacts`.
3. Внутри бакета структура: **artifacts/** → папки по `runId` (например `d584a544-bed1-486d-b5a6-2521f9d48ba2`) → внутри ZIP-архив репозитория (имя вида `pf-<runId>.zip`). Скачиваешь ZIP — получаешь снимок кода, созданного фабрикой для этого run.
4. Ссылку на объект в S3 можно взять из ответа API: `GET http://localhost:9080/factory/runs/{runId}` — поле **artifactLocation** (например `s3://artifacts/artifacts/{runId}/pf-{runId}.zip`).

---

## 3. Audit log (события по каждому run)

**Файл:** задаётся переменной `AUDIT_LOG_PATH` (по умолчанию `audit.log` в текущей директории). В Docker часто маппится в `data/audit.log` через volume `data`.

**Формат:** одна строка — один JSON-объект (JSONL). Поля конверта: `timestamp`, `runId`, `eventType`, `payload` (payload — JSON-строка с деталями).

**Типы событий:**

| eventType | Что означает |
|-----------|--------------|
| `request_received` | Получен запрос на run. |
| `policy_check` | Проверка политики (OPA). |
| `state_changed` | Переход состояния workflow (NEW → PLANNED → … → DONE/REJECTED). В payload — inputDigest, outputDigest. |
| `tool_call_executed` | Выполнен tool (create_repo_from_archetype, create_github_repo, push_repo_to_github, apply_patch и т.д.). |
| `agent_planner_call` | Вызов LLM-планировщика: latency_ms, success, fallback_used, `rationale` (краткое объяснение выбора/фолбэка). |
| `planner_explanation` | Краткое объяснение (`explanation`) решения планировщика (LLM или stub) рядом с `pipeline_plan` для прозрачности и evidence. |
| `agent_codegen_call` | Вызов LLM-кодогена: latency_ms, success, fallback_used. |
| `codegen_patch_set` | Сгенерированный patch set от кодогена. |
| `artifact_registry_updated` | Обновлена запись в артефакт-реестре. |
| `approval_required` | Требуется человеческое подтверждение (human-in-the-loop). |

Событие выбора intent-кандидата (`intent_candidate_selected`) также пишет `explanation` в payload: почему выбран вариант (если пользователь передал `reason`, он дублируется в `explanation`; иначе ставится дефолтная формулировка).

**Как смотреть по runId:**  
`grep '"runId":"<runId>"' data/audit.log` (или путь из `AUDIT_LOG_PATH`).

Подробнее: раздел [Audit log](runbook.md#audit-log) в runbook.

---

## 4. Health-эндпоинты

| Эндпоинт | Назначение |
|----------|------------|
| `GET /health` | Liveness: фабрика жива. Ответ: `{"status":"ok"}`. |
| `GET /health/ready` | Readiness: готовность принимать трафик. Ответ: `{"status":"ready"}`. |
| `GET /health/neural` | Доступность нейросервиса (LLM gateway). Ответ: `{"neural_service":"ok"}` / `"not_configured"` / `"unavailable"`. |

Используются для healthcheck в Docker Compose/Kubernetes и для быстрой проверки перед прогонами.

---

## 5. Реестр артефактов и записи по run

**Метаданные run:** директория `ARTIFACT_REGISTRY_DIR` (по умолчанию `artifact-registry`). В compose часто маппится в `data/artifact-registry/`. Один файл на run: `{runId}.json`.

**Содержимое записи:** runId, workflowState, updatedAt, repositoryVersion, imageVersion, sbomVersion, signatureVersion, **repoUrl** (если создан репо на GitHub), **artifactLocation** (если артефакт залит в S3/MinIO).  
`sbomVersion/signatureVersion` берутся из `ToolStepResult` с приоритетом: `tool result` (`sbom_version`, `signature_version`) → CI env (`FACTORY_SBOM_VERSION`, `FACTORY_SIGNATURE_VERSION`) → post-step файлы в workspace (`sbom-cyclonedx.json`, `cosign.bundle.json`, и т.д.); при отсутствии источника остаются placeholder-значения.

**Через API:** `GET /factory/runs/{runId}` — та же информация в JSON (404, если run не найден).

**Код артефакта (репо):** директория workspace — `FACTORY_WORKSPACE_DIR` (по умолчанию `workspace`), поддиректория `pf-<runId>/`. При использовании S3/MinIO — дополнительно ZIP в бакете по `artifact_location`.

---

## 6. Логи приложения (stdout)

Фабрика пишет в stdout (в Docker — `docker logs <container>`). Там: Ktor/Netty, запросы, при включённом OTel — экспорт span в логи (LoggingSpanExporter). Для детального разбора конкретного run удобнее audit log по runId.

---

## 7. OPA (политики)

Если задан `OPA_URL`, фабрика перед run запрашивает у OPA решение. Decision logs — по настройке самого OPA (в текущем compose OPA не пишет отдельный лог-файл в volume; при необходимости настраивается отдельно).

Ручная проверка политики: [runbook — OPA](runbook.md#opa-политики).

---

## 8. MinIO (артефакты в S3-совместимом хранилище)

При заданных `ARTIFACT_STORAGE_*` фабрика загружает ZIP репо в MinIO. Консоль MinIO: **http://localhost:9001** (логин/пароль из `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`). Бакет и префикс задаются в env; в записи run и в результате tool — поле `artifact_location` (s3://…).

---

## Сводка: что смотреть в типичных сценариях

| Цель | Где смотреть |
|------|----------------|
| «Фабрика жива?» | `curl .../health`, `curl .../health/ready` |
| «LLM доступен?» | `curl .../health/neural` |
| «Сколько runs, сколько токенов?» | `curl .../metrics` или Grafana по тем же метрикам |
| «Что произошло в run X?» | Audit log по runId, при необходимости `GET /factory/runs/{runId}` |
| «Где ссылка на репо / артефакт?» | `GET /factory/runs/{runId}` (repoUrl, artifactLocation) или файл `data/artifact-registry/{runId}.json` |
| «Графики по времени» | Prometheus (9090) + Grafana (3001), profile observability |

---

## Полный прогон со стеком (S3, Prometheus, Grafana)

Скрипт **`scripts/run_full_stack_demo.sh`** поднимает стек с profile `observability` (MinIO, Prometheus, Grafana), создаёт бакет `artifacts`, выполняет один run с задачей посложнее и выводит результат и ссылки.

**Запуск (из корня репо):**
```bash
./scripts/run_full_stack_demo.sh
```
Опционально: в `.env` задайте `GITHUB_TOKEN`, чтобы репо создавался на GitHub.

**После прогона — куда смотреть (подставьте свой `RUN_ID` и порт фабрики):**

| Что | Ссылка или путь |
|-----|------------------|
| **Результат run (JSON)** | `curl -s http://localhost:9080/factory/runs/{RUN_ID}` |
| **Репо на GitHub** | Поле `repoUrl` в ответе выше (если задан GITHUB_TOKEN) |
| **ZIP в S3/MinIO** | Поле `artifactLocation` в ответе; объекты в MinIO в бакете `artifacts`, префикс `artifacts/{runId}/` |
| **Метрики** | http://localhost:9080/metrics |
| **Prometheus** | http://localhost:9090 (запросы: `factory_runs_total`, `factory_llm_calls_total` и т.д.) |
| **Grafana** | http://localhost:3001 (admin/admin). Data source: `http://prometheus:9090` |
| **MinIO Console** | http://localhost:9001 (minioadmin/minioadmin) |
| **Audit log** | `docker compose -f deploy/docker-compose.yml exec factory cat /app/data/audit.log` |
| **События по runId** | `docker compose ... exec factory grep '"runId":"{RUN_ID}"' /app/data/audit.log` |
| **Запись реестра** | В контейнере: `/app/data/artifact-registry/{RUN_ID}.json` |
| **Health** | http://localhost:9080/health , /health/ready , /health/neural |
