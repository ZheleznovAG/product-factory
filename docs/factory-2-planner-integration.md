# Подключение LLM к Planner (Factory 2.0)

План внедрения реального Planner без изменения Tool Executor, state machine и policy. Основа: [strategic-fork-factory-2.md](strategic-fork-factory-2.md), [neural-service-api.md](neural-service-api.md).

**Статус:** реализовано. Planner — `LlmAgentPlanner` при `NEURAL_SERVICE_URL`; позже добавлен `LlmAgentCodegen` (кодоген тоже через нейросервис с fallback на stub).

---

## Цель (выполнена)

Заменить `StubAgentPlanner` на реализацию, вызывающую `NeuralServiceClient`, при заданном `NEURAL_SERVICE_URL`. Аналогично кодоген: `LlmAgentCodegen` при `NEURAL_SERVICE_URL`, иначе `StubAgentCodegen`.

---

## Ограничения (не трогаем)

- `WorkflowRunner` — только подмена зависимости `AgentPlanner`
- `ToolExecutor`, `PolicyCheck`, `ApprovalStore`, `AuditLog` — без изменений
- Контракт выхода планировщика — существующие `AgentPlannerArtifacts` (pipelinePlan, adrDraft, testPlan) и [contracts/agent_outputs.schema.json](../contracts/agent_outputs.schema.json)

---

## Шаги реализации

### 1. Реализация `LlmAgentPlanner`

- Класс `LlmAgentPlanner` (или `NeuralAgentPlanner`), реализующий `AgentPlanner`.
- Зависимость: `NeuralServiceClient` (уже есть `HttpNeuralServiceClient`).
- Вход: `AgentPlannerInput` (goal, constraints, productSpec, contracts).
- Выход: `AgentPlannerArtifacts` — **обязательно** парсинг из JSON ответа LLM с валидацией по схеме `agent_outputs.schema.json` (или эквивалентная проверка структур).

Требования к вызову LLM:

- Низкая temperature (например 0.2–0.3) для предсказуемости.
- Structured output: запрашивать JSON, соответствующий `PipelinePlanArtifact`, `AdrDraftArtifact`, `TestPlanArtifact` (или один JSON с тремя блоками).
- Таймаут и retry — по [decision-points-v1.md](decision-points-v1.md) (Planner p99 <20s, timeout 30s).
- При ошибке/таймауте — fallback на `StubAgentPlanner` или явный отказ с записью в audit.

### 2. Промпт и контракт

- Системный промпт: роль Planner, формат ответа (JSON schema или пример), запрет на free-form вне схемы.
- В контекст передавать: goal, constraints, productSpec (если есть), список доступных tools (из Tool Registry / contracts) — чтобы план ссылался только на разрешённые tools.
- Ограничить tool surface: в плане допустимы только инструменты из реестра (валидация после парсинга).
- Реализация шаблонов вынесена в единый реестр ролей `AgentRolePromptRegistry`:
  - `PLANNER` (`planner/v2`) используется в `LlmAgentPlanner`.
  - `CODEGEN` (`codegen/v2`) используется в `LlmAgentCodegen`.
  - Базовые роли: `Planner`, `Implementer`, `Tester`, `Reviewer`; расширенные: `Architect`, `SecurityReviewer`, `ReleaseManager`.
  - Реестр хранит role-specific `system/user` шаблоны, responsibilities/rules и рендерит переменные (`goal`, `constraints`, `allowed_tools`, `planner_steps`, `rag_context_section`, role contracts).
  - Отдельное описание: [agent-role-registry.md](agent-role-registry.md).

### 3. Внедрение в контур

- Реализовано в `Application.kt`: при `NEURAL_SERVICE_URL` создаётся `HttpNeuralServiceClient`, передаётся в `LlmAgentPlanner` и `LlmAgentCodegen`; иначе оба stub.
- Codegen: `LlmAgentCodegen` при `NEURAL_SERVICE_URL` (audit `agent_codegen_call`), fallback на `StubAgentCodegen`.

### 4. Наблюдаемость и governance

- Логировать в audit: факт вызова Planner, модель (если есть), latency, успех/fallback/ошибка; **не** логировать полные промпты/ответы по умолчанию (см. [adr/0005-log-audit-trace-retention.md](adr/0005-log-audit-trace-retention.md)).
- OTel: span на шаг планировщика (уже есть общий span на шаги в WorkflowRunner — при необходимости дочерний span на `planner.generate`).
- Eval: существующий eval-regression-gate и trace grading распространить на прогоны с LLM Planner (датасеты с ожидаемыми артефактами или градация по критериям).

### 5. Тесты

- Unit: мок `NeuralServiceClient`, возвращающий валидный JSON под схему; проверка, что `LlmAgentPlanner.generate()` возвращает корректные `AgentPlannerArtifacts`.
- Интеграционный (опционально): с тестовым эндпоинтом или записанным ответом; проверка fallback при 5xx/таймауте.
- Существующие тесты `StubAgentPlanner` и контракта `agent_outputs.schema.json` оставить; добавить тесты для LLM-реализации.

---

## Критерий успеха

После внедрения:

- При `NEURAL_SERVICE_URL` заданном фабрика вызывает LLM для шага планирования.
- Артефакты планировщика проходят валидацию по схеме и не расширяют tool surface.
- При недоступности LLM или невалидном ответе — явный fallback или отказ с записью в audit, без падения контура.
- Tool Executor, state machine, policy не меняются.

---

## Связанные файлы

- [AgentPlanner.kt](../src/main/kotlin/productfactory/agent/AgentPlanner.kt) — интерфейс и Stub
- [WorkflowRunner.kt](../src/main/kotlin/productfactory/workflow/WorkflowRunner.kt) — внедрение Planner
- [NeuralServiceClient.kt](../src/main/kotlin/productfactory/neural/NeuralServiceClient.kt), [HttpNeuralServiceClient.kt](../src/main/kotlin/productfactory/neural/HttpNeuralServiceClient.kt)
- [contracts/agent_outputs.schema.json](../contracts/agent_outputs.schema.json)
