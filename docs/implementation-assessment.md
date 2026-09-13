# Оценка реализации Product Factory

**Дата:** 2026-02-27 (обновлено: ADR 0011/0012, SLO/cost enforcement, AI-risk контур, multi-tenancy docs sync)  
**Основа:** [roadmap-checklist.md](roadmap-checklist.md), [phases-task-list.md](phases-task-list.md), [system-audit-corrective-plan.md](system-audit-corrective-plan.md).

---

## Сводка по фазам

| Фаза | Название | Оценка | Комментарий |
|------|----------|--------|-------------|
| 1 | Инициация и риск | **100%** | PRD, data map, risk register, DoD, prohibited actions, ADR-0001; decision points v1 в decision-points-v1.md |
| 2 | Архитектура и контракты | **100%** | SDD, ADR, tools list, контракты (JSON Schema), approval policy, secrets policy, пять входных схем |
| 3 | Основание платформы | **100%** | API, state machine, audit log с inputDigest/outputDigest, OTel, OPA из приложения, sandbox executor, артефакт-реестр, health endpoints. Temporal подключён в режиме `TEMPORAL_ADDRESS` (WS0). |
| 4 | MVP фабрики | **закрыта** | Архетип catalog-service, tool create_repo_from_archetype, CI к архетипу, staging (GitOps-style), SBOM/Trivy/Cosign в CI архетипа. RAG/pgvector ingestion и versioning реализованы; интеграция в planner опциональна и выключена по умолчанию. |
| 5 | Надёжность и качество | **частично** | Trace grading и eval gate в CI. LlmAgentPlanner и LlmAgentCodegen подключены при NEURAL_SERVICE_URL (Scale закрыта). Роли Planner/Implementer/Tester/Reviewer и human-loop API реализованы (WS1/WS3 MVP). SLO/cost: метрики, Prometheus alerts, pre-run budget enforcement и CI slo-gate работают; online promotion signals — в бэклоге. |
| 6 | Supply chain и комплаенс | **частично** | SBOM/Trivy/Cosign в CI архетипа (supply-chain-gate). Источник `sbomVersion/signatureVersion` в run фиксирован (tool -> CI env -> workspace; ADR 0011). Threat model (NIST AML, MITRE ATLAS) и AI-risk контур оформлены; SLSA attestation policy остаётся в бэклоге. |
| 7 | Продуктивизация | **частично** | Второй архетип (web-app) добавлен, CI и supply-chain gate для него. WS4 (intent layer MVP: schema/protocol/build-mode) реализован; WS2 (environments v1) остаётся в процессе. |

---

## Что реализовано (детально)

### Документация (фазы 1–2)

- PRD, data map, risk register, DoD, prohibited actions, ADR-0001, ADR-0002 (границы слоёв), SDD, контракты (tools, agent_outputs, пять входных схем в contracts/schemas/), approval policy, secrets policy, tools-list-mvp, runbook. Decision points v1 — [decision-points-v1.md](decision-points-v1.md).

### Код и инфраструктура (фаза 3)

