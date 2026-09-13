#!/usr/bin/env python3
"""
Planning Pipeline: продумывание и детализация плана технологий и задач на основе архитектуры и roadmap.

Читает architecture-and-path, roadmap-workstreams-and-variants, solution_design и генерирует полный документ
docs/technology-and-implementation-plan.md: стек (взрослый open-source, с альтернативами без жёсткой привязки),
архитектура, детальный план задач, интеграция/эксплуатация. Цель — чтобы осталось только делать.

Запуск:
  python3 scripts/run_planning_pipeline.py [--dry-run] [--allow-docker] [--step N] [--resume]
  python3 scripts/run_pipeline_with_resume.py  # если использовать обёртку с возобновлением при лимите
"""
from __future__ import annotations

import sys
from pathlib import Path

from pipeline_lib import (
    PipelineStep,
    SprintRunner,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS = REPO_ROOT / "docs"
OUT_PART1 = DOCS / "technology-and-implementation-plan-part1.md"
OUT_PART2 = DOCS / "technology-and-implementation-plan-part2.md"
OUT_PART3 = DOCS / "technology-and-implementation-plan-part3.md"
OUT_PART4 = DOCS / "technology-and-implementation-plan-part4.md"
OUT_FINAL = DOCS / "technology-and-implementation-plan.md"

SOURCES = (
    "docs/architecture-and-path.md, docs/roadmap-workstreams-and-variants.md, "
    "docs/solution_design.md, docs/adr/0015-language-strategy-by-layer.md."
)

STEPS_BUILTIN = [
    PipelineStep(
        "1",
        "Технологический стек и обоснование",
        f"""Ты работаешь в репозитории Product Factory (корень репо — текущая директория).

Задача: на основе видения, дорожной карты и архитектуры подготовить раздел «1. Технологический стек» итогового плана.

Обязательно прочитай файлы: {SOURCES}

Требования к стеку:
- Только зрелый open-source. Для каждого компонента: основная выбранная технология + 1–2 альтернативы (чтобы можно было переключаться без жёсткой зависимости).
- Слои: Perception, Intent & Cognitive Modeling, Strategy Synthesis, Control Plane, Capability & Tool Graph, Outcome Intelligence, Self-Evolution Loop; плюс Memory, Observability, Experimentation, Governance.
- Укажи конкретные названия (Kotlin, Ktor, Postgres, pgvector, OPA, Temporal, Prometheus, Grafana, OpenTelemetry, и т.д.), версии где критично (например JDK 17, Postgres 15+), краткое обоснование выбора и альтернативы.
- Не включай технологии, для которых нет зрелых open-source решений — их можно упомянуть в разделе «Пока не определено» в конце документа.

Результат запиши в файл: {OUT_PART1.relative_to(REPO_ROOT)}

Структура раздела: введение (1–2 абзаца), таблица или список по слоям/компонентам (компонент, основная технология, альтернативы, обоснование), подраздел «Варианты стека» (минимальный MVP / Phase 2 / Phase 3). Язык: русский, термины при необходимости на английском."""
    ),
    PipelineStep(
        "2",
        "Архитектура и компоненты",
        f"""Ты работаешь в репозитории Product Factory.

Задача: детализировать раздел «2. Архитектура и компоненты» плана реализации.

Прочитай: {SOURCES}, а также созданный ранее {OUT_PART1.relative_to(REPO_ROOT)} (если есть).

Нужно:
- Описать слои системы в соответствии с architecture-and-path: потоки данных, ключевые интерфейсы (API, контракты), границы между Control и Intelligence.
- Для каждого значимого компонента: назначение, входы/выходы, связь с другими компонентами, где хранятся конфиги и состояние.
- Указать контракты (IntentSpec, IntentSession, Tool Registry, Capability Registry) и где они определены (схемы, репо).
- Диаграмму в формате Mermaid (flowchart или C4 упрощённый) включить в текст.

Результат запиши в файл: {OUT_PART2.relative_to(REPO_ROOT)}. Язык: русский."""
    ),
    PipelineStep(
        "3",
        "Детальный план задач",
        f"""Ты работаешь в репозитории Product Factory.

Задача: составить раздел «3. Детальный план задач» — так, чтобы осталось только реализовывать.

Прочитай: {SOURCES}, {OUT_PART1.relative_to(REPO_ROOT)}, {OUT_PART2.relative_to(REPO_ROOT)} (если есть).

Нужно:
- Взять за основу блоки и задачи из docs/roadmap-workstreams-and-variants.md (WS0–WS4, приоритеты P0–P1–P2).
- Разбить на конкретные подзадачи с ID, зависимостями (например «H1-Auth-1.1», «H1-Auth-1.2»), критериями приёмки и оценкой сложности (S/M/L).
- Порядок: сначала закрытие горизонта 1 (безопасность, контроль, наблюдаемость), затем подготовка горизонта 2.
- Таблица или список: ID | Задача | Зависимости | Критерии приёмки | Сложность. При необходимости сгруппировать по фазам (Горизонт 1 завершение, Горизонт 2 заделы).

Результат запиши в файл: {OUT_PART3.relative_to(REPO_ROOT)}. Язык: русский."""
    ),
    PipelineStep(
        "4",
        "Интеграция и эксплуатация",
        f"""Ты работаешь в репозитории Product Factory.

Задача: раздел «4. Интеграция и эксплуатация».

Прочитай: {SOURCES}, части 1–3 плана ({OUT_PART1.relative_to(REPO_ROOT)} – {OUT_PART3.relative_to(REPO_ROOT)}, если есть).

Нужно:
- Сборка и деплой: Docker, Docker Compose, опционально Kubernetes (k3s), GitOps (Argo CD). Окружения dev/staging/prod.
- CI/CD: GitHub Actions (или аналог), шаги (build, test, SBOM, sign, push), quality gates.
- Эксплуатация: краткий runbook (запуск, health, типичные проблемы, откат), мониторинг (Prometheus, Grafana, алерты), логи и аудит.
- Безопасность: секреты, AuthN/AuthZ в проде, fail-closed OPA.

Результат запиши в файл: {OUT_PART4.relative_to(REPO_ROOT)}. Язык: русский."""
    ),
    PipelineStep(
        "5",
        "Сборка итогового документа",
        f"""Ты работаешь в репозитории Product Factory.

Задача: собрать итоговый полный документ плана технологий и реализации.

Прочитай файлы: {OUT_PART1.relative_to(REPO_ROOT)}, {OUT_PART2.relative_to(REPO_ROOT)}, {OUT_PART3.relative_to(REPO_ROOT)}, {OUT_PART4.relative_to(REPO_ROOT)}.

Сделай:
1. Объедини содержимое в один документ с оглавлением в начале (якоря для разделов 1–4).
2. Добавь краткое введение (2–3 абзаца): цель документа, источники (architecture-and-path, roadmap-workstreams-and-variants, solution_design), для кого план.
3. Добавь раздел «5. Пока не определено»: перечень технологических направлений, для которых нет зрелых open-source решений — указать, что оставим на потом при появлении стабильных решений.
4. Проверь перекрёстные ссылки между разделами.
5. Результат запиши в файл: {OUT_FINAL.relative_to(REPO_ROOT)}. Язык: русский.
6. Удали временные файлы part1–part4: {OUT_PART1.relative_to(REPO_ROOT)} через {OUT_PART4.relative_to(REPO_ROOT)}."""
    ),
]


def main() -> int:
    runner = SprintRunner(
        name="planning-pipeline",
        steps=STEPS_BUILTIN,
        state_file=Path(__file__).resolve().parent / ".planning_pipeline_state.json",
        default_push=False,
        description="Planning Pipeline: продумывание и детализация плана технологий и задач",
    )
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
