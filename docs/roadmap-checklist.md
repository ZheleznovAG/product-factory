# Roadmap checklist: план vs текущее состояние

Сводка по [system-audit-corrective-plan.md](system-audit-corrective-plan.md) и [implementation-assessment.md](implementation-assessment.md). Обновлять по мере закрытия пунктов. **Лестница этапов (безопасность → quality gates → окружения → мульти-агенты → платформа)** с отметками готовности: [architecture-and-path.md](architecture-and-path.md).

---

## Фаза Alpha — Input Contract Spec v1 (2 недели, старт 2026-02-20)

| Deliverable | Статус | Примечание |
|-------------|--------|------------|
| Схемы для пяти входных файлов (product, constraints, quality_profile, risk_profile, target_stack) | Да | [contracts/schemas/](../contracts/schemas/) — product, constraints, quality_profile, risk_profile, target_stack |
| Валидатор контрактов `pf validate` | Да | `product-factory validate <dir>` в Docker; приложение проверяет контракты при `FACTORY_CONTRACTS_DIR` |
| Каталог risk tiers low/medium/high + мэппинг approvals/budgets | Есть | [risk-register.md](risk-register.md), [approval-policy.md](approval-policy.md), [policies/budgets.yaml](../policies/budgets.yaml) |
| ADR: границы слоёв + запреты | Да | [adr/0001-stack-and-prerequisites.md](adr/0001-stack-and-prerequisites.md), [adr/0002-layer-boundaries.md](adr/0002-layer-boundaries.md) |

**Критерий успеха Alpha:** вся конфигурация фабрики проходит валидацию до запуска; free-form поля не участвуют в policy decisions.

---

## Фаза Beta — Minimal Deterministic Execution Core (6 недель)

| Deliverable | Статус | Примечание |
|-------------|--------|------------|
| State machine: NEW → PLANNED → … → STAGED → DONE/FAILED | Да | [WorkflowStateMachine](../src/main/kotlin/productfactory/workflow/WorkflowStateMachine.kt), [WorkflowRunner](../src/main/kotlin/productfactory/workflow/WorkflowRunner.kt); переходы в audit log |
| Event log: runId, stepId, inputDigest, outputDigest | Да | Audit log ([FileAuditLog](../src/main/kotlin/productfactory/workflow/AuditLog.kt)); digest в state_changed и tool_call_executed (в т.ч. дефолты в AuditLog, явно в SandboxToolExecutor) |
| Tool registry (JSON schema) + sandbox executor + idempotency keys | Да | [contracts/tools.schema.json](../contracts/tools.schema.json); sandbox executor + один tool, idempotency key в audit |
| Policy PDP (OPA) allow/deny + decision logs | Да | Вызов OPA из приложения при OPA_URL; [OpaPolicyCheck](../src/main/kotlin/productfactory/policy/); decision logs — по настройке OPA |
| OTel: span на run, дочерние на шагах | Да | [OpenTelemetryFactory](../src/main/kotlin/productfactory/observability/), трассировка в WorkflowRunner |
| Артефакт-реестр: репо/образ/SBOM/подписи в одном run record | Да | [ArtifactRegistry](../src/main/kotlin/productfactory/workflow/ArtifactRegistry.kt): один record на runId при STAGED/DONE; `sbomVersion/signatureVersion` приходят из `ToolStepResult` (tool/CI/workspace), placeholder только при отсутствии источника |

**Критерий успеха Beta:** ретрай не создаёт дублей side-effects; policy decisions логируются.

---

## Фаза MVP — Factory Run для одного archetype (6–8 недель)

| Deliverable | Статус | Примечание |
|-------------|--------|------------|
| Archetype catalog-service (код + тесты + Docker + миграции БД) | Да | [archetypes/catalog-service/](../archetypes/catalog-service/): Ktor, тесты, Dockerfile, db/migrations |
| CI: unit + integration + сборка образа | Да | [ci-archetype-catalog.yml](../.github/workflows/ci-archetype-catalog.yml): `gradle build` (включая тесты) и `docker build` для `archetypes/catalog-service` |
| SBOM (Syft), scan (Trivy), sign (Cosign) + verify в gate | Частично | Добавлен job `supply-chain-gate` в [ci-archetype-catalog.yml](../.github/workflows/ci-archetype-catalog.yml) (build/push + Syft + Trivy + Cosign sign/verify); нужен rollout на остальные CI и staging policy |
| Staging deploy (GitOps) + smoke test | Да | `deploy/staging/docker-compose.yml`, `scripts/smoke-staging.sh`, job `staging-smoke` в `.github/workflows/ci-archetype-catalog.yml` |