- **API:** `POST /factory/run` (Ktor), FactoryRunRequest/Response; approvals API и CLI; health endpoints (`/health`, `/health/ready`, `/health/neural`), `POST /factory/runs/{runId}/answer`.
- **Workflow:** WorkflowRunner с state machine (NEW → … → STAGED → DONE/FAILED); планировщик (LlmAgentPlanner при `NEURAL_SERVICE_URL`, иначе StubAgentPlanner) и кодоген (LlmAgentCodegen при `NEURAL_SERVICE_URL`, иначе StubAgentCodegen), запись в audit (agent_planner_call, agent_codegen_call), роли Planner/Implementer/Tester/Reviewer.
- **Audit log:** FileAuditLog (JSONL), inputDigest/outputDigest, события state_changed, tool_call_executed, approval_required и др.
- **Policy:** PolicyCheck (OPA при OPA_URL), fallback allow.
- **Approvals:** ApprovalStore (InMemory + File), API GET/POST `/factory/approvals/{runId}`, CLI approve/reject; WorkflowRunner при require_human_approval создаёт pending.
- **OTel:** OpenTelemetryFactory, spans на run и шагах.
- **Sandbox executor:** SandboxToolExecutor, tool create_repo_from_archetype (подключён к архетипу catalog-service), idempotency key.
- **Артефакт-реестр:** FileArtifactRegistry, run record при STAGED/DONE; `sbomVersion/signatureVersion` заполняются через `ToolStepResult` (источники: tool result, CI env, post-step файлы workspace через SupplyChainVersionResolver; решение зафиксировано в ADR 0011).
- **Валидатор контрактов:** ContractValidator, CLI `product-factory validate <dir>`; при FACTORY_CONTRACTS_DIR — валидация при старте. В CI job **validate-contracts** запускается на каждый push и PR (Docker build + валидация `contracts-example/` или `contracts/`).
- **Docker:** multi-stage build. Порт задаётся через **PORT** (по умолчанию 8080); в compose — **FACTORY_PORT** для порта на хосте (см. deploy/README.md).
- **Durable execution (WS0):** при `TEMPORAL_ADDRESS` запускается Temporal Worker; `POST /factory/run` работает асинхронно (`202 Accepted`) и run исполняется как Temporal Workflow с persist/retry по activity.
- **GitHub:** при заданных GITHUB_TOKEN (и при необходимости GITHUB_OWNER) workflow выполняет create_github_repo и push_repo_to_github; в записи run заполняется **repoUrl** (ссылка на созданный репозиторий).

### MVP (фаза 4)

- **Архетип catalog-service:** [archetypes/catalog-service/](../archetypes/catalog-service/) — Ktor, тесты, Dockerfile, db/migrations.
- **Архетип web-app:** [archetypes/web-app/](../archetypes/web-app/) — Ktor Web (HTML, /health, /health/ready), тесты, Dockerfile; CI в [.github/workflows/ci-archetype-web-app.yml](../.github/workflows/ci-archetype-web-app.yml) (build, Docker, supply-chain-gate, staging-smoke).
- **Tool create_repo_from_archetype:** копирование архетипа по archetype_id (любой из `archetypes/`), idempotency, audit.
- **CI архетипов:** ci-archetype-catalog.yml, ci-archetype-web-app.yml — gradle build, docker build, supply-chain-gate (Syft, Trivy, Cosign), staging-smoke.
- **Staging:** deploy/staging/docker-compose.yml (catalog-service), scripts/smoke-staging.sh.

### Scale и качество (фаза 5 частично)

- **Trace grading:** формат trace (eval/trace.schema.json), runner [eval/runners/run_offline_evals.py](../eval/runners/run_offline_evals.py), датасет из 3 сценариев.
- **Eval gate в CI:** job eval-regression-gate в [.github/workflows/ci.yml](../.github/workflows/ci.yml) при изменении prompts/, policies/opa/, contracts/tools*, eval/; [ci/quality_gates.py](../ci/quality_gates.py).
- **Нейросервис:** контракт (docs/neural-service-api.md), gateway scripts/neural_gateway/, HttpNeuralServiceClient; LlmAgentPlanner и LlmAgentCodegen подключаются в контур при `NEURAL_SERVICE_URL`, с fallback на stub при ошибке/невалидном JSON.

### CI (GitHub Actions)

- **ci.yml:** kotlin-build, **validate-contracts** (на каждый push и PR: Docker build + `product-factory validate` для contracts-example/ или contracts/), supply-chain-factory (при push в main: build, push в GHCR, SBOM, Trivy, Cosign), live-run (compose up, run_live_run.sh), eval-regression-gate (paths-filter, quality_gates.py).
- **ci-archetype-catalog.yml:** сборка и тесты catalog-service, supply-chain-gate, staging-smoke.
- **ci-archetype-web-app.yml:** сборка и тесты web-app, supply-chain-gate, staging-smoke (контейнер + /health/ready).

### Тесты

- **FactoryRunTest:** сценарий POST `/factory/run` → DONE/audit/artifact registry стабилизирован (без `@Ignore`): путь к `contracts/tools.schema.json` резолвится через classpath (`src/test/resources/contracts/tools.schema.json`) с fallback-поиском от `user.dir`. Полный Docker e2e также проверяется скриптом `scripts/run_live_run.sh` и CI job **live-run**. Варианты и обоснование — [factory-run-test-analysis.md](factory-run-test-analysis.md).
- Остальные: FactoryApprovalApiTest, WorkflowRunnerPolicyTest, PolicyCheckTest, SandboxToolExecutorTest, FileApprovalStoreTest, WorkflowStateMachineTest, ContractValidatorTest, ContractValidationCliTest.

