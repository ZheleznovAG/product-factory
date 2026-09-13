# Alpha sprint: задачи на 1–2 недели

Старт по плану: 2026-02-20. Цель — закрыть **Фазу Alpha** (Input Contract Spec v1) из [system-audit-corrective-plan.md](system-audit-corrective-plan.md).

---

## Цель спринта

- Вся конфигурация фабрики валидируется до запуска.
- Free-form поля не участвуют в policy decisions (только структурированные поля).

---

## Задачи (приоритет по порядку)

### 1. Схемы для пяти входных контрактов

- [x] Добавить JSON Schema (или аналог) для:
  - `product.yaml` (ProductSpec) — см. пример catalog-service в плане
  - `constraints.yaml` (Constraints)
  - `quality_profile.yaml` (QualityProfile)
  - `risk_profile.yaml` (RiskProfile)
  - `target_stack.yaml` (TargetStack)
- Размещение: например `contracts/schemas/` или рядом с существующими [contracts/](../contracts/).
- Версии: зафиксировать `apiVersion: productfactory.io/v1` в схемах.

### 2. Валидатор контрактов

- [x] Реализовать валидацию по схемам:
  - из кода (Kotlin/библиотека) при старте factory run или отдельной командой;
  - или CLI `pf validate` (если вводится CLI), принимающий путь к директории с YAML.
- [x] Детерминированные сообщения об ошибках (поле, схема, значение).
- Опционально: интеграция в CI (проверка конфигов в репо).

### 3. Каталог risk tiers и мэппинг

- [x] Проверить/дополнить каталог: `low` / `medium` / `high`.
- [x] Явный мэппинг: risk tier → approvals (какие операции требуют approval) и budgets (лимиты токенов/tool calls/wall-clock).
- Документ: обновить [approval-policy.md](approval-policy.md) или [risk-register.md](risk-register.md) ссылкой на мэппинг.

### 4. ADR: границы слоёв и запреты

- [x] Оформить ADR (например 0002 или расширить 0001): «Граница ответственности слоёв и запрет side-effects из agent layer» по шаблону из плана (контекст, решение, инварианты, триггеры пересмотра).
- Связать с [prohibited-agent-actions.md](prohibited-agent-actions.md) и [solution_design.md](solution_design.md).

---

## Критерии приёмки спринта

- Все пять типов конфигов из плана можно валидировать по схемам.
- Валидатор доступен из кода или как команда; ошибки однозначны.
- Risk tiers и мэппинг approvals/budgets зафиксированы.
- ADR по границам слоёв принят и зафиксирован в [docs/adr/](adr/).

---

## Ссылки

- [system-audit-corrective-plan.md](system-audit-corrective-plan.md) — фаза Alpha, форматы YAML, шаблон ADR
- [roadmap-checklist.md](roadmap-checklist.md) — общий прогресс по фазам
- [phases-task-list.md](phases-task-list.md) — задачи по фазам 1–7