**Критерий успеха MVP:** 3 прогона одного ProductSpec → одинаковый set артефактов (digest); rollback через GitOps (процедура зафиксирована в `docs/runbook.md`).

---

## Фаза Scale — AI как Proposal Engine (8–10 недель)

| Deliverable | Статус | Примечание |
|-------------|--------|------------|
| Агент-планировщик (pipeline plan, ADR draft, test plan в JSON) | **Да** | LlmAgentPlanner при NEURAL_SERVICE_URL (Codex/gateway); fallback на StubAgentPlanner. Gateway передаёт полный промпт и снимает markdown с ответа. |
| Агент-кодоген (patch sets, git diff, без прямого merge) | **Да** | LlmAgentCodegen при NEURAL_SERVICE_URL; fallback на StubAgentCodegen. Запись codegen_patch_set и agent_codegen_call в audit. Merge не выполняется. |
| Approvals для write/deploy (human-in-the-loop) | **Да** | ApprovalStore (InMemory + File), API GET/POST `/factory/approvals/{runId}`, CLI approve/reject; WorkflowRunner при requireHumanApproval создаёт pending и ждёт approve. |
| Trace grading + eval runs + regression gates | **Да** | [eval/runners/run_offline_evals.py](../eval/runners/run_offline_evals.py), датасет 3 сценария, [ci/quality_gates.py](../ci/quality_gates.py), job **eval-regression-gate** в [.github/workflows/ci.yml](../.github/workflows/ci.yml) при изменении prompts/policies/tools/eval. |

---

## Сводка по готовности

| Фаза | Готовность | Следующий шаг |
|------|------------|---------------|
| Alpha | **закрыта** | Валидация контрактов в CI: job **validate-contracts** запускается на каждый push и PR ([ci.yml](../.github/workflows/ci.yml)). |
| Beta | **закрыта** | Опционально: расширить sandbox tools. |
| MVP | **закрыта** | Архетип catalog-service, CI, SBOM/Trivy/Cosign, staging + smoke. Опционально: второй архетип, расширить gates. |
| Scale | **закрыта** | Планировщик и кодоген с NEURAL_SERVICE_URL (Codex/LLM), approvals, eval gate. Опционально: cost/SLO. |

---

## Откуда продолжить

**Сейчас:** Alpha, Beta, MVP и Scale закрыты. Дальше — точечные улучшения или следующие фазы (SLO/cost, durable engine, supply chain, продуктивизация).

**Варианты дальше (по приоритету):**

1. **Scale — закрыта**  
   - Планировщик и кодоген подключены к NEURAL_SERVICE_URL (Codex/gateway). В audit: agent_planner_call, agent_codegen_call (latency, success, fallback).  
   См. [neural-service-api.md](neural-service-api.md), [neural-service-operations.md](neural-service-operations.md).

2. **Улучшения без новой фазы**  
   - Второй архетип (web-app) уже есть.  
   - Интеграционный `FactoryRunTest` стабилизирован (без `@Ignore`); e2e также проверяется через Docker/runbook (`scripts/run_live_run.sh`) и CI job `live-run`. В Application добавлен опциональный `toolExecutor` для тестов.  
   - Supply-chain для образа фабрики: job supply-chain-factory в [ci.yml](../.github/workflows/ci.yml) (push main: build, push GHCR, SBOM, Trivy, Cosign).  
   - [implementation-assessment.md](implementation-assessment.md) приведён в соответствие с текущим состоянием (Scale закрыта).
   - [phases-task-list.md](phases-task-list.md) синхронизирован (Scale закрыта, фаза 5 отмечена).

**Рекомендация:** система рабочая; при необходимости — полировка (тесты, доки) или следующие фазы roadmap.

**Стратегическая развилка (Factory 1.0 → 2.0):** см. [strategic-fork-factory-2.md](strategic-fork-factory-2.md). Реализован гибрид: LLM для Planner и Codegen при `NEURAL_SERVICE_URL`; план интеграции — [factory-2-planner-integration.md](factory-2-planner-integration.md).

**Полный бэклог и спринт:** [sprint-full-backlog.md](sprint-full-backlog.md), [sprint-factory2-howto.md](sprint-factory2-howto.md). Запуск: `python3 scripts/run_factory2_full_sprint.py --allow-docker`.
