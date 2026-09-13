# Deploy: полный стек в Docker

Поднимаем фабрику и все внешние сервисы одним compose: **фабрика**, **MinIO** (S3-совместимое хранилище артефактов), **OPA**, опционально **neural-gateway**. Админка: MinIO Console на порту 9001.

## Быстрый старт

```bash
# Из корня репо
docker compose -f deploy/docker-compose.yml up -d

# С neural-gateway (LLM)
docker compose -f deploy/docker-compose.yml --profile with-neural up -d

# С Temporal (durable workflow: persist, retry по шагам). Задать TEMPORAL_ADDRESS=temporal:7233 для factory (например в .env).
docker compose -f deploy/docker-compose.yml --profile temporal up -d
```

При профиле **temporal** поднимаются: PostgreSQL для Temporal, Temporal Server (порт 7233), Temporal UI (порт 8081). Фабрике нужно передать `TEMPORAL_ADDRESS=temporal:7233` (в `.env` или в `environment` для сервиса factory), тогда POST /factory/run будет возвращать 202 и выполнять run через Temporal.

## GitOps desired state для staging/prod

Для `catalog-service` зафиксированы отдельные desired-state манифесты:

- `deploy/staging/docker-compose.yml`
- `deploy/prod/docker-compose.yml`
- `deploy/prod-canary/docker-compose.yml`
- `deploy/staging/gitops.env`, `deploy/prod/gitops.env`, `deploy/prod-canary/gitops.env` (tracked values для GitOps commit)

Ожидаемый формат образа для `prod` и `prod-canary`: только immutable digest (`image@sha256:...`).

Promotion `staging -> prod`:

```bash
# берет digest из deploy/staging/gitops.env и обновляет только deploy/prod/gitops.env
scripts/gitops_promote_staging_to_prod.sh

# канареечный шаг (обновляет только deploy/prod-canary/gitops.env)
TARGET=canary scripts/gitops_promote_staging_to_prod.sh

# обновить одновременно prod и prod-canary
TARGET=all scripts/gitops_promote_staging_to_prod.sh
```

Дальше стандартный GitOps-шаг: `git add/commit/push` + PR.

Применение desired state и smoke:

```bash
scripts/gitops_apply_env.sh staging --smoke
scripts/gitops_apply_env.sh prod --smoke
scripts/gitops_apply_env.sh prod-canary --smoke
```

## Сервисы и порты (фиксированные, без переменных)

| Сервис | Порт на хосте | Прочее |
|--------|----------------|--------|
| **factory** | **9080** | http://localhost:9080 |
| **minio** | 9000, 9001 | Console http://localhost:9001 |
| **opa** | 8181 | — |
| **neural-gateway** (profile with-neural) | 8090 | — |
| **prometheus** (profile observability) | 9090 | — |
| **grafana** (profile observability) | **3001** | http://localhost:3001 (admin/admin) |
| **temporal** (profile temporal) | **7233** | gRPC для Worker/Client |
| **temporal-ui** (profile temporal) | **8081** | http://localhost:8081 (Web UI) |

## Создание бакетов в MinIO

MinIO не создаёт бакеты автоматически. После первого запуска:

1. Откройте http://localhost:9001 (логин/пароль из `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`, по умолчанию minioadmin/minioadmin).
2. Создайте бакет **artifacts**.

Либо одной командой (если установлен `mc`):

```bash
mc alias set minio http://localhost:9000 minioadmin minioadmin
mc mb minio/artifacts --ignore-existing
```

## Observability (Prometheus + Grafana)

Сбор метрик и дашборды — опционально, profile `observability`:

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d
```

- **Prometheus:** http://localhost:9090 (target фабрики уже в конфиге).
- **Grafana:** http://localhost:3001 (admin/admin). Data source и дашборды **Factory overview** + **Factory SLO & Cost** подхватываются из `deploy/grafana/provisioning/` при старте.
- Alert rules подключены из `deploy/prometheus-rules.yml` через `rule_files` в `deploy/prometheus.yml`.

Метрики фабрики: `factory_runs_total`, `factory_run_duration_ms_*`, `factory_tool_calls_total`, `factory_llm_calls_total`, `factory_llm_input_tokens_total`, `factory_llm_output_tokens_total`.

## GitHub (токен, create_github_repo и push_repo_to_github)

Чтобы фабрика создавала репозитории в GitHub и пушила в них код, задайте при запуске **GITHUB_TOKEN** и при необходимости **GITHUB_OWNER**. Пошаговая и безопасная настройка: **[docs/github-setup.md](../docs/github-setup.md)**.

- **GITHUB_TOKEN** — Personal Access Token (GitHub → Settings → Developer settings → Tokens, scope `repo`). В compose уже проброшены `GITHUB_TOKEN` и `GITHUB_OWNER` из переменных окружения; можно задать их в `.env` в корне репо (файл не коммитить) и запустить: `docker compose --env-file ../.env up -d`.
- **GITHUB_OWNER** (опционально) — логин пользователя или организации; при пустом — репо под пользователем токена.

При заданном **GITHUB_TOKEN** workflow выполняет: create_repo_from_archetype → create_github_repo → push_repo_to_github. Ссылка на созданный репо — в ответе `GET /factory/runs/{runId}` (поле **repoUrl**). См. [integrations-server-deployment.md](../docs/integrations-server-deployment.md).

## Переменные окружения

- **FACTORY_ENV** — профиль окружения: `local` (по умолчанию), `ci`, `k8s`; пишется в audit при run.
- **AUTH_MODE** — профиль AuthN API. Для H1-Auth-1.1 фиксирован `oidc_jwt`.
- **AUTH_JWT_ISSUER** — ожидаемый `iss` в access token (OIDC issuer).
- **AUTH_JWT_AUDIENCE** — ожидаемый `aud` API.
- **AUTH_JWT_CLAIM_TENANT_ID** — claim для `tenantId` (по умолчанию `tenant_id`).
- **AUTH_JWT_CLAIM_SUBJECT** — claim для `subject` (по умолчанию `sub`).
- **AUTH_JWT_CLAIM_ROLES** — claim для `roles` (по умолчанию `roles`).
- **MINIO_ROOT_USER / MINIO_ROOT_PASSWORD** — MinIO и фабрика (ARTIFACT_STORAGE_*).
- **OPA_URL** — фабрика подхватывает из compose (http://opa:8181). **POLICY_FAIL_MODE** — `open` (fallback allow при сбое OPA) или `closed` (fallback deny); по умолчанию `open`.
- **NEURAL_BACKEND, OPENAI_API_KEY, PROXY_TARGET_URL** — для neural-gateway (при profile with-neural).
- **GITHUB_TOKEN** — для create_github_repo и push_repo_to_github (опционально). **GITHUB_OWNER** — для репо в организации (опционально).
- **GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD** — при profile observability.
- **TEMPORAL_ADDRESS** — при profile temporal задать `temporal:7233`, чтобы фабрика подняла Worker и выполняла run через Temporal (202 + асинхронное выполнение). **TEMPORAL_NAMESPACE** — namespace (по умолчанию `default`).

## Проверка

```bash
curl -s http://localhost:9080/health
curl -s http://localhost:9080/health/neural
curl -s -X POST http://localhost:9080/factory/run -H "Content-Type: application/json" -d '{"goal":"Test run","constraints":[],"target_stack":"web-app"}'
```

После успешного run артефакт появится в MinIO (бакет artifacts), в ответе tool call — поле `artifact_location` с URI.

## Монолит и сервисы

Что выделено в отдельные контейнеры и зачем — см. [docs/monolith-vs-services.md](../docs/monolith-vs-services.md).
