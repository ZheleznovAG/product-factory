# Runbook: запуск, откат, инциденты

**Сводный обзор «как это всё работает и как использовать» (режимы, сценарии, что получаете на выходе):** [usage-overview.md](usage-overview.md). **Архитектура и целевая картина:** [architecture-and-vision.md](architecture-and-vision.md). **Как убедиться, что система работает:** [how-it-works-and-verify.md](how-it-works-and-verify.md). **Архетипы и дорожная карта:** [archetypes-and-roadmap.md](archetypes-and-roadmap.md). **Сервер, Git, хранилища:** [integrations-server-deployment.md](integrations-server-deployment.md). **Контракт execution-окружений (local-docker/remote):** [environments.md](environments.md). **Провижининг раннеров и browser smoke для web-app:** [runner-provisioning-and-browser-smoke.md](runner-provisioning-and-browser-smoke.md).

## Операционный контур: intent, SLO/cost, AI-risk, environments, multi-tenancy, self-serve, rollback

### Intent (быстрый контур)

Цепочка intent->run для self-serve команд:
1. `POST /intent/estimate` — оценка полноты и рисков intent.
2. `POST /experience/generate` — генерация кандидатов решения.
3. `POST /factory/run` — запуск workflow после выбора кандидата.
4. `POST /factory/runs/{runId}/answer` — ответы на вопросы/выбор варианта (при ask_user/approval).

Минимальный API-контракт:
- `apiVersion: productfactory.io/v1`
- Явный `tenantId` в каждом запросе.
- Аудит run и решений обязателен (`AUDIT_LOG_PATH` + `runId` correlation).

### SLO и Cost (операционные цели)

Базовые цели на production/staging:
- `success_rate >= 95%` (окно 30d).
- `p99 run latency <= 7200s` (окно 30d).
- `LLM input tokens <= 80%` budget cap (окно 30d, cap по умолчанию 5,000,000).

Источники истины:
- `GET /metrics` (сырые метрики).
- Prometheus alerts/rules: `deploy/prometheus-rules.yml`.
- Grafana dashboard `Factory SLO & Cost` (profile `observability`).

Реакция на breach:
1. Зафиксировать `runId`, tenant, временное окно и алерт.
2. Ограничить нагрузку (`concurrency`, privileged tools при необходимости).
3. При продолжающейся деградации выполнить rollback по GitOps-процедуре ниже.

### AI-risk (операционные guardrails)

Минимальные обязательные контроли:
- tool allowlist + policy deny для опасных аргументов/секретов;
- manual approvals для privileged/high-risk шагов;
- trace/eval gates (`eval-regression-gate`, `slo-gate`) перед promotion;
- обязательный audit trail (`request_received`, `policy_check`, `tool_call_*`, approval events).

Runbook-реакция при AI-risk инциденте:
1. Зафиксировать `runId`, `tenantId`, policy decision и список tool attempts из audit.
2. Включить safe mode: отключить privileged tools и/или перевести tenant в read-only execution.
3. Ротировать затронутые ключи/секреты, если инцидент связан с потенциальной утечкой.
4. Выполнить post-incident update для [risk-register.md](risk-register.md) и [threat_model.md](threat_model.md).

### Environments (что где запускать)

Матрица окружений:
- `local` (один контейнер): `docker run -p 8080:8080 ...`.
- `compose/dev`: `docker compose -f deploy/docker-compose.yml up -d` (factory `9080`, Grafana `3001`).
- `staging`: desired state в `deploy/staging/*`, smoke `http://127.0.0.1:18080/health/ready`.
- `prod`: desired state в `deploy/prod/*`, smoke `http://127.0.0.1:28080/health/ready`.
- `prod-canary`: desired state в `deploy/prod-canary/*`, smoke `http://127.0.0.1:28081/health/ready`.

Для всех окружений:
- `FACTORY_ENV` должен быть задан (`local|ci|k8s`).
- Для `ci/prod` рекомендуется `POLICY_FAIL_MODE=closed`.
- Все деплои через immutable image digest.

### Canary схема (если применимо)

Canary применяется для production-контура, где есть отдельное desired state окружение `prod-canary`:
- источник кандидата: `deploy/staging/gitops.env`;
- canary desired state: `deploy/prod-canary/gitops.env` + `deploy/prod-canary/docker-compose.yml`;
- full prod desired state: `deploy/prod/gitops.env` + `deploy/prod/docker-compose.yml`.

Когда canary применим:
- есть отдельный canary endpoint (`http://127.0.0.1:28081/health/ready`) и возможность прогонять smoke отдельно от prod;
- деплой идёт по immutable digest (`image@sha256:...`).

Когда canary не применим:
- локальный single-container запуск (`docker run -p 8080:8080 ...`);
- dev/compose-контур без отдельного canary трафика и health endpoint.

Операционная последовательность canary:
1. Подготовить digest в `staging` и проверить `scripts/gitops_apply_env.sh staging --smoke`.
2. Продвинуть digest только в canary: `TARGET=canary scripts/gitops_promote_staging_to_prod.sh`.
3. Применить canary desired state: `scripts/gitops_apply_env.sh prod-canary --smoke`.
4. После окна наблюдения продвинуть в prod: `TARGET=prod scripts/gitops_promote_staging_to_prod.sh` и `scripts/gitops_apply_env.sh prod --smoke`.

### Self-serve (явный путь для потребителя без копипаста)

Операционный онбординг tenant (делает платформа):
1. Выдать `tenantId` и зафиксировать owner/on-call.
2. Назначить budget policy (`COST_BUDGETS_PATH` + лимиты soft/hard cap).
3. Проверить доступность `NEURAL_SERVICE_URL` и `health/neural`.
4. Выполнить smoke цепочку `intent -> experience -> run`.
5. Проверить audit trail (`request_received`, `policy_check`, `tool_call_*`, approvals).

Путь потребителя self-serve (делает команда продукта, без `curl`):
1. Сформулировать задачу и запустить `intent` (варианты + выбор + run):
```bash
docker run -it --rm --network host product-factory:latest \
  /app/bin/product-factory intent \
  --url http://localhost:9080 \
  --tenant team-a \
  --query "Нужен onboarding для B2B SaaS" \
  --variants 3 \
  --pick 1 \
  --run
```
2. Для прямого запуска без этапа intent использовать `run`:
```bash
docker run --rm --network host product-factory:latest \
  /app/bin/product-factory run \
  --url http://localhost:9080 \
  --tenant team-a \
  --goal "Сервис каталога товаров" \
  --constraint "Kotlin" \
  --constraint "Ktor" \
  --target-stack catalog-service
```
3. Проверить состояние run через `status`:
```bash
docker run --rm --network host product-factory:latest \
  /app/bin/product-factory status <runId> --url http://localhost:9080 --tenant team-a
```

Портал (`GET /factory/ui`) опционален: использовать только для human-in-the-loop шагов (ask_user/approval) и операторского мониторинга. API/curl оставлять как fallback для интеграций и автоматизации.

### Rollback за минуты (revert desired state -> verify -> smoke)

SLO/cost rollback trigger:
- sustained `success_rate < 95%`;
- sustained `p99 > 7200s`;
- soft/hard cap budget breach с влиянием на критичный tenant.

Цель: вернуть последний стабильный desired state за минуты, без ручной правки compose.

1. `Revert desired state`:
```bash
# 1) Найти commit, который изменил CATALOG_SERVICE_IMAGE
git log --oneline -- deploy/prod/gitops.env deploy/prod-canary/gitops.env

# 2) Реверснуть проблемный commit (пример для canary)
git revert <bad_commit_sha>

# 3) Закоммитить/запушить revert (если revert не создал commit автоматически)
git push
```

2. `Verify`:
```bash
# Проверить, что в desired state вернулся стабильный digest
git show -- deploy/prod-canary/gitops.env deploy/prod/gitops.env
grep -E '^CATALOG_SERVICE_IMAGE=' deploy/prod-canary/gitops.env deploy/prod/gitops.env
```

3. `Smoke`:
```bash
# Применить откат и выполнить health smoke
scripts/gitops_apply_env.sh prod-canary --smoke
# если откат был для full prod:
scripts/gitops_apply_env.sh prod --smoke
```

Критерий успеха rollback:
- `health/ready` зелёный (`28081` для canary, `28080` для prod);
- нет активных критичных алертов по `success_rate`/`p99`;
- контрольный smoke-run проходит.

## Запуск фабрики (API Gateway)

