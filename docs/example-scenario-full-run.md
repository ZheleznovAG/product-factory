# Детальный сценарий: один полный прогон от запроса до артефакта

Один конкретный пример: как запускается прогон, что происходит внутри фабрики и что вы получаете на выходе.

---

## Исходная ситуация

- На хосте есть репозиторий Product Factory, Docker, опционально файл `.env` с `GITHUB_TOKEN`.
- Нужно получить готовый репозиторий «микросервис каталога на Ktor» и его ZIP в S3-совместимом хранилище (MinIO).

---

## Шаг 0. Запуск стека

Оператор из корня репо выполняет:

```bash
./scripts/run_full_stack_demo.sh
```

Скрипт:

1. Останавливает старый compose.
2. Поднимает стек с профилем **observability**: фабрика (9080), MinIO (9000/9001), OPA (8181), Prometheus (9090), Grafana (3001).
3. Ждёт, пока MinIO и фабрика ответят на health.
4. Создаёт в MinIO бакет **artifacts** (если его ещё нет).
5. Отправляет один запрос к фабрике (см. ниже).
6. Ждёт завершения run (опрос `GET /factory/runs/{runId}` каждые 5 сек до `workflowState: DONE`).
7. Выводит результат и ссылки.

Дальше разберём только то, что делает **фабрика** после прихода запроса.

---

## Шаг 1. Запрос к фабрике

Скрипт выполняет:

```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Микросервис каталога товаров: REST API на Ktor, health endpoints, миграции БД, тесты и Docker",
    "constraints": ["Kotlin", "Ktor", "без внешних очередей"],
    "target_stack": "catalog-service",
    "budget": { "token_budget": 100000, "tool_calls_budget": 30, "wall_clock_seconds": 600 }
  }'
```

**Что это значит:**

- **goal** — текстовое описание задачи (для планировщика/кодогена и для логов).
- **constraints** — ограничения (используются в политиках и при выборе архетипа).
- **target_stack** — явно задаём архетип: `catalog-service` (REST API на Ktor с миграциями и тестами). Альтернатива — `web-app` (простой сайт).
- **budget** — лимиты на токены, число вызовов tools и время выполнения (проверяются политикой и при необходимости могут отклонить run).

**Ответ фабрики (сразу, синхронно):**

```json
{
  "runId": "d584a544-bed1-486d-b5a6-2521f9d48ba2",
  "status": "accepted",
  "message": "Workflow completed with sandbox tool executor."
}
```

- **runId** — уникальный идентификатор этого прогона; по нему потом запрашивают результат и ищут события в audit.
- **status: accepted** — запрос принят в работу; workflow уже выполнен до конца (в текущей реализации фабрика обрабатывает run синхронно в рамках того же HTTP-запроса).

Если бы фабрика отклонила запрос (политика, валидация контрактов, ошибка), в ответе было бы `"status": "rejected"` и сообщение об причине.

---

## Шаг 2. Что происходит внутри workflow (по шагам)

После приёма запроса фабрика выполняет последовательность шагов. Каждый переход состояния и каждый вызов tool пишется в **audit log** (JSONL) с полем `runId`.

### 2.1. request_received

В audit пишется событие: получен запрос, в payload — `goal` (и при необходимости другие поля). Состояние workflow: **NEW**.

### 2.2. Валидация контрактов (если настроена)

Если задан `FACTORY_CONTRACTS_DIR`, фабрика проверяет наличие и валидность пяти YAML (product, constraints, quality_profile, risk_profile, target_stack) по схемам из `contracts/schemas/`. При ошибке — run переходит в **REJECTED**, в ответ API — `status: rejected`.

В нашем сценарии контракты по умолчанию не проверяются (директория не задана), шаг пропускается.

### 2.3. Планирование (plan_workflow) → состояние PLANNED

- Вызывается **политика (OPA)** по адресу `OPA_URL`: разрешён ли run с такими goal и (пока пустым) списком tool calls. Результат (allowed / require_human_approval / denied) пишется в audit как `policy_check`.
- Если OPA вернул «требуется approval», фабрика переводит run в ожидание; подтверждение — через API `POST /factory/approvals/{runId}/approve`. В нашем примере политика по умолчанию разрешает.
- Вызывается **планировщик** (Agent Planner):
  - При заданном `NEURAL_SERVICE_URL` — запрос к LLM (через gateway): «построй план для цели …»; ответ (pipeline_plan, adr_draft, test_plan) пишется в audit как `agent_planner_call` и при необходимости в события типа `pipeline_plan`.
  - Без нейросервиса — **StubAgentPlanner**: возвращает фиксированный план без вызова LLM.
