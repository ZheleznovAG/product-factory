# Agent runtime

Phase 2-3. Supervisor, tools, prompts; structured outputs only; tool calls via registry.

## Role Prompt Registry

- `AgentRolePromptRegistry` (`src/main/kotlin/productfactory/agent/AgentRolePromptRegistry.kt`) хранит шаблоны промптов по ролям (`PLANNER`, `CODEGEN`, `IMPLEMENTER`, `TESTER`, `REVIEWER`).
- `LlmAgentPlanner` и `LlmAgentCodegen` берут `system/user` промпты через этот реестр, а не из локальных строк.
- Для трассировки версий в system prompt включается marker шаблона (`planner/v1`, `codegen/v1`).
