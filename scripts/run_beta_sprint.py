#!/usr/bin/env python3
"""
Оркестр Beta-спринта (Execution Core) через Codex CLI.
Фаза Beta по docs/roadmap-checklist.md: state machine, OTel, OPA, sandbox executor, артефакт-реестр.

Запуск: python scripts/run_beta_sprint.py [--dry-run] [--step 1] [--allow-docker] [--no-commit] [--model MODEL] [--resume]
"""
from __future__ import annotations

import sys

from pipeline_lib import PipelineStep, SprintRunner

STEPS = [
    PipelineStep(
        id="1",
        name="State machine в workflow",
        prompt="""В этом репо Product Factory (Kotlin/Ktor) добавь явную state machine в workflow. Состояния по плану: NEW → PLANNED → GENERATED → TESTED → SECURED → STAGED → DONE или FAILED. WorkflowRunner и связанный код в src/main/kotlin/productfactory/workflow/ должны переходить по состояниям; каждое изменение состояния пиши в audit log (runId, stepId, newState). Не меняй контракты API; сохрани совместимость POST /factory/run. См. docs/system-audit-corrective-plan.md (фаза Beta), docs/solution_design.md.""",
    ),
    PipelineStep(
        id="2",
        name="OpenTelemetry (OTel) spans",
        prompt="""Добавь OpenTelemetry в приложение Product Factory (Kotlin/Ktor): один span на весь factory run, дочерние spans на policy_check, validation, agent_spec (или текущие шаги workflow). Экспорт в консоль или OTLP — на твой выбор, но через стандартный OTel API. Зависимости добавь в build.gradle.kts; код в src/main/kotlin. См. docs/system-audit-corrective-plan.md (Observability), docs/implementation-assessment.md (OTel отсутствует).""",
    ),
    PipelineStep(
        id="3",
        name="Вызов OPA из приложения",
        prompt="""В Product Factory сейчас PolicyCheck — заглушка (читает YAML, всегда allow). Реализуй реальный вызов OPA: по переменной окружения OPA_URL (HTTP) отправлять запрос с входом goal, tool_calls, token_usage (или аналог из policies/opa/data.json и factory.rego). Решение allow/deny и require_human_approval использовать в WorkflowRunner. Decision log — по настройке OPA или логировать ответ в audit log. См. policies/opa/rego/factory.rego, policies/opa/data.json, docs/implementation-assessment.md.""",
    ),
    PipelineStep(
        id="4",
        name="Sandbox executor и один tool",
        prompt="""Добавь sandbox executor в Product Factory: компонент, который выполняет tool calls по контракту (contracts/tools.schema.json). Реализуй один tool с side-effect, например create_repo_from_archetype (или stub, который пишет в audit log и возвращает успех). Обязательно: idempotency key в контракте вызова; запись каждого вызова в audit log (runId, toolName, idempotencyKey, result). Интеграция с WorkflowRunner: после agent_spec (или планировщика) вызывать executor для разрешённых tools. См. docs/tools-list-mvp.md, docs/adr/0002-layer-boundaries.md.""",
    ),
    PipelineStep(
        id="5",
        name="Артефакт-реестр (run record)",
        prompt="""Добавь артефакт-реестр в Product Factory: один «run record» на factory run, в котором связываются runId, версии артефактов (репо, образ, SBOM, подпись — поля могут быть опциональными/заглушками). Хранение: in-memory или файл/директория в формате JSON/JSONL; API не обязательно, достаточно записи из WorkflowRunner при переходе в STAGED/DONE. См. docs/roadmap-checklist.md (Beta: артефакт-реестр).""",
    ),
]


def main() -> int:
    runner = SprintRunner(name="beta-sprint", steps=STEPS)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