- Состояние переходит в **PLANNED**.

### 2.4. Кодоген (generate_code) → состояние CODE_GENERATED

- Вызывается **кодоген** (Agent Codegen):
  - При `NEURAL_SERVICE_URL` — запрос к LLM: «сгенерируй предложения по коду (патчи) для цели …»; ответ (список предложений с путями файлов и patch/diff) пишется в audit как `agent_codegen_call` и `codegen_patch_set`.
  - Без нейросервиса — **StubAgentCodegen**: возвращает пустой или фиксированный набор предложений.
- Сейчас фабрика **не применяет** эти патчи к репозиторию автоматически в рамках этого сценария: репо создаётся из архетипа «как есть». Патчи остаются в audit для анализа и возможного применения в будущем.
- Состояние переходит в **CODE_GENERATED**.

### 2.5. Выполнение tools (execute_tools)

Планировщик (или встроенная логика по `target_stack` и `goal`) определяет список вызовов tools. Для нашего запроса с `target_stack: catalog-service` и при заданном `GITHUB_TOKEN` формируется цепочка:

1. **create_repo_from_archetype**
2. **create_github_repo**
3. **push_repo_to_github**

Перед каждым вызовом снова вызывается **OPA** (policy check для конкретного tool). Если разрешено и не требуется approval — выполняется tool.

#### Tool 1: create_repo_from_archetype

