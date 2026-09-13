# Control Plane vs Intelligence Plane — формальная модель

Краткая модель разделения слоёв фабрики. Основа: [solution_design.md](solution_design.md), [adr/0002-layer-boundaries.md](adr/0002-layer-boundaries.md), [strategic-fork-factory-2.md](strategic-fork-factory-2.md).

---

## 1. Определения

| Плоскость | Назначение | Свойства |
|-----------|------------|----------|
| **Control Plane** | Оркестрация, политики, исполнение side-effects, аудит, desired state | Детерминированный (при одинаковых входах — одинаковый результат), версионируемый, воспроизводимый |
| **Intelligence Plane** | Генерация предложений: планы, ADR, тест-планы, патчи кода | Недетерминированный (LLM); выходы **только** структурированные артефакты по контракту |

---

## 2. Инварианты (границы)

1. **Пересечение границы Control → Intelligence:** только запросы с чётким контрактом (goal, constraints, productSpec, список разрешённых tools). Никаких free-form инструкций от пользователя в управляющую логику без санитизации.

2. **Пересечение границы Intelligence → Control:** только структурированные артефакты, валидируемые по JSON Schema (agent_outputs.schema.json, tools.schema.json). Tool names в плане — только из реестра. Никакого исполнения кода/команд от агента напрямую.

3. **Side-effects:** выполняются **только** в Control Plane через Tool Executor после policy check и при необходимости human approval. Intelligence Plane не имеет права писать в репо, деплоить, вызывать внешние API.

4. **Повторяемость:** ретраи и повторные запуски обрабатываются в Control Plane (idempotency keys, audit). Агент не управляет ретраями.

---

## 3. Поток данных (упрощённо)

```
User Request → Control (API, validation)
    → Control (policy check, approval if needed)
    → Intelligence (Planner: goal → pipeline_plan, adr_draft, test_plan)
    → Control (validate planner output against schema + tool registry)
    → [optional] Intelligence (Codegen: plan → patch proposals)
    → Control (validate codegen output; apply only via tools)
    → Control (Tool Executor: create_repo_from_archetype, etc.)
    → Control (audit, artifact registry)
    → Response to User
```

---

## 4. Что может и чего не может Intelligence Plane

**Может:**
- Генерировать JSON: pipeline_plan, adr_draft, test_plan (Planner).
- Генерировать предложения по коду (patch/diff) как артефакты (Codegen).
- Получать контекст (goal, constraints, productSpec, список tools).

**Не может:**
- Вызывать tools напрямую.
- Писать в репозиторий, деплоить, выдавать секреты.
- Решать, применять ли предложение (решает Control + policy + human approval).
- Управлять ретраями и идемпотентностью.

---

## 5. Связь с Factory 2.0

При подключении LLM к Planner (Factory 2.0) границы не меняются: LLM — реализация Intelligence Plane. Контракт выхода (AgentPlannerArtifacts), валидация по схеме и ограничение tool surface остаются в Control Plane. См. [factory-2-planner-integration.md](factory-2-planner-integration.md).
