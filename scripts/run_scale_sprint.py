#!/usr/bin/env python3
"""
Оркестр Scale-спринта (AI как Proposal Engine) через Codex CLI.
Фаза Scale по docs/roadmap-checklist.md: агент-планировщик, кодоген, approvals, trace grading.

Запуск: python scripts/run_scale_sprint.py [--dry-run] [--step 1] [--allow-docker] [--no-commit] [--model MODEL] [--resume]
"""
from __future__ import annotations

import sys

from pipeline_lib import PipelineStep, SprintRunner

STEPS = [
    PipelineStep(
        id="1",
        name="Агент-планировщик (pipeline plan, ADR draft, test plan)",
        prompt="""В Product Factory добавь компонент «агент-планировщик»: по входу (goal, контракты или product spec) он генерирует структурированные JSON-артефакты: (1) pipeline_plan — план шагов пайплайна (steps с id, description, tool, depends_on), (2) adr_draft — черновик ADR (context, decision, consequences), (3) test_plan — план тестов (scope, cases, coverage targets). Схемы для этих типов добавь в contracts/agent_outputs.schema.json или в отдельные JSON Schema в contracts/schemas/. Реализацию можно начать с модуля-заглушки в src/main/kotlin/productfactory/agent/ (или src/agent/), который возвращает фиксированный JSON по контракту; позже подключается LLM. API или вызов из WorkflowRunner — по твоему выбору (например после policy_check). См. docs/system-audit-corrective-plan.md (фаза Scale), contracts/agent_outputs.schema.json (execution_plan, task_graph), docs/adr/template.md.""",
    ),
    PipelineStep(
        id="2",
        name="Агент-кодоген (patch sets, git diff, без merge)",
        prompt="""Добавь компонент «агент-кодоген»: он генерирует предложения по коду в виде patch sets (git diff-формат или структурированный JSON с path, old_content, new_content). Важно: кодоген не выполняет merge/commit — только возвращает предложения (proposals), которые затем могут быть применены через отдельный tool с approval. Реализуй в src/main/kotlin/productfactory/agent/ (или рядом с планировщиком): интерфейс или класс CodegenProposal с полями file path, patch/diff, summary; вызов из workflow только после планировщика и policy. Запись предложений в audit log (runId, proposal_type: codegen, file_count). См. docs/system-audit-corrective-plan.md (Scale: patch sets, без прямого merge), docs/prohibited-agent-actions.md, docs/adr/0002-layer-boundaries.md.""",
    ),
    PipelineStep(
        id="3",
        name="Approvals для write/deploy (human-in-the-loop)",
        prompt="""В Product Factory уже есть require_human_approval в policy (OPA) и отклонение с сообщением «Human approval required». Доработай human-in-the-loop: (1) когда policy возвращает require_human_approval, сохранять запрос на approval в хранилище (файл, директория approvals/ или in-memory с персистом) с runId, timestamp, reason, proposed_actions. (2) Добавить способ «одобрить» или «отклонить» (REST endpoint или CLI команда, например POST /factory/approvals/{runId}/approve или product-factory approve <runId>). (3) После одобрения — разрешить продолжение workflow для этого runId (или повторную отправку). Документируй в docs/runbook.md. См. docs/approval-policy.md, docs/risk-register.md, policies/opa/rego/factory.rego.""",
    ),
    PipelineStep(
        id="4",
        name="Trace grading и eval runs (regression gate)",
        prompt="""Шаг 4 Scale: trace grading и regression gate уже реализованы. Нужно проверить и при необходимости дополнить.

Уже есть: (1) Формат trace в eval/trace.schema.json и примеры в eval/traces/. (2) Runner eval/runners/run_offline_evals.py и датасет eval/datasets/agent_trace_minimal.json (два сценария). (3) ci/quality_gates.py и job eval-regression-gate в .github/workflows/ci.yml (триггер: изменения в prompts/, policies/opa/, contracts/tools*, eval/). Спецификация: docs/scale-step4-trace-eval-gate.md.

Сделай: (1) Убедись, что локально проходит: python3 ci/quality_gates.py --mode agent --dataset eval/datasets/agent_trace_minimal.json. (2) Проверь, что в docs/runbook.md есть краткая секция про запуск eval gate (локально и в CI). (3) По желанию добавь один новый сценарий в eval/datasets/ (например run с approval_required и outcome rejected) или обнови eval/README.md ссылкой на docs/scale-step4-trace-eval-gate.md. Не ломай существующие тесты и CI.""",
    ),
]


def main() -> int:
    runner = SprintRunner(name="scale-sprint", steps=STEPS)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
