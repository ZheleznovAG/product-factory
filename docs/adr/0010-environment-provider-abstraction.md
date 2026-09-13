# ADR 0010: EnvironmentProvider abstraction for workflow execution

- Status: accepted
- Date: 2026-02-26

## Context

Factory runtime already has `FactoryEnvironment` (`local`, `ci`, `k8s`) for deployment profile.
At the same time workflow steps need explicit execution context for tools (where/how to run a step):

- local Docker runtime
- remote SSH runtime

Without a dedicated abstraction, this context is spread across ad-hoc env vars and is not visible in audit.

## Decision

Introduce `EnvironmentProvider` in workflow layer:

- `provide(runId, envType) -> EnvironmentContext`
- `EnvironmentType`: `local-docker`, `remote-ssh`
- `EnvironmentContext` carries minimal execution parameters (mode, working directory, key-value parameters)

Current implementations are stubs:

- `StubEnvironmentProvider` for `local-docker`
- `StubEnvironmentProvider` for `remote-ssh` (placeholder SSH params)

Bridge existing deployment profile to execution type via mapper:

- `FactoryEnvironment.local` -> `local-docker`
- `FactoryEnvironment.ci` -> `local-docker`
- `FactoryEnvironment.k8s` -> `remote-ssh` (temporary default mapping, can be replaced by dedicated k8s backend later)

Workflow now resolves environment context at run start and writes it into `request_received` audit event.

## Consequences

- Unified extension point for future real backends (Docker host, SSH orchestrator, Kubernetes executor)
- No violation of layer boundaries: provider describes context only, side-effects remain in Tool Executor
- Better observability: execution environment is explicit in audit trail
