#!/usr/bin/env python3
"""
Development Pipeline: автоматизированная реализация задач из плана технологий и реализации.

Читает раздел «3. Детальный план задач» из docs/technology-and-implementation-plan.md,
превращает каждую задачу (ID, описание, критерии приёмки) в шаг пайплайна и выполняет через Codex.

Запуск:
  python3 scripts/run_development_pipeline.py [--dry-run] [--allow-docker] [--step N] [--from-step N] [--to-step N]
  python3 scripts/run_development_pipeline.py --reload-plan   # пересобрать шаги из плана
  python3 scripts/run_pipeline_with_resume.py --pipeline development --allow-docker
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from pipeline_lib import (
    SCRIPTS_DIR,
    PipelineStep,
    SprintRunner,
    load_steps_from_file,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
DOCS = REPO_ROOT / "docs"
PLAN_FILE = DOCS / "technology-and-implementation-plan.md"
STEPS_FILE = SCRIPTS_DIR / ".development_pipeline_steps.json"
STATE_FILE = SCRIPTS_DIR / ".development_pipeline_state.json"

# Контекст для промптов
REPO_CONTEXT = (
    "Ты работаешь в репозитории Product Factory (корень репо — текущая директория). "
    "Соблюдай AGENTS.md, docs/adr/0002-layer-boundaries.md, docs/prohibited-agent-actions.md. "
    "Язык кода и коммитов: по контексту (Kotlin/конфиги — как в проекте); комментарии и доки — русский где уместно."
)


def _parse_task_table(lines: list[str]) -> list[dict]:
    """Извлекает строки задач из markdown-таблицы (формат: | ID | Задача | Зависимости | Критерии приёмки | Сложность |)."""
    tasks = []
    for line in lines:
        line = line.strip()
        if not line.startswith("|") or not line.endswith("|"):
            continue
        parts = [p.strip() for p in line.split("|") if p.strip() != ""]
        if len(parts) < 5:
            continue
        task_id, task_desc, deps, criteria, complexity = parts[0], parts[1], parts[2], parts[3], parts[4]
        if task_id.upper() == "ID" or not re.match(r"^[H0-9][A-Za-z0-9.-]+$", task_id):
            continue
        tasks.append({
            "id": task_id,
            "task": task_desc,
            "dependencies": deps,
            "criteria": criteria,
            "complexity": complexity,
        })
    return tasks


def extract_tasks_from_plan(plan_path: Path) -> list[dict]:
    """Читает docs/technology-and-implementation-plan.md и возвращает список задач из раздела 3."""
    text = plan_path.read_text(encoding="utf-8")
    lines = text.splitlines()

    # Найти начало раздела 3 (Детальный план задач)
    start = None
    for i, line in enumerate(lines):
        if re.search(r"#\s+3\.\s+Детальный план задач", line) or (
            "<a id=\"section-3\"></a>" in line and i + 1 < len(lines) and "3." in lines[i + 1]
        ):
            start = i
            break
    if start is None:
        for i, line in enumerate(lines):
            if "Детальный план задач" in line and "3." in line:
                start = i
                break
    if start is None:
        return []

    # Собрать все строки таблиц до раздела 4
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if re.search(r"^#\s+4\.", lines[i]) or "Definition of Done" in lines[i]:
            end = i
            break

    table_lines = [lines[i] for i in range(start, end) if lines[i].strip().startswith("|") and lines[i].count("|") >= 5]
    return _parse_task_table(table_lines)


def tasks_to_steps(tasks: list[dict]) -> list[PipelineStep]:
    """Преобразует список задач в PipelineStep с числовыми id для resume/--from-step."""
    steps = []
    for idx, t in enumerate(tasks, 1):
        task_id = t["id"]
        task_desc = t["task"]
        deps = t.get("dependencies", "")
        criteria = t.get("criteria", "")
        complexity = t.get("complexity", "")
        prompt = (
            f"{REPO_CONTEXT}\n\n"
            f"Задача (из плана технологий и реализации, раздел 3): **{task_id}**\n\n"
            f"**Что сделать:** {task_desc}\n\n"
            f"**Зависимости (уже должны быть выполнены):** {deps}\n\n"
            f"**Критерии приёмки (обязательно выполнить):** {criteria}\n\n"
            f"(Сложность по плану: {complexity})\n\n"
            "Реализуй задачу: код, конфиги, тесты и/или документы по необходимости. "
            "Не меняй несвязанные файлы. После изменений проверь сборку/тесты по AGENTS.md (Docker или локально)."
        )
        name = f"{task_id}: {task_desc[:60]}{'…' if len(task_desc) > 60 else ''}"
        steps.append(PipelineStep(id=str(idx), name=name, prompt=prompt))
    return steps


def build_steps_from_plan(plan_path: Path) -> list[PipelineStep]:
    """Строит список шагов из файла плана и сохраняет в .development_pipeline_steps.json."""
    tasks = extract_tasks_from_plan(plan_path)
    if not tasks:
        return []
    steps = tasks_to_steps(tasks)
    # Сохранить для последующих запусков и ручного редактирования
    out = [{"id": s.id, "name": s.name, "prompt": s.prompt} for s in steps]
    STEPS_FILE.parent.mkdir(parents=True, exist_ok=True)
    STEPS_FILE.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    return steps


def get_builtin_steps() -> list[PipelineStep]:
    """Минимальный fallback: несколько первых задач из плана вручную (если план недоступен)."""
    return [
        PipelineStep(
            "1",
            "H1-Auth-1.1: Профиль AuthN для API",
            f"{REPO_CONTEXT}\n\nРеализуй задачу H1-Auth-1.1 из docs/technology-and-implementation-plan.md (раздел 3): "
            "выбрать и зафиксировать профиль AuthN для API (JWT/OIDC), описать mapping claims -> tenantId, subject, roles. "
            "Критерии: в runbook и config-доках зафиксированы issuer/audience/claims; есть таблица mapping claim->runtime поля.",
        ),
    ]


def main() -> int:
    argv = list(sys.argv[1:])
    reload_plan = "--reload-plan" in argv
    if reload_plan:
        argv.remove("--reload-plan")

    if reload_plan or not STEPS_FILE.exists():
        if not PLAN_FILE.exists():
            print(f"Файл плана не найден: {PLAN_FILE}", file=sys.stderr)
            steps = get_builtin_steps()
        else:
            steps = build_steps_from_plan(PLAN_FILE)
            if not steps:
                print("Не удалось извлечь задачи из плана, используем встроенный fallback.", file=sys.stderr)
                steps = get_builtin_steps()
    else:
        steps = load_steps_from_file(STEPS_FILE, get_builtin_steps())

    runner = SprintRunner(
        name="development-pipeline",
        steps=steps,
        state_file=STATE_FILE,
        default_push=True,
        description="Development Pipeline: реализация задач из technology-and-implementation-plan.md",
    )
    return runner.run(argv)


if __name__ == "__main__":
    sys.exit(main())
