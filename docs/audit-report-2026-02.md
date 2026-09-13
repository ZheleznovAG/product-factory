# Аудит Product Factory (февраль 2026)

**Дата:** 2026-02-20  
**Область:** документация, код, тесты, CI, риски, соответствие roadmap и правилам проекта.  
**Основа:** [implementation-assessment.md](implementation-assessment.md), [roadmap-checklist.md](roadmap-checklist.md), [system-audit-corrective-plan.md](system-audit-corrective-plan.md), [AGENTS.md](../AGENTS.md).

---

## 1. Резюме

| Область | Оценка | Комментарий |
|--------|--------|-------------|
| Документация | **Хорошо** | Доки согласованы; Alpha–Scale закрыты везде; единичные расхождения (см. ниже). |
| Архитектура и границы | **Соответствует** | Control/Intelligence Plane, side-effects только через Tool Executor, ADR-0002 соблюдён. |
| Контракты и схемы | **В порядке** | 5 входных схем, tools.schema, contracts-example, валидатор в CLI и при старте. |
| Код и тесты | **Приемлемо** | Unit/интеграционные тесты есть; один e2e с @Ignore, компенсирован Docker/runbook. |
| CI/CD | **В порядке** | kotlin-build, validate-contracts, supply-chain-factory, eval-regression-gate; архетипы с supply-chain-gate и staging-smoke. |
| Риски и политики | **Задокументированы** | Risk register, approval-policy, prohibited actions, threat model; исполнение — в коде и runbook. |

**Итог:** Фабрика в заявленном состоянии (Alpha–Scale закрыты). Критичных расхождений нет. Рекомендации — точечные правки в документации и приоритизация следующих фаз (SLO/cost, Temporal, supply-chain углубление).

---

## 2. Документация

### 2.1 Согласованность

- **Фазы Alpha, Beta, MVP, Scale** — везде помечены как закрытые: roadmap-checklist, implementation-assessment, phases-task-list, README, whats-next, strategic-fork, AGENTS.md.
- **LLM в контуре:** LlmAgentPlanner и LlmAgentCodegen при `NEURAL_SERVICE_URL` описаны единообразно (runbook, how-it-works-and-verify, agent-vs-llm-in-factory, factory-2-planner-integration, neural-service-api).
- **Health:** GET /health, /health/ready, /health/neural — отражены в whats-next и runbook.

### 2.2 Расхождения и правки

| Где | Что | Рекомендация |
|-----|-----|--------------|
| **roadmap-checklist** | Event log: «Частично» — «нет обязательных полей digest» | В коде `AuditLog.kt` и `SandboxToolExecutor` уже пишут inputDigest/outputDigest (в т.ч. дефолты). Уточнить формулировку: «digest в state_changed и tool_call_executed есть; полное покрытие всех событий — по необходимости». |
| **whats-next** | «Валидация контрактов в CI (опционально)» | Job **validate-contracts** уже есть в ci.yml (при изменении contracts/ и при push). Пометить как «Есть: job validate-contracts в ci.yml». |
| **open-questions** | «Health endpoint — рекомендуется (must)» | Health уже реализован; в open-questions отметить как выполненное или убрать из «опциональных». |

### 2.3 Полнота

- PRD, DoD, risk register, approval-policy, secrets-policy, prohibited-agent-actions, data-map, ADR 0001–0007, decision-points-v1, risk-register-v1 — на месте.
- Runbook описывает запуск, health, нейросервис, прогон с gateway, audit, approvals.
- Отсутствуют или минимальны: пошаговый операционный runbook для инцидентов (можно вынести из risk-register контингенси-планы).

---

## 3. Код и архитектура

### 3.1 Границы слоёв (ADR-0002)

- Side-effects только через Tool Executor (SandboxToolExecutor, create_repo_from_archetype с idempotency).
- Агентный слой возвращает структурированные предложения (план, patch sets); merge/запись не выполняет.
- Policy (OPA при OPA_URL) и валидация контрактов (FACTORY_CONTRACTS_DIR) — до выполнения инструментов.

### 3.2 Структура

- API (Ktor), WorkflowRunner, state machine, FileAuditLog, ApprovalStore (InMemory + File), ContractValidator, OTel, policy (OpaPolicyCheck), агенты (Stub/Llm Planner и Codegen), SandboxToolExecutor, FileArtifactRegistry — соответствуют solution_design и implementation-assessment.

