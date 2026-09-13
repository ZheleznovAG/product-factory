# Список инструментов (tools) для MVP

Фаза 2. Инструменты, которые агент может вызывать в рамках одного factory run. Реестр заполняется в `contracts/tools.schema.json` и дублируется в `policies/allowlists.yaml`; Policy Engine проверяет каждый вызов.

## Предлагаемый набор для MVP (архетип «API service»)

| Tool | Описание | Risk tier | Human approval | Идемпотентность |
|------|----------|-----------|----------------|-----------------|
| **generate_spec** | Генерация PRD/спека/архитектуры (внутренний вызов агента, не side-effect) | read_only | нет | N/A (нет side-effect) |
| **create_repo_from_archetype** | Создать репозиторий из архетипа (шаблон + начальный коммит) | write_limited | нет* | по `repo_name` + `archetype_id` |
| **commit_files** | Закоммитить файлы в репо (код, конфиги, контракты) | write_limited | нет* | по `repo_url` + `commit_idempotency_key` |
| **trigger_ci** | Запустить CI pipeline для репо | write_limited | нет | по `repo_url` + `ref` + `workflow_run_id` |
| **get_ci_status** | Получить статус последнего прогона CI | read_only | нет | да |
| **deploy_staging** | Инициировать деплой в staging (GitOps: создать коммит в репо окружения или вызвать API) | privileged | да | по `repo_url` + `ref` + `deploy_id` |

\* В MVP для write_limited без доступа к prod можно не требовать approval; при ужесточении — выставить `requires_human_approval: true` для `create_repo_from_archetype` и `commit_files`.

## Не входят в MVP

- Деплой в prod (только через ручной процесс вне фабрики или отдельный tool с обязательным approval).
- Удаление репо, веток, артефактов (добавлять только после threat model и policy).
- Изменение политик фабрики, allowlist, бюджеты (только человек через change-control).

## Связь с контрактом

Для каждого tool в реестре должны быть заданы:
- `auth_scope` — какие права/области доступа нужны (например, `repo:write`, `ci:read`).
- `idempotency.key_fields` — поля, по которым однозначно определяется операция для ретраев.
- `timeouts_ms`, `retry_policy` — по контракту в [contracts/tools.schema.json](../contracts/tools.schema.json).
- `input_schema`, `output_schema` — JSON schema входа и выхода для валидации.

Конкретные экземпляры (с input/output schema) добавляются в фазах 3–4 при реализации Tool Executor.
