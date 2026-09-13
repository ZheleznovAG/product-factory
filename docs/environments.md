# Environments Contract (MVP)

Документ фиксирует MVP-решение по execution-окружениям в соответствии с [ADR 0012](adr/0012-local-docker-and-remote-ssh-scope.md).

## Решение по scope

- `local-docker`: in scope, поддерживаются два режима:
- `runbook` (default): только конфиг окружения, выполнение шагов по прежнему host-path.
- `full`: шаги выполняются через Docker runner (`ExecutionContext`).
- `remote-ssh`: in scope только в минимальном варианте (single-command SSH-runner).
- `remote-ssh` full orchestration: out of scope для MVP.

## `local-docker`

Назначение: выполнение workflow в локальном Docker-контуре (compose/kind) на машине фабрики.

Ключевые поля `EnvironmentContext`:

- `environment_type`: `local-docker`
- `execution_mode`: `docker`
- `working_directory`: `workspace/<run-id>` (или путь из конфигурации)
- `parameters.execution_strategy`: `runbook` | `full`
- `parameters.compose_file_path`: по умолчанию `deploy/docker-compose.yml`
- `parameters.workspace_path`: путь workspace
- `parameters.runner_image`: runner image для `full`
- `parameters.k8s_provider`: опционально (`kind`)

Пример:

```json
{
  "environment_type": "local-docker",
  "execution_mode": "docker",
  "working_directory": "workspace/run-123",
  "parameters": {
    "execution_strategy": "full",
    "compose_file_path": "deploy/docker-compose.yml",
    "workspace_path": "workspace/run-123",
    "runner_image": "product-factory-tool-runner:latest"
  }
}
```

ENV-параметры:

- `FACTORY_WORKSPACE_DIR`
- `FACTORY_LOCAL_DOCKER_COMPOSE_FILE`
- `FACTORY_LOCAL_DOCKER_WORKSPACE_PER_RUN`
- `FACTORY_LOCAL_K8S_PROVIDER`
- `FACTORY_LOCAL_DOCKER_EXECUTION` (`runbook` default, либо `full`)
- `FACTORY_LOCAL_DOCKER_RUNNER_IMAGE`

## `remote-ssh`

Назначение: минимальный удалённый запуск одной команды на предоставленном хосте по SSH.

Ключевые поля `EnvironmentContext`:

- `environment_type`: `remote-ssh`
- `execution_mode`: `ssh`
- `parameters.ssh.host`
- `parameters.ssh.user`
- `parameters.ssh.auth_mode`: `key_path` | `placeholder_unset`
- `parameters.ssh.key_path`
- `parameters.ssh.exec.command_template`
- `parameters.ssh.runner_status`: `enabled` | `disabled`

Пример:

```json
{
  "environment_type": "remote-ssh",
  "execution_mode": "ssh",
  "parameters": {
    "ssh.host": "build-node.internal",
    "ssh.user": "deployer",
    "ssh.auth_mode": "key_path",
    "ssh.key_path": "/run/secrets/pf_deployer_key",
    "ssh.exec.command_template": "ssh {auth_flags} {user}@{host} -- {command}",
    "ssh.runner_status": "enabled"
  }
}
```

ENV-параметры:

- `FACTORY_REMOTE_SSH_HOST`
- `FACTORY_REMOTE_SSH_USER`
- `FACTORY_REMOTE_SSH_KEY_PATH`

Ограничение MVP:

- при неполной конфигурации (`host/user/key`) используется безопасный fallback в runbook-контекст (`ssh.runner_status=disabled`);
- многошаговая SSH-оркестрация/управление сессиями в MVP не реализуются.

## Безопасность и границы

- Side-effects идут только через workflow runners + Tool Executor/policy.
- Агентный слой не получает прямого write/deploy-канала.
- SSH-секреты не хранятся в репозитории, только ENV/внешние secret-механизмы.

## AuthN профиль API (H1-Auth-1.1)

Для API зафиксирован единый профиль аутентификации: `AUTH_MODE=oidc_jwt` (OIDC Bearer JWT).

Обязательные параметры при включённом AuthN:

- `AUTH_JWT_ISSUER` — ожидаемый `iss`.
- `AUTH_JWT_AUDIENCE` — ожидаемый `aud`.
- `AUTH_JWT_HS256_SECRET` — shared secret для runtime-валидации подписи JWT в текущем профиле H1-Auth-1.2.

Параметры mapping claims -> runtime identity fields:

- `AUTH_JWT_CLAIM_TENANT_ID` (по умолчанию `tenant_id`) -> `tenantId`.
- `AUTH_JWT_CLAIM_SUBJECT` (по умолчанию `sub`) -> `subject`.
- `AUTH_JWT_CLAIM_ROLES` (по умолчанию `roles`) -> `roles` (строка или массив строк).

Полная таблица mapping и операционные правила (`tenant` boundary, поведение при mismatch tenant claim/request) зафиксированы в [runbook.md](runbook.md#authn-профиль-api-h1-auth-11).
