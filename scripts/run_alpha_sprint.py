#!/usr/bin/env python3
"""
Оркестр Alpha-спринта через Codex CLI (только на хосте).
В Docker — только Kotlin-код фабрики (см. Dockerfile в корне).

Запуск: python scripts/run_alpha_sprint.py [--dry-run] [--step 1] [--allow-docker] [--no-commit] [--model MODEL] [--resume]
  После успешного завершения шага делается git commit (если есть изменения).
"""
from __future__ import annotations

import sys

from pipeline_lib import PipelineStep, SprintRunner

STEPS = [
    PipelineStep(
        id="1",
        name="JSON Schema для пяти контрактов",
        prompt="""По docs/system-audit-corrective-plan.md (секция "Формализация input contract v1") и docs/alpha-sprint.md создай JSON Schema для:
- product.yaml (ProductSpec)
- constraints.yaml (Constraints)
- quality_profile.yaml (QualityProfile)
- risk_profile.yaml (RiskProfile)
- target_stack.yaml (TargetStack)
Размести в contracts/schemas/. apiVersion: productfactory.io/v1. Используй примеры YAML из плана как эталон.""",
    ),
    PipelineStep(
        id="2",
        name="Валидатор контрактов",
        prompt="""Добавь валидатор контрактов фабрики только на Kotlin: загрузка YAML из директории, проверка по JSON Schema из contracts/schemas/. Детерминированные ошибки (поле, схема). Реализуй как Kotlin-модуль в этом репо; точка входа — при factory run или отдельная команда/CLI (pf validate). Валидатор должен запускаться в существующем Docker-образе фабрики (Dockerfile в корне), без Node/других рантаймов. См. docs/alpha-sprint.md и docs/system-audit-corrective-plan.md.""",
    ),
    PipelineStep(
        id="3",
        name="Risk tiers и мэппинг",
        prompt="""По docs/alpha-sprint.md и docs/system-audit-corrective-plan.md: зафиксируй каталог risk tiers (low/medium/high) и мэппинг на approvals и budgets. Обнови docs/approval-policy.md или docs/risk-register.md (или добавь docs/risk-tier-mapping.md) с явной таблицей/конфигом.""",
    ),
    PipelineStep(
        id="4",
        name="ADR границы слоёв",
        prompt="""По шаблону из docs/system-audit-corrective-plan.md (ADR-0001 в конце) создай ADR «Граница ответственности слоёв и запрет side-effects из agent layer»: контекст, решение (3 пункта), инварианты, триггеры пересмотра. Сохрани как docs/adr/0002-layer-boundaries.md. Свяжи с docs/prohibited-agent-actions.md и docs/solution_design.md.""",
    ),
]


def main() -> int:
    runner = SprintRunner(name="alpha-sprint", steps=STEPS)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
