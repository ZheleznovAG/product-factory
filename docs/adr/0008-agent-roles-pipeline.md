# ADR-0008: Явные роли агентов в pipeline

**Дата:** 2026-02-26  
**Статус:** accepted

## Контекст

Pipeline фабрики уже фиксирует шаги и состояния, но роль исполнителя шага явно не задавалась в модели и audit. Это усложняет разбор run (кто отвечал за планирование, реализацию, тесты и security-review) и подготовку к role-based policy.

## Решение

1. Введён enum ролей `WorkflowAgentRole`:
   - `Planner`
   - `Implementer`
   - `Tester`
   - `Reviewer`
2. Введены явные step-id константы (`WorkflowStepId`) и маппинг `WorkflowStepRoleMapping`.
3. Маппинг шагов pipeline:

| Шаг | Роль |
|---|---|
| `plan_workflow` | `Planner` |
| `generate_artifacts` | `Implementer` |
| `run_tests` | `Tester` |
| `run_security_checks` | `Reviewer` |

4. В audit добавлено поле `role` там, где шаг/событие однозначно относится к роли:
   - `state_changed` для role-mapped шагов;
   - `tool_call_executed` (`Implementer`);
   - `tests_*` (`Tester`);
   - `security_checks_*` (`Reviewer`).

## Последствия

- **Плюсы:** лучше читаемость audit, проще вводить role-based approvals/policy и отчёты по пайплайну.
- **Минусы:** payload audit событий расширен новым полем `role`.
- **Смягчение:** изменение обратно совместимо; существующие события и поля не удалялись.