### 3.3 Контракты

- Схемы: product, constraints, quality_profile, risk_profile, target_stack (contracts/schemas/).
- contracts-example/ — полный набор YAML для CI и примеров.
- Валидация: CLI `product-factory validate <dir>`, при FACTORY_CONTRACTS_DIR — при старте приложения.

---

## 4. Тесты и CI

### 4.1 Тесты

- **Активные:** FactoryApprovalApiTest, WorkflowRunnerPolicyTest, WorkflowRunnerPlannerTest, PolicyCheckTest, SandboxToolExecutorTest, FileApprovalStoreTest, WorkflowStateMachineTest, ContractValidatorTest, ContractValidationCliTest, StubAgentPlannerTest, StubAgentCodegenTest, LlmAgentPlannerTest, LlmAgentCodegenTest (с моками/заглушками).
- **Отключён:** FactoryRunTest (один e2e с @Ignore) — причина зафиксирована (CWD/окружение в testApplication); проверка того же сценария — через FactoryApprovalApiTest, Docker и runbook (implementation-assessment, комментарий в коде).

### 4.2 CI (ci.yml)

- **kotlin-build:** Gradle build (тесты включены).
- **validate-contracts:** Docker build + `product-factory validate` для contracts-example (или contracts/).
- **supply-chain-factory:** при push в main — build, push в GHCR, Syft (SBOM), Trivy, Cosign sign/verify.
- **eval-regression-gate:** paths-filter по prompts, policies/opa, contracts/tools*, eval/; quality_gates.py с датасетом agent_trace_minimal.json.

Архетипы: ci-archetype-catalog.yml, ci-archetype-web-app.yml — build, Docker, supply-chain-gate, staging-smoke.

---

## 5. Риски и комплаенс

- **Risk register (docs/risk-register.md):** R-001–R-011 с митигацией и контингенси; владелец и триггеры пересмотра указаны.
- **Approval policy:** человеческое подтверждение для привилегированных операций; API и CLI реализованы.
- **Prohibited actions:** задокументированы; исполнение — через Tool Executor и policy.
- **Supply chain:** SBOM (Syft), Trivy, Cosign для образа фабрики и архетипов; SLSA/AI-risk контур — в бэклоге (phases-task-list, whats-next).

---

## 6. Рекомендации по приоритетам

1. **Документация (низкий порог):**  
   - Обновить roadmap-checklist: уточнить формулировку по inputDigest/outputDigest.  
   - В whats-next: пометить валидацию контрактов в CI как выполненную.  
   - В open-questions: отметить health endpoint как выполненный.

2. **Полировка:**  
   - FactoryRunTest: либо стабилизировать (toolExecutor/окружение) и снять @Ignore, либо оставить @Ignore и явно описать замену (Docker/runbook) в одном месте (например, в implementation-assessment уже есть).

3. **Следующие фазы (по roadmap):**  
   - SLO/cost для promotion (черновик в decision-points-v1).  
   - Durable engine (Temporal/аналог) при росте требований к длинным прогонам и ретраям.  
   - Углубление supply chain (SLSA, AI-risk контур по NIST AI RMF / ISO 42001).

---

## 7. Соответствие AGENTS.md и правилам

- Запуск и проверка — только через Docker (сборка и тесты в образе); локальный Gradle — без обязательной установки JDK на хосте для «проверки».
- Секреты — через env / .env.example; в коде секретов нет.
- Side-effects только через Tool Executor; запреты агента соблюдены.
- Контракты и схемы — apiVersion productfactory.io/v1, каталог contracts/schemas/.
- Ключевые документы (system-audit-corrective-plan, roadmap-checklist, solution_design, implementation-assessment, runbook, how-it-works-and-verify) актуальны и ссылаются друг на друга.

---

**Вывод:** Аудит не выявил критичных несоответствий. Документация и код приведены в соответствие с текущим состоянием (Alpha–Scale закрыты, LLM в контуре при NEURAL_SERVICE_URL). Рекомендуется выполнить точечные правки в документации из раздела 6 и далее двигаться по roadmap (SLO/cost, durable engine, supply chain, продуктивизация).
