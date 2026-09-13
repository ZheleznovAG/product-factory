# Протокол handoff артефактов между ролями

## Цель

Зафиксировать контракт обмена артефактами между ролями pipeline:

1. `Planner`
2. `Implementer`
3. `Tester`
4. `Reviewer`

Документ описывает внутренний handoff внутри run и не меняет внешние API-контракты (`apiVersion: productfactory.io/v1`).

## Последовательность ролей и артефактов

1. `Planner` формирует пакет планирования:
   - `pipeline_plan`
   - `adr_draft`
   - `test_plan`
2. `Implementer` принимает план и формирует:
   - `codegen_patch_set` (патчи/proposals)
   - результаты вызовов tools (`tool_call_executed`, `ToolCallResult`)
3. `Tester` формирует тестовый отчёт/вердикт:
   - `QaGateOutput` (`verdict`, `summary`, `artifactRefs`)
4. `Reviewer` формирует security-вердикт:
   - `SecGateOutput` (`verdict`, `summary`, `artifactRefs`)

## Handoff-контракт в RunContext

`RunContext` — единый context pack run: [RunContext](../src/main/kotlin/productfactory/workflow/RunContext.kt).

| Роль | Артефакт | Поле RunContext | Тип |
|---|---|---|---|
| Planner | `pipeline_plan`, `adr_draft`, `test_plan` | `plannerArtifacts` | `AgentPlannerArtifacts` |
| Implementer | `codegen_patch_set` | `codegenArtifact` | `CodegenPatchSetArtifact` |
| Tester | test report/verdict | `qaOutput` | `QaGateOutput` |
| Reviewer | security verdict | `secOutput` | `SecGateOutput` |

Примечание: отдельные поля для Tester/Reviewer добавлять не требуется, так как `qaOutput` и `secOutput` уже являются ролевыми output-контрактами.

## Где хранится результат tools у Implementer

Handoff по tools разделён на два уровня:

1. Шаговый результат выполнения tools:
   - [ToolStepResult](../src/main/kotlin/productfactory/workflow/WorkflowStepResults.kt) (`repoUrl`, `artifactLocation`, `sbomVersion`, `signatureVersion`)
2. Детализированный audit по каждому вызову:
   - событие `tool_call_executed` в `DockerToolExecutor`/`SandboxToolExecutor` с полем `result` (`ToolCallResult`)
3. Сводное состояние артефактов run:
   - [ArtifactRunRecord](../src/main/kotlin/productfactory/workflow/ArtifactRegistry.kt) через `artifact_registry_updated`

Таким образом:

1. `RunContext.codegenArtifact` фиксирует proposal-уровень (что предложено изменить).
2. tool-аудит и `ToolStepResult` фиксируют execution-уровень (что реально выполнено инструментами).

## Инварианты протокола

1. Handoff идёт строго по ролям: `Planner -> Implementer -> Tester -> Reviewer`.
2. Агентный слой передаёт proposals и verdicts; side-effects выполняются только через Tool Executor и policy/approval.
3. Для каждого role handoff есть сериализуемый артефакт с возможностью аудита.