### Чего нет или только заглушки

- **Temporal (или аналог)** — подключён в контур как опциональный durable-режим (`TEMPORAL_ADDRESS`); дефолт без переменной остаётся синхронным.
- **RAG / pgvector** — реализованы ingestion/status/activate (CLI `product-factory rag ...`) с versioning индекса в pgvector; runtime-интеграция в planner опциональна по `RAG_PLANNER_CONTEXT_ENABLED=true` (по умолчанию выключено).
- **Threat model (углубление)** — [threat_model.md](threat_model.md) заполнен (NIST AML, MITRE ATLAS), AI-risk governance/monitoring описаны в [ai-risk-contour-plan.md](ai-risk-contour-plan.md); детальная проработка по архетипам/инфраструктуре остаётся вне scope текущей фазы.
- **SLO / cost для promotion** — enforcement внедрён: pre-run budget gate (`COST_BUDGETS_PATH`, soft/hard cap), Prometheus alerts и CI `slo-gate`; в бэклоге остаются online promotion signals и расширение budget policy на прод-контур.
- **WS2 / WS4** — WS4 MVP (intent schema/protocol/build-mode) реализован; по WS2 зафиксирован scope окружений `local-docker`/`remote-ssh` (ADR 0012), расширенный remote execution остаётся в бэклоге.

---

## Оценка по критериям плана

### Фазы 1–2 — критерии успеха

- DoD и запреты — **выполнено**. Side-effects через tool executor — **зафиксировано**.

### Фаза 3 — критерий успеха

- «Один factory run трассируется end-to-end, все tool calls в audit log» — **выполнено**.

### Фаза 4 (MVP) — критерий успеха

- Архетип, CI, staging, supply-chain gate — **выполнено**. Воспроизводимость «запрос → staging» и rollback через GitOps — процедуры в runbook.

### Фаза 5 (частично) — критерий успеха

- «Offline eval gate блокирует регресс» — **выполнено** (eval-regression-gate в CI). SLO/cost gate в runtime/CI введены; online promotion signals остаются в следующем инкременте.

---

## Рекомендации по приоритетам

1. **Живое использование:** второй архетип web-app добавлен (CI, runbook); прогоны «запрос → артефакт», при необходимости — data-pipeline.
2. **LLM в контуре:** планировщик и кодоген подключены при `NEURAL_SERVICE_URL` (LlmAgentPlanner, LlmAgentCodegen); gateway в `scripts/neural_gateway/` (режимы codex/openai/proxy).
3. **Полировка:** интеграционный `FactoryRunTest` стабилизирован (без `@Ignore`), в Application опциональный `toolExecutor` для тестов; Docker e2e сохранён через runbook/CI live-run. Supply-chain для образа фабрики: job supply-chain-factory в ci.yml. Решения по открытым вопросам MVP — [ADR-0003–0007](adr/0003-deployment-substrate-and-cloud-strategy.md), [decision-points-v1.md](decision-points-v1.md), [risk-register-v1.md](risk-register-v1.md).

---

## Итоговая оценка

- **Документация и контракты:** фазы 1–2 закрыты.
- **Работающий код:** фазы 3, 4 (MVP) и Scale реализованы: планировщик и кодоген с LLM при `NEURAL_SERVICE_URL`, approvals, trace grading, eval gate, Temporal-режим WS0, budget/SLO gates.
- **Готовность к использованию:** фабрика рабочая; архетипы catalog-service и web-app, прогоны с Codex/gateway — см. [how-it-works-and-verify.md](how-it-works-and-verify.md), [runbook.md](runbook.md).

**Общая готовность:** Alpha, Beta, MVP и Scale закрыты; по WS: WS0, WS1, WS3 и WS4 (MVP) закрыты, WS2 — в процессе. Далее по roadmap — online promotion signals, SLSA attestation policy и продуктивизация.