**Фабрику запускаем только через Docker.** Локальный Gradle — только для сборки образа в CI и для отладки на машине разработчика.

### Docker (штатный способ)

```bash
docker build -t product-factory:latest .
docker run -p 8080:8080 product-factory:latest
```

Audit log на хост:
```bash
mkdir -p data
docker run -p 8080:8080 -v "$(pwd)/data:/app/data" -e AUDIT_LOG_PATH=/app/data/audit.log product-factory:latest
```

**Прогоны с созданием репо из архетипа:** в образ не копируется директория `archetypes/`. Чтобы tool `create_repo_from_archetype` находил архетипы (catalog-service, web-app), монтируйте её:
```bash
docker run -p 8080:8080 -v "$(pwd)/archetypes:/app/archetypes" product-factory:latest
```
Можно комбинировать с volume для audit: `-v "$(pwd)/data:/app/data" -v "$(pwd)/archetypes:/app/archetypes"`.

**Compose:** фабрика всегда на http://localhost:9080. Один контейнер: `docker run -p 8080:8080 ...` — тогда фабрика на 8080; для скриптов задайте `FACTORY_URL=http://localhost:8080`. Подробнее: [deploy/README.md](deploy/README.md).

**Сразу отдавать артефакт в хранилище (S3/MinIO):** задайте `ARTIFACT_STORAGE_BUCKET` (и при необходимости `ARTIFACT_STORAGE_ENDPOINT`, ключи доступа). После создания репо фабрика упакует его в ZIP и загрузит в бакет; в результате tool call будет поле `artifact_location` (s3://…). Подробнее: [integrations-server-deployment.md](integrations-server-deployment.md).

Проверка:
```bash
curl -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal":"API service for X","constraints":[]}'
```
Ожидается ответ с `runId`, `status`, `message`.

### Dry-run Tool Executor (`dry_run: true`)

Для безопасной оценки side-effects без реального выполнения используйте `dry_run` в `POST /factory/run`.

Пример:
```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{
    "goal":"Create catalog service",
    "constraints":[],
    "dry_run": true
  }'
```

Поведение:
- Workflow строит план tool calls, но **не выполняет** side-effects (`create_repo_from_archetype`, `apply_patch`, `create_github_repo`, `push_repo_to_github`).
- В audit пишется событие `tool_call_dry_run_plan` с деталями по каждому шагу:
  - `toolName`, `idempotencyKey`, `arguments`;
  - `policy` (`allowed`, `requireHumanApproval`, `source`, `reason`, `decision`);
  - `approval` и `wouldExecute` (выполнился бы шаг при текущем состоянии policy/approval).
- Дополнительно пишется событие `dry_run_completed`.

Проверка в audit:
```bash
grep '"eventType":"tool_call_dry_run_plan"' audit.log
```

### Multi-tenant (`tenantId`)

- API поддерживает `tenantId` для изоляции run/audit/registry.
- Источник `tenantId` (приоритет): поле JSON `tenantId` → заголовок `X-Tenant-Id` → query `tenantId` → `default`.
- Формат: `^[a-z0-9][a-z0-9._-]{0,62}$`.
- Для `tenantId != default` один и тот же `runId` в разных tenant-ах изолирован в API (`GET /factory/runs/{runId}`, approvals, answer, promotion) и в audit/registry ключах.
- Решение об изоляции зафиксировано в ADR 0014; источник run-level `sbomVersion/signatureVersion` зафиксирован в ADR 0011.

Операционные правила multi-tenancy:
1. Для production/staging запусков передавать явный `tenantId` (не полагаться на `default`).
2. Вести triage SLO/cost и budget breaches tenant-scoped (owner/on-call на tenant).
3. При cross-tenant инциденте немедленно блокировать затронутый tenant path и запускать forensic audit.

### AuthN профиль API (H1-Auth-1.1)

Для API выбран профиль аутентификации: **OIDC Bearer JWT** (access token).
На текущем этапе runtime (H1-Auth-1.2) верификация подписи выполняется по shared secret (`HS256`) вместе с проверкой `issuer`/`audience`/claims mapping.

Фиксированные параметры профиля:
- `AUTH_MODE=oidc_jwt`.
- `AUTH_JWT_ISSUER`: ожидаемый OIDC issuer (например, `https://iam.example.com/realms/product-factory`).
- `AUTH_JWT_AUDIENCE`: ожидаемый audience API (например, `product-factory-api`).
- `AUTH_JWT_CLAIM_TENANT_ID`: claim с tenant (`tenant_id` по умолчанию).
- `AUTH_JWT_CLAIM_SUBJECT`: claim субъекта (`sub` по умолчанию).
- `AUTH_JWT_CLAIM_ROLES`: claim ролей (`roles` по умолчанию, массив строк).
- `AUTH_JWT_HS256_SECRET`: секрет подписи для валидации JWT (текущий runtime профиль H1-Auth-1.2; для local/ci и self-hosted контуров).

Правила runtime mapping (claim -> поле исполнения):

| JWT claim | Runtime поле | Правило |
| --- | --- | --- |
| `tenant_id` (или claim из `AUTH_JWT_CLAIM_TENANT_ID`) | `tenantId` | Обязателен для production/staging. Должен проходить regex `^[a-z0-9][a-z0-9._-]{0,62}$`. |
| `sub` (или claim из `AUTH_JWT_CLAIM_SUBJECT`) | `subject` | Обязателен. Используется как идентификатор вызывающего субъекта в audit/policy context. |
| `roles` (или claim из `AUTH_JWT_CLAIM_ROLES`) | `roles` | Строка или массив строк (например, `["factory.operator","factory.approver"]` или `"factory.operator"`). При отсутствии трактуется как пустой список. |

Нормализация tenant контекста:
1. При включённом AuthN `tenantId` из токена является источником истины для security boundary.
2. Если в запросе также передан `tenantId` (body/header/query), он должен совпадать с claim `tenant_id`; иначе запрос должен отклоняться (`403` в H1-Auth-2).
3. Режим `tenantId=default` допустим только для локальной совместимости и dev-контуров.

Примечание: в текущем этапе документируется профиль и mapping. Обязательная проверка JWT на маршрутах (`/factory/*`, `/intent/*`, `/experience/*`) реализуется в H1-Auth-1.2.

Пример:
```bash
curl -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: team-a" \
  -d '{"goal":"API service for X","constraints":[]}'

curl "http://localhost:9080/factory/runs/<runId>?tenantId=team-a"
```

### Profile Runbook: reset, incognito, ranking

Предпосылки:
- `PROFILE_STORE_ENABLED=true`
- (опционально) `PROFILE_REQUIRE_CONSENT=true`

Базовый профиль:
```bash
curl -X PUT http://localhost:9080/factory/profiles/alice \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId":"acme",
    "embedding":[0.1,0.2,0.3],
    "rules":[{"type":"LIKE","value":"accelerated version"}],
    "consent":{"profileStorage":true}
  }'
```

Сбросить профиль (очистить embedding/rules, сохранить consent):
```bash
curl -X POST http://localhost:9080/factory/profiles/alice/reset \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"acme","reason":"reset_after_experiment"}'
```

Включить инкогнито:
```bash
curl -X POST http://localhost:9080/factory/profiles/alice/incognito \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"acme","enabled":true,"reason":"privacy_mode"}'
```

Выключить инкогнито:
```bash
curl -X POST http://localhost:9080/factory/profiles/alice/incognito \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"acme","enabled":false}'
```

Ранжирование с профилем:
```bash
curl -X POST http://localhost:9080/experience/generate \
  -H "Content-Type: application/json" \
  -d '{
    "apiVersion":"productfactory.io/v1",
    "tenantId":"acme",
    "profileId":"alice",
    "intent":{"outcome":"Ускорить TTFV","experience":"Короткий guided flow"},
    "generation":{"variants":3}
  }'
```

Инкогнито в запросе (игнорирует профильное ранжирование):
```bash
curl -X POST http://localhost:9080/experience/generate \
  -H "Content-Type: application/json" \
  -d '{
    "apiVersion":"productfactory.io/v1",
    "tenantId":"acme",
    "profileId":"alice",
    "incognito":true,
    "intent":{"outcome":"Ускорить TTFV","experience":"Короткий guided flow"},
    "generation":{"variants":3}
  }'
```

Авто-обновление профиля при выборе кандидата:
- шаг `select_intent_candidate` в `POST /factory/runs/{runId}/answer`;
- передайте `profileId`, при необходимости `profileStorageConsent=true`;
- если `incognito=true`, авто-обновление отключается.

**Планировщик Grand Pipeline (scheduled runner):** скрипт `scripts/scheduled_pipeline_runner.py` каждые ~5 часов запускает: возобновление pipeline с последнего шага, либо (после завершения всех шагов) **мозговой штурм** — агенты с докой и интернетом составляют новые доки и новый pipeline в `scripts/.grand_pipeline_steps.json`, затем следующий цикл идёт по новому pipeline; либо только maintenance (аудит, идеи). Lock и state в `scripts/.grand_pipeline_state.json`. Запуск: `python3 scripts/scheduled_pipeline_runner.py --allow-docker`. Один цикл: `--once`. Подробнее: [scheduled-runner-howto.md](scheduled-runner-howto.md).

**Живой прогон (скрипт):** после запуска фабрики можно выполнить автоматический проверочный прогон:
```bash
# Фабрика уже слушает (compose: 9080, один контейнер: 8080)
./scripts/run_live_run.sh
# Полный intent-сценарий: уточнение -> выбор кандидата -> /factory/run -> проверка
bash ./scripts/run_intent_to_run.sh
```
Переменные: `FACTORY_URL` (по умолчанию http://localhost:9080 для compose), `FACTORY_RUN_TIMEOUT` (сек, по умолчанию 120 для `run_live_run.sh`, 180 для `run_intent_to_run.sh`). `run_live_run.sh` делает базовый `POST /factory/run`, проверяет ответ и при успехе — `GET /factory/runs/{runId}`. `run_intent_to_run.sh` выполняет цепочку `POST /intent/estimate` -> `POST /experience/generate` -> выбор кандидата (`CANDIDATE_INDEX`) -> `POST /factory/run` -> polling `GET /factory/runs/{runId}` до `STAGED|DONE`. Оба скрипта используются в CI (job live-run). Если фабрика запущена с **GITHUB_TOKEN**, тот же прогон создаст репозиторий на GitHub и отправит в него код — см. раздел [GitHub: создание репозитория и push](#github-создание-репозитория-и-push).

**Что такое живой прогон (run_live_run.sh):** один запрос к фабрике — `POST /factory/run` с телом `{"goal":"Live run check","constraints":[],"target_stack":"web-app"}`. Workflow выполняет: проверка политики → планировщик (stub или LLM) → кодоген (stub или LLM) → **create_repo_from_archetype** (копирование архетипа web-app в `workspace/pf-<runId>`) → при наличии патчей от LLM — apply_patch → тесты/security (заглушки) → stage → DONE. В ответ приходит `runId` и `status: accepted`; скрипт дополнительно запрашивает `GET /factory/runs/{runId}` и проверяет, что run найден.

**Что создаётся при успешном прогоне:**

| Что | Где | Условие |
|-----|-----|--------|
| **Директория-репозиторий** | Внутри контейнера: `workspace/pf-<runId>/` (копия архетипа, например web-app). На хосте видна только если смонтирован volume: `-v "$(pwd)/workspace:/app/workspace"`. | Всегда при accepted. |
| **Запись в артефакт-реестре** | Файл `<runId>.json` в `ARTIFACT_REGISTRY_DIR`. Поля: runId, workflowState, updatedAt, repositoryVersion, imageVersion, sbomVersion, signatureVersion, **repoUrl**, **artifactLocation**. `sbomVersion/signatureVersion` заполняются по приоритету: `tool result` → `CI env` (`FACTORY_SBOM_VERSION`, `FACTORY_SIGNATURE_VERSION`) → post-step артефакты в workspace (`sbom-cyclonedx.json`, `cosign.bundle.json` и др.). При отсутствии источника используется fallback `sbom:cyclonedx:placeholder-v1`/`signature:cosign:placeholder-v1` (ADR 0011). Читается через `GET /factory/runs/{runId}`. | Всегда при STAGED/DONE. |
| **Выгрузка в S3/MinIO** | Бакет из `ARTIFACT_STORAGE_BUCKET`: ZIP репо загружается туда, в результате tool call — поле `artifact_location` (s3://…). | Если заданы ARTIFACT_STORAGE_* и MinIO доступен. |
| **Репозиторий на GitHub** | Репо с именем `pf-<runId>` на GitHub, код запушен в ветку main. | Только если задан **GITHUB_TOKEN** (и при необходимости GITHUB_OWNER). |

Без монтирования `workspace` и без GitHub/S3 «на выходе» у прогона — только ответ API (runId, status) и запись в артефакт-реестре (и audit log). Скрипт живого прогона проверяет именно это: 200, runId, accepted и что GET /factory/runs/{runId} возвращает запись.

### Где смотреть метрики, логи и дашборды

**Полный обзор:** [observability-and-logs.md](observability-and-logs.md) — метрики, Prometheus/Grafana, audit log, health, реестр артефактов, MinIO. **Полный прогон со стеком (S3, Prometheus, Grafana):** `./scripts/run_full_stack_demo.sh` — поднимает observability, создаёт бакет, выполняет run и выводит все ссылки (см. раздел «Полный прогон со стеком» в observability-and-logs.md).

- **Сырые метрики (Prometheus-формат):** всегда доступны по эндпоинту фабрики:
  ```bash
  curl -s http://localhost:9080/metrics
  ```
  Счётчики: `factory_runs_total{status="accepted|rejected"}`, `factory_run_duration_ms_*`, `factory_tool_calls_total`, `factory_llm_calls_total`, `factory_llm_input_tokens_total`, `factory_llm_output_tokens_total`.

- **Prometheus + Grafana (дашборды):** при запуске с profile `observability` поднимаются Prometheus и Grafana:
  ```bash
  docker compose -f deploy/docker-compose.yml --profile observability up -d
  ```
  - **Prometheus:** http://localhost:9090 — сбор метрик с фабрики (scrape `/metrics`).
  - **Grafana:** http://localhost:3001 (admin/admin). Data source: Prometheus, URL `http://prometheus:9090`. Готовые дашборды уже в provisioning: **Factory overview** и **Factory SLO & Cost**.

Итого: быстрая проверка — `curl .../metrics`; просмотр в виде графиков — через Prometheus + Grafana (profile observability).

### SLO и cost: контроль и реакция

Где смотреть:

- `GET /metrics` на фабрике (быстрая проверка сырых метрик).
- Grafana при запуске с profile `observability`: `http://localhost:3001` (дашборд SLO/Cost и операционные графики).
- Prometheus: `http://localhost:9090` (проверка rule evaluation и raw series).
- Prometheus Alerts: `http://localhost:9090/alerts` (состояния alert rules).

Синхронизация порогов и правил:

- Runtime alerts в Prometheus и CI gate используют одинаковые целевые пороги из [slo-ci-gate.md](slo-ci-gate.md):
  - `success_rate >= 95%`
  - `p50 <= 900s`
  - `p99 <= 7200s`
- Для CI источником правды является `ci/slo-thresholds.json`.
- Для runtime источником правды является [deploy/prometheus-rules.yml](../deploy/prometheus-rules.yml) (смонтирован в Prometheus через compose).

Пороговые значения, которые считаются нарушением:

- `success_rate < 95%`
- `p50 > 900s`
- `p99 > 7200s`
- `LLM input+output tokens за 30d > 80% от budget cap` (soft cap, cap по умолчанию = 5,000,000).
- `LLM input+output tokens за 30d >= 100% budget cap` (hard cap).

Pre-run budget enforcement:

- Перед принятием `run` фабрика проверяет периодические лимиты из `cost-budgets`.
- `429` (`COST_BUDGET_SOFT_CAP_EXCEEDED`) — достигнут soft cap.
- `403` (`COST_BUDGET_HARD_CAP_EXCEEDED`) — достигнут/превышен hard cap.
- Конфиг загружается из `COST_BUDGETS_PATH` (по умолчанию `policies/cost-budgets.yaml`).

Активные alert rules (файл [deploy/prometheus-rules.yml](../deploy/prometheus-rules.yml)):

- `FactoryRunSuccessRateLow30d`
- `FactoryRunLatencyP50TooHigh30d`
- `FactoryRunLatencyP99TooHigh30d`
- `FactoryLlmTokenBudgetSoftCapApproaching30d`
- `FactoryLlmTokenBudgetHardCapExceeded30d`

Операционная интерпретация severity:

- `warning`: деградация SLO/cost, требуется triage и корректирующие действия.
- `critical`: превышен hard cap бюджета, требуется немедленное ограничение нагрузки и/или rollback.

Проверка wiring алертов:

1. `deploy/prometheus.yml` должен содержать `rule_files: /etc/prometheus/prometheus-rules.yml`.
2. `deploy/docker-compose.yml` должен монтировать `./prometheus-rules.yml:/etc/prometheus/prometheus-rules.yml:ro` в сервис `prometheus`.
3. После обновления правил выполнить:
   ```bash
   docker compose -f deploy/docker-compose.yml --profile observability up -d prometheus grafana
   ```
4. Проверить, что правила подхватились: `http://localhost:9090/rules`.

CI enforcement:

- В `.github/workflows/ci.yml` после `live-run` выполняется job `slo-gate`.
- `live-run` публикует артефакт `ci-artifacts/live_run_summary.json` (метрики для gate).
- `slo-gate` запускает `python3 ci/slo_gate.py --input ci-artifacts/live_run_summary.json --mode live --thresholds-config ci/slo-thresholds.json`.
- При нарушении порогов `slo_gate.py` возвращает `exit 1`, job падает, merge блокируется (при branch protection).
- Базовые пороги CI хранятся в `ci/slo-thresholds.json`.
- Детализация режимов gate, входных полей и примеров запуска: [slo-ci-gate.md](slo-ci-gate.md).

Как реагировать при нарушении SLO:

1. Проверить логи и следы выполнения:
   - `docker compose logs -f factory`
   - audit log по `runId` (события policy/gate/tool/agent).
   - состояние алертов и выражений в Prometheus: `http://localhost:9090/alerts`.
2. Выполнить откат на последний стабильный релиз/конфигурацию:
   - откатить образ/теги деплоя;
   - перепроверить `health`, `/metrics` и долю успешных run после отката.
3. Эскалировать инцидент:
   - зафиксировать время, затронутые runId, текущие метрики и предполагаемую причину;
   - уведомить ответственных по on-call/владельца сервиса и открыть incident ticket.

Дополнительно:

- Операционный baseline SLO/cost и rollback-процедуры: текущий раздел runbook + [deploy/prometheus-rules.yml](../deploy/prometheus-rules.yml).
- Детали CI gate и формат артефактов: [slo-ci-gate.md](slo-ci-gate.md).
- Бюджетные лимиты по tenant: [policies/cost-budgets.yaml](../policies/cost-budgets.yaml).

### Health check фабрики

Для liveness/readiness проверки используйте HTTP endpoint фабрики:

```bash
curl -s http://localhost:9080/health
curl -s http://localhost:9080/health/ready
```

Ожидается `200 OK` и минимальный JSON (`{"status":"ok"}` или `{"status":"ready"}`).
Проверка: curl -s http://localhost:9080/health и /health/ready.
Это можно использовать как health probe в оркестраторах (например Kubernetes/Docker Compose) и как быстрый smoke-check в CI перед e2e шагами.

**Проверка нейросервиса (Codex/gateway):** если задан `NEURAL_SERVICE_URL`, перед прогонами имеет смысл убедиться, что обёртка жива:

```bash
curl -s http://localhost:9080/health/neural
```

Ответ: `{"neural_service":"ok"}` — фабрика достучалась до gateway (у него есть GET /health). `"not_configured"` — URL не задан; `"unavailable"` — таймаут или не 200 (проверьте, что gateway запущен и порт совпадает с `NEURAL_SERVICE_URL`).

### Минимальный Web-UI решений (plan/risks/options + answer/approvals)

Открыть UI:

```bash
open http://localhost:9080/factory/ui
```

Scope:
- это минимальная операторская страница для human-in-the-loop решений (`ask_user`/`approval`) без отдельного frontend-сервиса;
- **канонический интерфейс фабрики остаётся API/CLI** (`decision-context`, `answer`, `approvals`), UI — тонкая обёртка над этими endpoint.

Что делает страница:
- загружает `decision-context` по `runId` и `tenantId`;
- показывает `plan` (из audit `pipeline_plan`), `risks` (approval/policy), `options`;
- отправляет решения в `POST /factory/runs/{runId}/answer` и `POST /factory/approvals/{runId}/approve|reject`.

API контекста (для CLI/интеграций):

```bash
curl -s "http://localhost:9080/factory/runs/<runId>/decision-context?tenantId=default" | jq
```

Ожидаемые поля: `artifact`, `approval`, `pendingQuestion`, `plan`, `risks[]`, `options[]`.

### Sprint points (обязательные остановки между фазами)

Назначение: фиксировать ручное решение `proceed|hold|reject` между блоками/фазами (например после WS3), с записью в audit.

Записать sprint point:

```bash
curl -X POST "http://localhost:9080/factory/runs/<runId>/sprint-point" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId":"default",
    "stage":"ws3-v1",
    "decision":"hold",
    "note":"Need security check",
    "decidedBy":"operator"
  }'
```

Ответ: `200` и JSON со статусом решения.  
Audit: событие `sprint_point_decision` (runId, stage, decision, decidedBy, note).

## RAG ingestion в pgvector (archetypes/docs)

Для индексации `archetypes/` и `docs/` используется CLI:

```bash
product-factory rag ingest
product-factory rag status
product-factory rag activate <versionId>
```

Минимально нужно задать `RAG_PGVECTOR_JDBC_URL` и `NEURAL_SERVICE_URL`. Дополнительно: `RAG_INDEX_NAMESPACE`, `RAG_EMBEDDING_MODEL`, `RAG_EMBEDDING_DIM`, `RAG_CHUNK_SIZE`, `RAG_CHUNK_OVERLAP`.

Если включить `RAG_PLANNER_CONTEXT_ENABLED=true`, LLM-планировщик использует top-k контекст из активной версии индекса (опционально, с безопасной деградацией при ошибках).

Детали операций и переменных: [rag-pgvector-operations.md](rag-pgvector-operations.md).

### Локально (только для разработки/CI)

Сборка для образа или тестов: JDK 17 + Gradle, `./gradlew build` (или `gradle build`). Запуск сервера без Docker — только для отладки.

## Запуск factory run (запрос продукта)

Через API: `POST /factory/run` с телом `{"goal": "...", "constraints": ["..."]}`. Статус в ответе; детали — в audit log по `runId`.

### Потребительский CLI (основной self-serve путь)

CLI встроен в бинарь фабрики и умеет базовый потребительский поток:

- `run` — отправить запуск.
- `status` — получить состояние run по `runId`.
- `intent` — оценить intent, показать варианты, выбрать и сразу запустить run.

Примеры:

```bash
# 1) Запуск run
docker run --rm --network host product-factory:latest \
  /app/bin/product-factory run \
  --url http://localhost:9080 \
  --goal "Сервис каталога товаров" \
  --constraint "Kotlin" \
  --constraint "Ktor" \
  --target-stack catalog-service

# 2) Проверка статуса
docker run --rm --network host product-factory:latest \
  /app/bin/product-factory status <runId> --url http://localhost:9080

# 3) Intent -> выбор варианта -> run (интерактивно)
docker run -it --rm --network host product-factory:latest \
  /app/bin/product-factory intent \
  --url http://localhost:9080 \
  --query "Нужен onboarding для B2B SaaS" \
  --variants 3 \
  --target-stack web-app
```

Для неинтерактивного выбора intent: добавьте `--pick <n> --run`.
Портал `GET /factory/ui` — опциональный интерфейс для ручных решений/approval, но не обязательный для стандартного self-serve потока.

### Сценарий Intent -> Experience -> Factory run

1. Оценить intent по пользовательскому запросу:
```bash
curl -s -X POST http://localhost:9080/intent/estimate \
  -H "Content-Type: application/json" \
  -d '{
    "apiVersion": "productfactory.io/v1",
    "tenantId": "acme",
    "requestId": "req-intent-001",
    "session": {
      "sessionId": "sess-2026-02-26-001",
      "references": {
        "selected_ids": ["ref-1", "ref-4"]
      }
    },
    "input": {
      "query": "Нужен onboarding для B2B SaaS, чтобы сократить time-to-first-value",
      "language": "ru",
      "reference_ids": ["ref-1", "ref-4"]
    },
    "constraints": {
      "riskTier": "medium",
      "maxClarifyingQuestions": 2
    }
  }'
```

2. Сгенерировать варианты experience на базе intent:
```bash
curl -s -X POST http://localhost:9080/experience/generate \
  -H "Content-Type: application/json" \
  -d '{
    "apiVersion": "productfactory.io/v1",
    "tenantId": "acme",
    "requestId": "req-exp-001",
    "session": {
      "sessionId": "sess-2026-02-26-001",
      "references": {
        "selected_ids": ["ref-1", "ref-4"],
        "options": [
          {"id":"ref-1","title":"Reference card 1","summary":"Fast progressive onboarding"},
          {"id":"ref-2","title":"Reference card 2","summary":"Conservative baseline"},
          {"id":"ref-3","title":"Reference card 3","summary":"Template-first setup"},
          {"id":"ref-4","title":"Reference card 4","summary":"Interactive assistant"},
          {"id":"ref-5","title":"Reference card 5","summary":"Checklist-first flow"},
          {"id":"ref-6","title":"Reference card 6","summary":"Guided deep-dive"}
        ]
      }
    },
    "intent": {
      "outcome": "Сократить time-to-first-value для новых B2B-пользователей",
      "experience": "Короткий guided flow с прогрессом",
      "constraints": ["Соблюдать tone-of-voice бренда", "Не менять pricing"],
      "reference_ids": ["ref-1", "ref-4"]
    },
    "generation": {
      "variants": 6,
      "includeRationale": true
    }
  }'
```

3. Пользователь выбирает 2 из 6 reference-вариантов, и оба `reference_ids` фиксируются в:
- `intent.reference_ids` (ответ API и следующий запрос),
- `audit payload` событий `intent_estimated` и `experience_generated`.

4. После выбора кандидата (например, `exp-2`) запустить фабрику через `POST /factory/run`:
```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Реализовать onboarding через template-first для B2B SaaS (выбран candidate exp-2)",
    "constraints": [
      "Соблюдать tone-of-voice бренда",
      "Не менять pricing",
      "Сфокусироваться на минимальном time-to-first-value"
    ],
    "target_stack": "web-app"
  }'
```

Автоматизированный вариант этого же сценария (удобно для smoke/CI):
```bash
FACTORY_URL=http://localhost:9080 \
TENANT_ID=acme \
USER_QUERY="Нужен onboarding для B2B SaaS" \
VARIANTS=3 \
CANDIDATE_INDEX=2 \
TARGET_STACK=web-app \
bash ./scripts/run_intent_to_run.sh
```
Требуется `jq` (скрипт валидирует его наличие).

### Режим с Temporal (durable workflow, WS0)

Если задан **TEMPORAL_ADDRESS** (например `localhost:7233` или `temporal:7233` в Docker), фабрика при старте поднимает Temporal Worker и выполняет каждый run как Temporal Workflow. В этом режиме:

- **POST /factory/run** возвращает **202 Accepted** и `status: "started"`; выполнение идёт асинхронно в Worker.
- Persist и retry по шагам обеспечивает Temporal (каждая activity при падении повторяется по настройкам retry).
- Результат run после завершения доступен по **GET /factory/runs/{runId}** (артефакт-реестр обновляется в шагах stage/finish).

Сервер Temporal можно поднять в том же Docker Compose, что и фабрика — профиль **temporal** (см. [deploy/README.md](deploy/README.md)):

```bash
# Из корня репо; в .env задать TEMPORAL_ADDRESS=temporal:7233
docker compose -f deploy/docker-compose.yml --profile temporal up -d
```

Явный пример «живого» прогона с Temporal:

```bash
# В .env (или в environment сервиса factory): TEMPORAL_ADDRESS=temporal:7233
RESP=$(curl -s -i -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal":"Live run via Temporal","constraints":[],"target_stack":"web-app"}')
echo "$RESP"   # ожидается HTTP/1.1 202 Accepted и в JSON status: "started"
```

```bash
RUN_ID=$(echo "$RESP" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')
curl -s http://localhost:9080/factory/runs/$RUN_ID
# Дополнительно: Temporal UI http://localhost:8081 (workflow и history по runId)
```

Поднимаются: PostgreSQL для Temporal, Temporal Server (7233), Temporal UI (8081). Альтернатива: [temporalio/samples-server](https://github.com/temporalio/samples-server) или Temporal Cloud. Переменные: **TEMPORAL_ADDRESS**, **TEMPORAL_NAMESPACE** (по умолчанию `default`). Без TEMPORAL_ADDRESS фабрика работает как раньше: синхронный run в процессе, без persist между перезапусками.
`target_stack` — опциональная строка с целевым стеком (например, `jvm-ktor`) для конкретного запуска. От неё зависит, **какой архетип** будет создан: при `web-app`, `web` или `webapp` — архетип **web-app** (небольшой сайт на Ktor с HTML, /health); при `catalog-service`, `api` или по умолчанию — **catalog-service** (API-сервис). Аналогично по ключевым словам в `goal`: «сайт», «website», «лендинг», «страниц» → web-app.
`budget` — опциональный объект лимитов запуска: `token_budget`, `tool_calls_budget`, `wall_clock_seconds`.

## Проверка с NEURAL_SERVICE_URL (LLM-планировщик)

Чтобы фабрика вызывала реальный LLM для планирования и кодогена, задайте `NEURAL_SERVICE_URL` (и при необходимости `NEURAL_SERVICE_API_KEY`). Без URL используются StubAgentPlanner и StubAgentCodegen.

Удобный способ — скрипт, который **перед стартом останавливает старый контейнер и освобождает порты 9080 и 8090**, затем поднимает Neural Gateway и фабрику, выполняет один прогон и выводит записи `agent_planner_call` из audit:

```bash
./scripts/run_factory_with_neural.sh
```

Если нужно только освободить порты и остановить контейнер: `./scripts/stop_factory_and_gateway.sh`. Переменные: `NEURAL_GATEWAY_PORT` (8090), `FACTORY_PORT` (9080 при compose), `FACTORY_CONTAINER_NAME` (product-factory-run).

По умолчанию gateway в режиме `codex` (Codex CLI на хосте). Для облачного OpenAI задайте перед запуском: `NEURAL_BACKEND=openai OPENAI_API_KEY=sk-...`. Для Ollama на хосте: `NEURAL_BACKEND=proxy PROXY_TARGET_URL=http://host.docker.internal:11434`.

Если нейросервис недоступен или вернул невалидный JSON, фабрика переходит на StubAgentPlanner/StubAgentCodegen (в audit: `agent_planner_call` и `agent_codegen_call` с `fallback_used: true`) и run завершается успешно. Для Codex CLI ответ часто дольше 30 с — таймауты по умолчанию 120 с; переопределение: `PLANNER_LLM_TIMEOUT_SECONDS`, `CODEGEN_LLM_TIMEOUT_SECONDS`. Подробнее: [docs/neural-service-api.md](neural-service-api.md), [docs/factory-2-planner-integration.md](factory-2-planner-integration.md).

## Живые прогоны (запрос -> артефакт)

Фабрика создаёт артефакт вызовом tool `create_repo_from_archetype`: копирует выбранный архетип (catalog-service или web-app) в директорию `workspace/pf-<runId>`. Чтобы получить **небольшой сайт** (архетип web-app), передайте `target_stack: "web-app"` или укажите в goal, например, «небольшой сайт» или «лендинг».

**Пример: запрос на создание сайта**
```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal": "Небольшой сайт-визитка", "constraints": [], "target_stack": "web-app"}'
```
После успешного run артефакт будет в `workspace/pf-<runId>/` (при запуске фабрики в Docker смонтируйте `workspace` на хост, чтобы увидеть файлы: `-v "$(pwd)/workspace:/app/workspace"`).

Ниже пример «живого» запроса к фабрике через `POST /factory/run` (API-сервис).

```bash
curl -s -X POST http://localhost:9080/factory/run \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Create Kotlin Ktor catalog service repository from archetype",
    "constraints": ["use archetype catalog-service", "enable health endpoints"],
    "target_stack": "jvm-ktor",
    "budget": {
      "token_budget": 120000,
      "tool_calls_budget": 20,
      "wall_clock_seconds": 900
    },
    "productSpec": "catalog-service-v1",
    "contracts": {
      "target_stack": "jvm-ktor"
    }
  }'
```

Ожидаемый ответ (`FactoryRunResponse`):

```json
{
  "runId": "8f3f8ddf-95d5-4f7a-96ef-9a64c5f6a40e",
  "status": "accepted",
  "message": "Workflow completed with sandbox tool executor."
}
```

При policy/approval-блокировке вместо этого может прийти:

```json
{
  "runId": "8f3f8ddf-95d5-4f7a-96ef-9a64c5f6a40e",
  "status": "rejected",
  "message": "Human approval required by policy"
}
```

Как быстро проверить результат:

- Audit log: найдите записи по `runId` в `audit.log` (или в файле из `AUDIT_LOG_PATH`), включая `artifact_registry`.
- Артефакт-реестр: событие `artifact_registry` содержит путь и тип созданного артефакта.
- Для `create_repo_from_archetype`: проверьте, что целевая директория репозитория действительно создана на файловой системе.
- Для удобства можно использовать `scripts/run_live_example.sh` (curl + вывод ключевых полей ответа).

## GitHub: создание репозитория и push

Грамотная настройка (получение токена, куда не класть, как передать в compose/docker run): **[github-setup.md](github-setup.md)**.

Если при запуске фабрики заданы **GITHUB_TOKEN** (Personal Access Token с правом `repo`) и при необходимости **GITHUB_OWNER** (логин пользователя или организации), workflow после копирования архетипа в workspace дополнительно:

1. Создаёт репозиторий на GitHub (tool **create_github_repo**).
2. Делает в workspace `git init`, `git add .`, `git commit`, `git push -u origin main` (tool **push_repo_to_github**).

То есть один и тот же живой прогон (POST /factory/run) при наличии токена заканчивается репо на GitHub с кодом архетипа.

### Переменные окружения

| Переменная       | Описание |
|------------------|----------|
| **GITHUB_TOKEN** | Обязательно для создания репо и push. PAT с scope `repo`. |
| **GITHUB_OWNER** | Опционально. Логин пользователя или организации; при пустом репо создаётся под текущим пользователем (по токену), при push owner при необходимости запрашивается через API. |

### Запуск с GitHub

**Docker (один контейнер):**
```bash
docker run -p 8080:8080 \
  -v "$(pwd)/archetypes:/app/archetypes" \
  -e GITHUB_TOKEN=ghp_... \
  -e GITHUB_OWNER=my-org \
  product-factory:latest
```

**Docker Compose (deploy):** задайте `GITHUB_TOKEN` и при необходимости `GITHUB_OWNER` в `environment` сервиса factory в `deploy/docker-compose.yml`. Подробнее: [deploy/README.md](../deploy/README.md).

### Живой прогон с созданием репо на GitHub

1. Запустите фабрику с `GITHUB_TOKEN` (и при необходимости `GITHUB_OWNER`), смонтировав `archetypes` и при желании `workspace`.
2. Выполните обычный запрос:
   ```bash
   ./scripts/run_live_run.sh
   ```
   или
   ```bash
   curl -s -X POST http://localhost:9080/factory/run \
     -H "Content-Type: application/json" \
     -d '{"goal":"API service for X","constraints":[],"target_stack":"web-app"}'
   ```
3. В ответе — `runId`, `status: accepted`. После завершения workflow репозиторий с именем `pf-<runId>` появится на GitHub (у указанного owner или у текущего пользователя токена). Код в нём — копия выбранного архетипа (catalog-service или web-app).

Секреты только через переменные окружения или секреты оркестратора; в репозитории — только примеры в `.env.example`. См. [integrations-server-deployment.md](integrations-server-deployment.md), [secrets-policy.md](secrets-policy.md).

## Архетипы и создание репозитория из архетипа

Доступные архетипы (директории `archetypes/<id>`):

| archetype_id      | Описание                    | CI workflow                      |
|-------------------|-----------------------------|----------------------------------|
| catalog-service   | Ktor API + health, catalog  | ci-archetype-catalog.yml         |
| web-app           | Ktor Web (HTML + /health)   | ci-archetype-web-app.yml         |

Tool **create_repo_from_archetype** создаёт репозиторий копированием архетипа. Параметры: `archetype_id` (обязательный), `repo_name` (по умолчанию = archetype_id). Пример вызова через sandbox executor (в контексте factory run): артефакт — директория с скопированным кодом архетипа.

Локальная проверка копирования (без фабрики):

```bash
# В корне репозитория: архетип копируется в указанную директорию
cp -r archetypes/web-app /tmp/my-web-app
cd /tmp/my-web-app && gradle build
```

Сборка и тесты архетипов в CI: при пуше в `archetypes/catalog-service/**` или `archetypes/web-app/**` запускается соответствующий workflow (build, Docker, supply-chain-gate, staging-smoke).

## Audit log

Файл по умолчанию: `audit.log` в текущей директории (или `AUDIT_LOG_PATH`).

Формат строки JSONL:
- Обязательные поля envelope: `timestamp`, `runId`, `eventType`, `payload`.
- `payload` — JSON-строка события; для `state_changed` и `tool_call_executed` в payload ожидаются `inputDigest` и `outputDigest` (SHA-256 от релевантного входа/выхода шага).

События: `request_received`, `policy_check`, `pipeline_plan`, `adr_draft`, `test_plan`, `policy_check_codegen`, `codegen_patch_set`, `proposal_recorded`, `agent_spec`, `state_changed`, `tool_call_executed`, а также `agent_planner_call` и `agent_codegen_call` (latency_ms, success, fallback_used).

## Supply-chain gate (SBOM + Trivy + Cosign + SLSA provenance)

Для образа **фабрики** (product-factory) и архетипов настроены CI gate:

- **Фабрика (основной образ):** job `supply-chain-factory` в [.github/workflows/ci.yml](../.github/workflows/ci.yml) — при push в `main`: build, push в GHCR, SBOM, Trivy, Cosign.
- **catalog-service:** [.github/workflows/ci-archetype-catalog.yml](../.github/workflows/ci-archetype-catalog.yml)
- **web-app:** [.github/workflows/ci-archetype-web-app.yml](../.github/workflows/ci-archetype-web-app.yml)

В каждом (фабрика и архетипы):

- build/push образа в GHCR;
- генерация SBOM (Syft, CycloneDX JSON);
- vulnerability scan (Trivy) с fail на `HIGH,CRITICAL`;
- подпись образа (Cosign keyless) и `cosign verify`.
- генерация build provenance attestation (`actions/attest-build-provenance@v2`) по digest;
- verify provenance (`gh attestation verify` с `predicate-type=https://slsa.dev/provenance/v1`).

Проверка provenance выполняется по digest (`ghcr.io/<owner>/<image>@sha256:...`) и действует как release gate: при отсутствии или невалидности attestation job падает.

Локальный прогон (если инструменты установлены в `PATH`):

```bash
cd archetypes/catalog-service
IMAGE_REF=ghcr.io/<owner>/catalog-service:local

docker build -t "$IMAGE_REF" .
syft "$IMAGE_REF" -o cyclonedx-json > sbom-cyclonedx.json
trivy image --severity HIGH,CRITICAL --exit-code 1 "$IMAGE_REF"
cosign sign --yes "$IMAGE_REF"
cosign verify \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "https://github.com/.+/.+/.*" \
  "$IMAGE_REF"

# Для проверки provenance в CI используется:
# gh attestation verify "oci://ghcr.io/<owner>/<image>@sha256:<digest>" \
#   --repo "<owner>/<repo>" \
#   --predicate-type "https://slsa.dev/provenance/v1"
```

Если `syft` / `trivy` / `cosign` не установлены локально, используйте их контейнерные образы или запускайте gate через GitHub Actions.

## Release: обязательные SBOM + подпись и manual approval для high-risk

Для релизов добавлен workflow [.github/workflows/release.yml](../.github/workflows/release.yml).

- Триггер: push тега `v*` или ручной `workflow_dispatch`.
- Перед публикацией выполняется job `high-risk-manual-approval` через GitHub Environment `high-risk-release`.
- Для каждого release-образа (`product-factory`, `catalog-service`, `web-app`) выполняется обязательный набор:
  - build + push в GHCR;
  - SBOM (Syft, CycloneDX JSON) и публикация SBOM-артефакта;
  - подпись Cosign keyless и `cosign verify`;
  - SLSA provenance attestation + verify.

Важно: чтобы approval был действительно ручным, в настройках репозитория для Environment `high-risk-release` должны быть включены `Required reviewers`.

## Staging deploy (GitOps-style) + smoke test

Источник desired state: `deploy/staging/docker-compose.yml`.
Значения GitOps: `deploy/staging/gitops.env` (tracked).
Шаблон локального env: `deploy/staging/.env.example`.
Базовый план продвижения: [gitops-cd-prod-plan.md](gitops-cd-prod-plan.md).

### Порядок деплоя

1. Обновите версию образа в GitOps-конфиге:
   - измените `deploy/staging/gitops.env` (`CATALOG_SERVICE_IMAGE`);
   - либо передайте `CATALOG_SERVICE_IMAGE` через env при запуске compose.
2. Закоммитьте изменение в git (это и есть GitOps change).
3. Примените desired state:
   ```bash
   scripts/gitops_apply_env.sh staging
   ```
4. Выполните smoke test:
   ```bash
   scripts/gitops_apply_env.sh staging --smoke
   ```
5. Убедитесь, что endpoint вернул `200`.

В CI это автоматизировано в job `staging-smoke` (`.github/workflows/ci-archetype-catalog.yml`): симуляция деплоя через compose + smoke test.

### Browser smoke для web-app (headless)

Для `web-app` после `health/ready` рекомендуется дополнительная проверка в headless Chromium (Playwright), чтобы поймать регресс UI/DOM. Базовый принцип: Node/браузер живут в отдельном smoke-контуре (CI runner или временный контейнер), в рантайм-образ фабрики ничего не добавляется.

Локальный пример:
```bash
docker build -t web-app:smoke archetypes/web-app
docker run -d --rm -p 18081:8080 --name web-app-smoke web-app:smoke
./scripts/smoke-staging.sh http://127.0.0.1:18081/health/ready
docker run --rm --network host mcr.microsoft.com/playwright:v1.51.0-jammy \
  bash -lc 'BASE_URL="http://127.0.0.1:18081/" node -e "
    const { chromium } = require(\"playwright\");
    (async () => {
      const browser = await chromium.launch({ headless: true });
      const page = await browser.newPage();
      await page.goto(process.env.BASE_URL, { waitUntil: \"domcontentloaded\", timeout: 15000 });
      const title = await page.title();
      const h1 = (await page.textContent(\"h1\"))?.trim();
      if (title !== \"Web App\") throw new Error(`Unexpected title: ${title}`);
      if (h1 !== \"Web App\") throw new Error(`Unexpected h1: ${h1}`);
      await browser.close();
      console.log(\"Browser smoke passed\");
    })().catch((e) => { console.error(e); process.exit(1); });
  "'
docker stop web-app-smoke
```

Полный runbook по провижинингу раннеров и интеграции browser smoke: [runner-provisioning-and-browser-smoke.md](runner-provisioning-and-browser-smoke.md).

## Prod promotion (GitOps-style)

Источники desired state:
- `deploy/prod/docker-compose.yml`
- `deploy/prod-canary/docker-compose.yml` (опциональный canary)

### Порядок promotion `staging -> prod`

1. Убедитесь, что в `staging` уже стоит immutable digest:
   - `CATALOG_SERVICE_IMAGE=ghcr.io/<org>/catalog-service@sha256:<digest>`.
2. Обновите `prod` desired state тем же digest:
   ```bash
   scripts/gitops_promote_staging_to_prod.sh
   ```
   Или явно:
   ```bash
   scripts/gitops_promote_staging_to_prod.sh ghcr.io/<org>/catalog-service@sha256:<digest>
   ```
3. (Опционально) обновите и canary env тем же digest:
   ```bash
   TARGET=all scripts/gitops_promote_staging_to_prod.sh
   ```
4. Закоммитьте изменение и откройте promotion PR:
   ```bash
   git add deploy/prod/gitops.env deploy/prod-canary/gitops.env
   git commit -m "promote: catalog-service <digest> to prod"
   git push
   ```
5. После merge примените desired state `prod`:
   ```bash
   scripts/gitops_apply_env.sh prod
   ```
6. Post-deploy проверка:
   ```bash
   scripts/gitops_apply_env.sh prod --smoke
   ```
   Затем проверить окно наблюдения (ошибки/SLO) 15-30 минут.

### Схема canary (prod + prod-canary)

Используйте canary при `medium/high` risk tier или если поведение под нагрузкой неочевидно.

```text
staging (digest D) -> prod-canary (digest D, малый трафик) -> prod (digest D, 100%)
                         |                                     |
                         |__ деградация -> rollback canary ____|
```

Минимальная операционная схема в этом репозитории:
1. Промоутнуть digest в `prod-canary`:
   ```bash
   TARGET=canary scripts/gitops_promote_staging_to_prod.sh ghcr.io/<org>/catalog-service@sha256:<digest>
   git add deploy/prod-canary/gitops.env
   git commit -m "canary: catalog-service <digest>"
   git push
   ```
2. Применить desired state canary:
   ```bash
   scripts/gitops_apply_env.sh prod-canary
   ```
3. Направить малую долю трафика на canary (рекомендуемо: `5% -> 25% -> 50% -> 100%`), если в окружении есть ingress/mesh/балансировщик.
4. На каждом шаге проверить:
   - `scripts/gitops_apply_env.sh prod-canary --smoke`
   - error rate/latency/SLO в Prometheus/Grafana.
5. Если окно наблюдения стабильно, обновить `prod` тем же digest и применить `deploy/prod`.
6. Если есть деградация, немедленно выполнить процедуру из раздела [Откат деплоя за минуты (GitOps)](#откат-деплоя-за-минуты-gitops).

## OPA (политики)

**В рантайме:** если задана переменная окружения `OPA_URL` (например `http://opa:8181`), приложение перед каждым factory run запрашивает у OPA решение `data.factory.allow`. Поведение при недоступности OPA или при отсутствии `OPA_URL` задаётся переменной **`POLICY_FAIL_MODE`**:
- **`open`** (по умолчанию) — fallback «allow», фабрика работает без запущенного OPA.
- **`closed`** — fallback «deny»: при любой ошибке/недоступности OPA run отклоняется (безопасный режим для продакшена).

Запуск с OPA (пример с sidecar):
```bash
docker run -p 8080:8080 -e OPA_URL=http://opa:8181 product-factory:latest
```
Строгий режим (отклонять run при сбое OPA):
```bash
docker run -p 8080:8080 -e OPA_URL=http://opa:8181 -e POLICY_FAIL_MODE=closed product-factory:latest
```

Проверка вручную:
```bash
opa eval -d policies/opa/data.json -i input.json -f json "data.factory.allow" policies/opa/rego/factory.rego
```
Пример `input.json`: `{"goal": "build API", "tool_calls": [], "token_usage": 1000}`.

**Синхронизация с реестром tools:** список разрешённых tools и risk tier для OPA хранится в `policies/opa/data.json`. Единый источник правды для executor — `contracts/tools.registry.json`; при добавлении или изменении tool обновляйте и реестр, и `data.json` (поля `tools` и `tool_risk`), чтобы policy и executor совпадали.

### Профиль окружения (FACTORY_ENV)

Переменная **`FACTORY_ENV`** задаёт профиль выполнения: `local` (по умолчанию), `ci`, `k8s`. Значение пишется в audit при событии `request_received` и может использоваться для выбора настроек (бюджеты, доступные tools). В CI и K8s рекомендуется задавать `POLICY_FAIL_MODE=closed`.

### Изолированный запуск tools (Docker runner)

При **`FACTORY_TOOL_RUNNER=docker`** и заданном **`DOCKER_IMAGE_TOOL_RUNNER`** инструменты `create_repo_from_archetype` и `apply_patch` выполняются в отдельном контейнере (образ tool-runner), без доступа к хосту. Остальные tools (create_github_repo, push_repo_to_github) по-прежнему выполняются в процессе фабрики. Для работы нужен доступ к Docker (socket или DOCKER_HOST).

Сборка образа tool-runner:
```bash
docker build -f deploy/Dockerfile.tool-runner -t product-factory-tool-runner:latest .
```

Запуск фабрики с docker runner:
```bash
docker run -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock \
  -e FACTORY_TOOL_RUNNER=docker -e DOCKER_IMAGE_TOOL_RUNNER=product-factory-tool-runner:latest \
  -v "$(pwd)/workspace:/app/workspace" -v "$(pwd)/archetypes:/app/archetypes" \
  product-factory:latest
```

Если Docker недоступен или образ не найден, executor автоматически откатывается на sandbox (локальное выполнение).

### Секреты (allowlist и политика)

- **Allowlist:** в реестре tools (`contracts/tools.registry.json`) у каждого tool может быть поле **`allowed_secrets`** — список имён env-переменных (например `GITHUB_TOKEN`), которые этот tool может получать. Только для `create_github_repo` и `push_repo_to_github` в реестре указан `GITHUB_TOKEN`; остальные tools не получают секреты через [SecretProvider](../src/main/kotlin/productfactory/workflow/tools/SecretProvider.kt).
- **Политика (OPA):** run отклоняется, если в аргументах любого tool call есть ключи `token`, `password`, `secret`, `api_key` — передача секретов в аргументах запрещена. В input OPA передаётся список `argument_keys` по каждому tool call.

## Human-in-the-loop approvals

Если OPA возвращает `require_human_approval: true`, factory run переходит в `rejected` с сообщением `Human approval required by policy`, а запрос на approval сохраняется в хранилище approvals:

- директория по умолчанию: `approvals/` (можно изменить через `APPROVALS_DIR`);
- файл: `<runId>.json`;
- поля: `runId`, `timestamp`, `reason`, `proposedActions`, `status`, а после решения — `decidedAt`, `decidedBy`, `comment`.

### API-путь

1. Запустить run:
   ```bash
   curl -s -X POST http://localhost:9080/factory/run \
     -H "Content-Type: application/json" \
     -d '{"goal":"deploy to staging","constraints":[]}'
   ```
2. Посмотреть pending approval:
   ```bash
   curl -s http://localhost:9080/factory/approvals/<runId>
   ```
3. Одобрить или отклонить:
   ```bash
   curl -s -X POST http://localhost:9080/factory/approvals/<runId>/approve \
     -H "Content-Type: application/json" \
     -d '{"decidedBy":"operator","comment":"approved"}'
   ```
   ```bash
   curl -s -X POST http://localhost:9080/factory/approvals/<runId>/reject \
     -H "Content-Type: application/json" \
     -d '{"decidedBy":"operator","comment":"rejected"}'
   ```
   Пример через универсальный endpoint ответов (A/B, индекс или строка):
   ```bash
   curl -s -X POST http://localhost:9080/factory/runs/<runId>/answer \
     -H "Content-Type: application/json" \
     -d '{"choice":"A"}'
   ```
4. После approve перезапустить тот же runId:
   ```bash
   curl -s -X POST http://localhost:9080/factory/runs/<runId>/retry \
     -H "Content-Type: application/json" \
     -d '{"goal":"deploy to staging","constraints":[]}'
   ```

### CLI-путь

```bash
product-factory approval-status <runId>
product-factory approve <runId> "approved by operator"
product-factory reject <runId> "rejected by operator"
```

После `approve` повторно отправьте запрос через `POST /factory/runs/<runId>/retry`.

### Вопросы пользователю (ask_user)

Фабрика может запросить ответ пользователя, когда **approval уже получен**, но нужно выбрать вариант выполнения (шаг `ask_user` в workflow, например выбор A/B).

Ответ отправляется через:

`POST /factory/runs/{runId}/answer` с телом, содержащим `choice`.

Пример:

```bash
curl -s -X POST http://localhost:9080/factory/runs/<runId>/answer \
  -H "Content-Type: application/json" \
  -d '{"choice":"A"}'
```

## Нейросервис (LLM gateway)

Фабрика обращается к «нейросервису» по единому API (OpenAI-совместимый `/v1/chat/completions`). Для вызывающего кода не важно, кто за фасадом: OpenAI, self-hosted или хост с Codex — задаётся только URL.

- Переменные: `NEURAL_SERVICE_URL` (обязательно для вызова LLM), при необходимости `NEURAL_SERVICE_API_KEY`.
- Если `NEURAL_SERVICE_URL` не задан, фабрика не вызывает внешний LLM (используются stub-планировщик и кодоген).

### Хост как нейросервис (Codex gateway)

На той же машине можно поднять шлюз, который реализует тот же API и под капотом вызывает Codex (или проксирует в OpenAI / другой URL):

```bash
cd scripts/neural_gateway
pip install -r requirements.txt
export NEURAL_BACKEND=codex   # или openai / proxy
uvicorn main:app --host 0.0.0.0 --port 8090
```

В окружении фабрики: `NEURAL_SERVICE_URL=http://localhost:8090`. По ответам не видно, Codex это или облако.

Подробнее: [neural-service-api.md](neural-service-api.md) (контракт и бэкенды), [neural-service-operations.md](neural-service-operations.md) (где подключать, управлять, масштабировать), `scripts/neural_gateway/README.md`.

## Eval regression gate (trace + RAGAs)

Локальный запуск:
```bash
python3 ci/quality_gates.py --mode agent --dataset eval/datasets/agent_trace_minimal.json
```

Когда запускается в CI (`.github/workflows/ci.yml`, job `eval-regression-gate`):
- `prompts/**`
- `policies/opa/**`
- `contracts/tools.schema.json`
- `contracts/tools.registry.example.json`
- `docs/tools-list-mvp.md`
- `eval/**`

Если `paths-filter` находит изменения по этим путям, CI запускает:
`python3 ci/quality_gates.py --mode agent --dataset eval/datasets/agent_trace_minimal.json --min-pass-rate 1.0 --min-average-score 0.95`
и
`python3 ci/quality_gates.py --mode ragas --dataset eval/datasets/ragas_minimal.json --min-pass-rate 1.0 --min-average-score 1.0`

Что делать при падении gate (exit code 1):
1. Локально воспроизвести падение той же командой.
2. Проверить, какой сценарий/ожидание упали (trace, expected checks, pass-rate/average-score).
3. Если изменение намеренное, обновить соответствующие trace/dataset и зафиксировать причину в PR.
4. Если регресс непреднамеренный, исправить prompts/policy/tools/eval-логику и повторить прогон до `PASS`.

Спецификация trace/dataset/CI: [scale-step4-trace-eval-gate.md](scale-step4-trace-eval-gate.md), [eval/README.md](../eval/README.md).
RAGAs сценарии и метрики: [eval-ragas.md](eval-ragas.md).

## Откат деплоя (GitOps rollback)

Связанный риск: `R-009` в `docs/risk-register.md`.

1. Найдите commit, который изменил `CATALOG_SERVICE_IMAGE` на проблемный digest в нужном окружении:
   - `deploy/staging/gitops.env`
   - `deploy/prod/gitops.env`
   - `deploy/prod-canary/gitops.env`
2. Выполните `git revert <commit>` в GitOps-ветке и отправьте revert commit.
3. Примените reverted desired state для целевого окружения:
   ```bash
   # staging
   scripts/gitops_apply_env.sh staging

   # prod
   scripts/gitops_apply_env.sh prod

   # prod-canary
   scripts/gitops_apply_env.sh prod-canary
   ```
4. Выполните smoke test только для целевого окружения:
   ```bash
   # prod
   scripts/gitops_apply_env.sh prod --smoke

   # prod-canary
   scripts/gitops_apply_env.sh prod-canary --smoke

   # staging
   scripts/gitops_apply_env.sh staging --smoke
   ```

Если используется контроллер с autosync (например, Argo CD), rollback может быть заблокирован текущим sync-поведением. На время отката:
1. Отключите autosync.
2. Примените revert commit.
3. Проверьте smoke test.
4. Включите autosync обратно.

## Откат деплоя за минуты (GitOps)

Цель: вернуть стабильный digest за `<= 5` минут после решения об откате.

Таймбокс:
1. `T+00:00 - T+01:00` Найти последний стабильный commit для нужного env (`deploy/prod/gitops.env` или `deploy/prod-canary/gitops.env`):
   ```bash
   git log --oneline -- deploy/prod/gitops.env deploy/prod-canary/gitops.env | head -n 10
   ```
2. `T+01:00 - T+02:00` Создать revert commit:
   ```bash
   git revert <commit-with-bad-digest>
   git push
   ```
3. `T+02:00 - T+04:00` Применить reverted desired state:
   ```bash
   # prod
   scripts/gitops_apply_env.sh prod

   # prod-canary
   scripts/gitops_apply_env.sh prod-canary
   ```
4. `T+04:00 - T+05:00` Подтвердить восстановление:
   ```bash
   scripts/gitops_apply_env.sh prod --smoke
   scripts/gitops_apply_env.sh prod-canary --smoke
   ```

Критерии успеха rollback:
- `health/ready = 200` для целевого env;
- нет роста error rate после revert;
- в desired state снова стоит стабильный immutable digest.

## Логи и трейсы

Пока: stdout приложения, audit.log. OTel — при добавлении в фазе 3–4.

## Инциденты

Эскалация, ротация секретов, отключение агентов (режим read-only). См. [risk-register.md](risk-register.md).
