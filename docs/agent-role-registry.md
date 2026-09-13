# Реестр ролей агентов и role-based prompt templates

Документ фиксирует реестр ролей для агентного слоя Product Factory и то, как role-specific правила используются в LLM-компонентах.

## Цель

- Централизовать роли агентного контура (`Planner`, `Implementer`, `Tester`, `Reviewer` и др.).
- Хранить для роли единый профиль: название, ответственности, правила, id шаблона, `system/user` шаблоны.
- Применять role-specific правила при генерации промптов в `LlmAgentPlanner` и `LlmAgentCodegen`.

## Где реализовано

- Реестр: `src/main/kotlin/productfactory/agent/AgentRolePromptRegistry.kt`
- Использование в планировщике: `src/main/kotlin/productfactory/agent/LlmAgentPlanner.kt`
- Использование в кодогене: `src/main/kotlin/productfactory/agent/LlmAgentCodegen.kt`

## Состав ролей v1

- `PLANNER`
- `CODEGEN`
- `IMPLEMENTER`
- `TESTER`
- `REVIEWER`
- `ARCHITECT`
- `SECURITY_REVIEWER`
- `RELEASE_MANAGER`

Расширение списка ролей выполняется добавлением нового `AgentPromptRole` и `AgentRoleProfile` в реестр.

## Структура профиля роли

Каждая роль описывается через `AgentRoleProfile`:

- `title`: человекочитаемое имя роли.
- `responsibilities`: список обязанностей роли.
- `rules`: набор role-specific правил.
- `template`: `id` и `system/user` шаблоны промптов.

Шаблоны рендерятся через `AgentRolePromptRegistry.renderSystem/renderUser` с подстановкой переменных. В `system` автоматически подмешиваются:

- `{{role_title}}`
- `{{role_responsibilities}}`
- `{{role_rules}}`

## Интеграция в LLM-агенты

### LlmAgentPlanner

- Использует профиль `PLANNER` (`planner/v2`).
- В prompt передаются ограничения по tool allowlist (`allowed_tools`).
- Дополнительно включается `downstream_role_rules`: агрегированные правила `IMPLEMENTER`, `TESTER`, `REVIEWER`, чтобы pipeline-план учитывал downstream governance.

### LlmAgentCodegen

- Использует профиль `CODEGEN` (`codegen/v2`).
- Дополнительно включается `collaboration_role_rules`: агрегированные правила `IMPLEMENTER`, `TESTER`, `REVIEWER`, чтобы codegen-предложения были совместимы с проверкой и ревью.

## Границы и безопасность

- Реестр ролей влияет только на генерацию предложений (prompts/rules).
- Исполнение side-effects остаётся в deterministic core через Tool Executor + Policy/Approval.
- Агентные роли не получают прямых прав на write/deploy вне governance контура.

См. также: `docs/adr/0002-layer-boundaries.md`, `docs/prohibited-agent-actions.md`.
