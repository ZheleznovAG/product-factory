# ADR 0012: Scope for `local-docker` and `remote-ssh`

- Status: accepted
- Date: 2026-02-27
- Related: ADR 0002, ADR 0010

## Context

После ADR 0010 execution-окружения вынесены в отдельный provider layer. Для MVP нужно было явно зафиксировать продуктовые границы двух целевых окружений:

- `local-docker`
- `remote-ssh`

Решение должно сохранять boundary ADR 0002: side-effects только через workflow + Tool Executor/policy, без прямого write/deploy канала у агента.

## Decision

Для MVP принят следующий scope:

- `local-docker`: поддерживаются оба режима, `runbook` и `full`, где `runbook` остаётся default.
- `remote-ssh`: в scope только минимальный однокомандный SSH runner; при неполной конфигурации выполняется безопасный fallback в disabled/runbook-контекст.

## Scope details

### `local-docker`

- `runbook` (default): команды выполняются на host execution context, как в текущем runbook-потоке.
- `full`: команды шагов выполняются через Docker runner image внутри `LocalDockerExecutionContext`.
- Режим переключается через `FACTORY_LOCAL_DOCKER_EXECUTION=runbook|full`.
- Используются `FACTORY_LOCAL_DOCKER_COMPOSE_FILE`, `FACTORY_LOCAL_DOCKER_WORKSPACE_PER_RUN`, `FACTORY_LOCAL_DOCKER_RUNNER_IMAGE`.

### `remote-ssh`

- Минимальный поддержанный путь: `ssh -i <key> <user>@<host> -- sh -lc "<command>"`.
- Обязательные параметры: `FACTORY_REMOTE_SSH_HOST`, `FACTORY_REMOTE_SSH_USER`, `FACTORY_REMOTE_SSH_KEY_PATH`.
- При отсутствии любого обязательного параметра SSH runner помечается как disabled и возвращается безопасный runbook execution context.
- Multi-step orchestration, session reuse, remote file lifecycle и deployment automation в MVP не входят.

## Alternatives considered

### A. `local-docker` только как runbook/config

- Плюсы: минимум реализации.
- Минусы: нет изолированного выполнения шагов внутри Docker.
- Отклонено: не закрывает Docker-first сценарии.

### B. `remote-ssh` полностью out of scope

- Плюсы: меньше поверхность риска.
- Минусы: отсутствует даже базовый удалённый execution path.
- Отклонено: минимальный SSH-runner реализуем и полезен без расширения scope.

## Consequences

- Граница MVP прозрачна:
  - `local-docker`: `runbook` + `full`
  - `remote-ssh`: только minimal single-command mode
- Поведение по умолчанию безопасное: при неполной SSH-конфигурации внешний вызов не выполняется.
- В будущем можно расширить SSH backend, не ломая environment contract.

## Implementation status

Решение реализовано и покрыто тестами:

- `src/main/kotlin/productfactory/workflow/EnvironmentProvider.kt`
- `src/main/kotlin/productfactory/workflow/ExecutionContext.kt`
- `src/main/kotlin/productfactory/Application.kt`
- `src/test/kotlin/productfactory/workflow/EnvironmentProviderTest.kt`

## Revisit triggers

- Одобрено требование на multi-step remote execution/session orchestration.
- Требуется усиленный security baseline для SSH secrets/approvals/audit.
- Нужен execution outside local Docker topology на постоянной основе.