- **Вход:** `archetype_id: "catalog-service"`, `repo_name: "pf-<runId>"` (нормализованный под имя директории).
- **Действие:** из каталога `archetypes/catalog-service/` копируется всё содержимое в директорию workspace: `workspace/pf-<runId>/`. Там оказывается готовый проект: Ktor, тесты, Dockerfile, миграции БД, README и т.д.
- **Дополнительно:** если заданы `ARTIFACT_STORAGE_*` (endpoint MinIO, бакет, ключи), фабрика упаковывает эту директорию в ZIP и загружает в бакет (например `artifacts/artifacts/<runId>/pf-<runId>.zip`). В результате tool в audit и в ответе появляется поле **artifact_location** (s3://… или эквивалент).
- В audit пишется событие **tool_call_executed** с именем tool и результатом (в т.ч. `repo_path`, `artifact_location` при наличии).

#### Tool 2: create_github_repo

- **Вход:** `repo_name`, `owner` (из `GITHUB_OWNER` или пусто), `private: false`.
- **Действие:** вызов GitHub API (с `GITHUB_TOKEN`): создаётся репозиторий у указанного владельца.
- **Результат:** в ответе tool — `repo_url` (например `https://github.com/owner/pf-<runId>`). Он сохраняется в фабрике и попадёт в артефакт-реестр и в ответ `GET /factory/runs/{runId}`.

#### Tool 3: push_repo_to_github

- **Вход:** `repo_name`, `owner`; фабрика знает `remoteUrl` из предыдущего шага.
- **Действие:** в директории `workspace/pf-<runId>/` выполняется `git init`, `git add .`, `git commit`, `git remote add origin <repo_url>`, `git push -u origin main`.
- **Результат:** код из архетипа оказывается в созданном GitHub-репозитории.

После всех tools состояние переходит в **STAGED**.

### 2.6. Фиксация в реестре (stage_artifacts) → STAGED

Фабрика записывает в **артефакт-реестр** одну запись на runId: состояние **STAGED**, метаданные (repositoryVersion, imageVersion, sbom/signature — пока placeholder), **repoUrl** (если был create_github_repo), **artifactLocation** (если был upload в S3). Событие **artifact_registry_updated** пишется в audit.

### 2.7. Завершение (finish_workflow) → DONE

Состояние переводится в **DONE**. На этом workflow заканчивается.

---

## Шаг 3. Как вы получаете результат

### 3.1. Ответ скрипта

Скрипт после ожидания DONE запрашивает `GET http://localhost:9080/factory/runs/{runId}` и выводит что-то вроде:

```json
{
  "runId": "d584a544-bed1-486d-b5a6-2521f9d48ba2",
  "workflowState": "DONE",
  "updatedAt": "2026-02-24T11:08:17.035182886Z",
  "repositoryVersion": "repo:product-factory/pf-d584a544-bed1-486d-b5a6-2521f9d48ba2:v1",
  "imageVersion": "image:ghcr.io/product-factory/pf-d584a544-bed1-486d-b5a6-2521f9d48ba2:v1",
  "repoUrl": "https://github.com/ZheleznovAG/pf-d584a544-bed1-486d-b5a6-2521f9d48ba2",
  "artifactLocation": "s3://artifacts/artifacts/d584a544-bed1-486d-b5a6-2521f9d48ba2/pf-d584a544-bed1-486d-b5a6-2521f9d48ba2.zip"
}
```

- **repoUrl** — ссылка на репозиторий на GitHub (если был задан `GITHUB_TOKEN`).
- **artifactLocation** — путь к ZIP в S3/MinIO (если настроен artifact storage).

### 3.2. Где взять код и ZIP

- **Код на GitHub:** открыть **repoUrl** в браузере — там полный репо из архетипа catalog-service (Ktor, тесты, Dockerfile, README и т.д.).
- **ZIP в MinIO:** открыть http://localhost:9001 (MinIO Console) → бакет **artifacts** → папка по **runId** → скачать ZIP. Либо использовать S3-совместимый клиент по пути из **artifactLocation**.

### 3.3. Audit log (трассировка)

Чтобы увидеть все события по этому run:

```bash
docker compose -f deploy/docker-compose.yml exec factory grep '"runId":"d584a544-bed1-486d-b5a6-2521f9d48ba2"' /app/data/audit.log
```

В выводе будут строки JSON: request_received → policy_check → state_changed (PLANNED, CODE_GENERATED, …) → tool_call_executed (create_repo_from_archetype, create_github_repo, push_repo_to_github) → artifact_registry_updated → state_changed (DONE). При включённом LLM — также agent_planner_call и agent_codegen_call с latency и success.

### 3.4. Метрики

- **Prometheus:** http://localhost:9090 — запросы вида `factory_runs_total`, `factory_tool_calls_total`, `factory_llm_calls_total`, `factory_run_duration_ms_sum`.
- **Grafana:** http://localhost:3001 — дашборд «Factory overview» с теми же метриками в виде панелей.

---

## Краткая схема «запрос → артефакты»

```
POST /factory/run (goal, constraints, target_stack, budget)
        │
        ▼
┌───────────────────┐
│ request_received   │  audit
└─────────┬─────────┘
          ▼
┌───────────────────┐     ┌─────────────────┐
│ policy_check (OPA) │────►│ allowed?        │──no──► REJECTED
└─────────┬─────────┘     └────────┬────────┘
          │ allowed                 │
          ▼                         ▼
┌───────────────────┐     ┌─────────────────┐
│ agent_planner     │     │ agent_codegen   │  (LLM или stub)
└─────────┬─────────┘     └────────┬────────┘
          ▼                         ▼
┌───────────────────┐
│ create_repo_from   │  → workspace/pf-<runId>/  (+ ZIP → MinIO)
│ _archetype         │
└─────────┬─────────┘
          ▼
┌───────────────────┐     ┌─────────────────┐
│ create_github_repo │────►│ push_repo_      │  (если GITHUB_TOKEN)
│                   │     │ to_github       │
└─────────┬─────────┘     └────────┬────────┘
          ▼                         ▼
┌───────────────────┐
│ artifact_registry │  → запись с repoUrl, artifactLocation
│ (STAGED → DONE)   │
└─────────┬─────────┘
          ▼
GET /factory/runs/{runId}  →  repoUrl, artifactLocation, workflowState: DONE
```

---

## Варианты того же сценария

- **Без GITHUB_TOKEN:** выполняются только create_repo_from_archetype (и при наличии — upload в MinIO). Репо остаётся только в `workspace/` внутри контейнера; **repoUrl** в ответе пустой.
- **Без MinIO (ARTIFACT_STORAGE_*):** create_repo_from_archetype не загружает ZIP; **artifactLocation** пустой; репо по-прежнему в workspace и при наличии токена — на GitHub.
- **Без NEURAL_SERVICE_URL:** планировщик и кодоген — stub; результат тот же (репо из архетипа), но в audit не будет реальных вызовов LLM, только заглушки.

Один и тот же сценарий «запрос → репо + опционально GitHub + опционально S3» покрывается разными комбинациями переменных окружения; шаги workflow и способ получения результата (GET /factory/runs/{runId}, audit, метрики) одинаковые.
