# Стратегическая развилка: Factory 1.0 → Factory 2.0

**Статус:** принятая рамка эволюции  
**Основа:** разбор текущего состояния ([roadmap-checklist.md](roadmap-checklist.md), [implementation-assessment.md](implementation-assessment.md)) и принцип «сначала стабильность, потом интеллект».

---

## 1. Текущая реальность

Построено **не прототип**, а минимальная управляемая фабрика:

- Детерминированный execution engine
- Stateful orchestration (без Temporal)
- Политики (OPA), human approvals, supply-chain gates, eval gate
- Архетипы с CI, контракты, audit trail

Это **Factory 1.0** = deterministic template engine: создаёт **варианты архетипов**, а не **новые продукты из intent**.

---

## 2. Расхождение с «изначальной мечтой»

| Задумывалось | Сейчас (при NEURAL_SERVICE_URL) |
|--------------|----------------------------------|
| User → LLM Planner → LLM Codegen → Workflow → Tools → Repo → CI → Deploy | User → **LlmAgentPlanner** → **LlmAgentCodegen** → Workflow → Archetype Tool → Repo → CI → Deploy |

Без NEURAL_SERVICE_URL по-прежнему Stub Planner + Stub Codegen. Gateway: `scripts/neural_gateway/` (codex/openai/proxy).

Следствия:

- Intelligence Plane подключён (планировщик и кодоген с LLM при настройке URL)
- Нет генерации архитектурных решений и adaptive planning
- Настоящий AI-risk management — впереди

При подключённом NEURAL_SERVICE_URL фабрика использует LLM для планирования и кодогена; без URL остаётся детерминированный режим (stub). Дальнейшие узкие места — Temporal, RAG, SLO/cost, полноценный AI-risk.

---

## 3. Три пути развития

### Путь 1 — Закрепить ядро (консервативный)

- Обязательные digest в audit, hash-chain
- SLO/cost enforcement
- Supply chain на саму фабрику
- Temporal

**Результат:** Enterprise-grade control plane.  
**Минус:** без «AI-multiplication effect».

### Путь 2 — Подключить LLM везде (агрессивный)

- NeuralServiceClient в Planner и Codegen
- Полная генерация решений и кода

**Результат:** фабрика генерирует решения.  
**Риск:** без аккуратного governance — неконтролируемая агентность.

### Путь 3 — Гибрид (рекомендуемый)

1. Подключить LLM **только к Planner**.
2. Codegen оставить архетипным + diff-патчи (без «сырого» генератора кода в контуре).
3. Сохранить trace-grading и eval gate.
4. Ввести/уже использовать auto-eval как gate.

**Результат:** Controlled AI без разрушения deterministic core.  
**Следующий шаг:** проверить, может ли фабрика с реальным Planner производить осмысленные архитектуры и оставаться управляемой.

---

## 4. Выбранное направление

**Стратегический выбор:** Путь 3 (гибрид).

- **Factory 1.0** = deterministic product engine (текущее состояние).
- **Factory 2.0** = controlled AI product engine: реальный Planner, архетипный/diff Codegen, те же Tool Executor, state machine, policy.

Эволюция на 2 года (порядок сохраняем):

1. Controlled Planner (LLM только для плана)
2. Diff-based Codegen (патчи к архетипам)
3. Eval gating (уже есть, усилить)
4. Cost-aware routing
5. Self-refinement loops (при необходимости)
6. Temporal (при росте нагрузки)
7. Multi-tenant isolation (при масштабировании)

---

## 5. Temporal и RAG — когда нужны

- **Temporal** — когда: >20 параллельных run, >2 ч средняя длительность, регулярные падения воркеров, нужен replay. Сейчас WorkflowRunner достаточен; Temporal — scaling lever, не feature lever.
- **RAG** — когда: реальная генерация сложных систем, накопленная база best practices, нужна retrieval memory. Пока нет реального codegen — RAG декоративен.

---

## 6. Следующий конкретный шаг

Подключить **Planner к реальному LLM**, не меняя:

- Tool Executor
- State machine
- Policy слой

Технический план — в [factory-2-planner-integration.md](factory-2-planner-integration.md). Полный объём работ и автоматизированный спринт — [sprint-full-backlog.md](sprint-full-backlog.md), [sprint-factory2-howto.md](sprint-factory2-howto.md), `scripts/run_factory2_full_sprint.py`.

---

## Связанные документы

- [neural-service-api.md](neural-service-api.md) — контракт LLM gateway
- [neural-service-operations.md](neural-service-operations.md) — эксплуатация
- [whats-next.md](whats-next.md) — очередь задач
- [solution_design.md](solution_design.md) — Control vs Intelligence Plane
