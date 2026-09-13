#!/usr/bin/env python3
"""
Планировщик Grand Pipeline: каждые ~5 часов запускает pipeline или maintenance.

- Каждые 5h + случайные минуты (0–30): попытка запуска.
- Если есть незавершённый pipeline (state): resume с последнего шага.
- Если pipeline завершён (все шаги): мозговой штурм → новый pipeline.
- Lock-файл: не запускать второй экземпляр.
- Maintenance в каждом цикле: аудит, идеи, рефакторинг.

Запуск: python3 scripts/scheduled_pipeline_runner.py [--interval-hours 5] [--allow-docker] [--once]
  --once   один цикл и выход.
"""
from __future__ import annotations

import argparse
import json
import random
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from pipeline_lib import (
    REPO_ROOT,
    SCRIPTS_DIR,
    PipelineLock,
    PipelineState,
    log,
    now_iso,
    run_codex,
    setup_logging,
    _setup_signal_handler,
)

DOCS_DIR = REPO_ROOT / "docs"
STATE_FILE = SCRIPTS_DIR / ".grand_pipeline_state.json"
STEPS_FILE = SCRIPTS_DIR / ".grand_pipeline_steps.json"
LOCK_FILE = SCRIPTS_DIR / ".pipeline_run.lock"
IDEAS_BACKLOG = DOCS_DIR / "ideas-backlog.md"

INTERVAL_HOURS = 5
INTERVAL_JITTER_MIN = 30
LAST_STEP_BUILTIN = 38
MAINTENANCE_TIMEOUT_SEC = 180


def get_last_step_number() -> int:
    if not STEPS_FILE.exists():
        return LAST_STEP_BUILTIN
    try:
        data = json.loads(STEPS_FILE.read_text(encoding="utf-8"))
        if isinstance(data, list) and len(data) > 0:
            return len(data)
    except (json.JSONDecodeError, OSError):
        pass
    return LAST_STEP_BUILTIN


def run_pipeline(from_step: int, to_step: int, allow_docker: bool, model: str | None = None) -> int:
    cmd = [
        sys.executable,
        str(SCRIPTS_DIR / "run_grand_pipeline.py"),
        "--from-step", str(from_step),
        "--to-step", str(to_step),
        "--state-file", str(STATE_FILE),
    ]
    if allow_docker:
        cmd.append("--allow-docker")
    if model:
        cmd.extend(["--model", model])
    return subprocess.run(cmd, cwd=REPO_ROOT).returncode


def run_brainstorm_phase(allow_docker: bool, model: str | None = None) -> int:
    cmd = [sys.executable, str(SCRIPTS_DIR / "run_brainstorm.py"), "--reset-state"]
    if allow_docker:
        cmd.append("--allow-docker")
    if model:
        cmd.extend(["--model", model])
    return subprocess.run(cmd, cwd=REPO_ROOT).returncode


def run_maintenance(allow_docker: bool, dry_run: bool = False) -> None:
    """Аудит, идеи, рефакторинг — в каждом цикле что-то делается."""
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    audit_path = DOCS_DIR / f"audit-scheduled-{date}.md"
    sandbox = "danger-full-access" if allow_docker else "workspace-write"

    if dry_run:
        log.info("[maintenance] DRY-RUN: пропускаю")
        return

    _run_maintenance_step(
        prompt=(
            "В репозитории Product Factory (Intent-to-Reality Engine) напиши краткий аудит на одну страницу: "
            "что сделано по grand-pipeline и docs, что в бэклоге, основные риски. "
            f"Сохрани результат в файл {audit_path.relative_to(REPO_ROOT)}. Формат: markdown, заголовки, списки."
        ),
        sandbox=sandbox,
        fallback_action=lambda: _write_stub_audit(audit_path, date),
        label="audit",
    )

    if not IDEAS_BACKLOG.exists():
        IDEAS_BACKLOG.write_text(
            "# Backlog идей развития\n\n"
            "Идеи из планировщика (мозговой штурм, улучшения, рефакторинг).\n\n---\n\n",
            encoding="utf-8",
        )

    _run_maintenance_step(
        prompt=(
            "Добавь 1–3 конкретные идеи развития Product Factory в конец файла "
            f"{IDEAS_BACKLOG.relative_to(REPO_ROOT)}. Каждая идея — одна строка или короткий абзац. "
            "Формат: маркер списка и текст. Не дублируй уже существующие пункты."
        ),
        sandbox=sandbox,
        fallback_action=lambda: _append_stub_idea(),
        label="ideas",
    )

    _run_maintenance_step(
        prompt=(
            "В репозитории Product Factory выполни одну небольшую задачу по рефакторингу или улучшению: "
            "выбери пункт из docs/ideas-backlog.md, или техдолг (качество кода, тесты, документация), или мелкое исправление. "
            "Сделай изменение в коде, тестах или документации. Закоммить с сообщением вида 'maintenance: краткое описание'. "
            "Одна задача за раз, без больших изменений."
        ),
        sandbox=sandbox,
        fallback_action=None,
        label="refactor",
        timeout_sec=300,
    )


def _run_maintenance_step(
    prompt: str,
    sandbox: str,
    fallback_action: object | None,
    label: str,
    timeout_sec: int = MAINTENANCE_TIMEOUT_SEC,
) -> None:
    log.info("[maintenance:%s] запуск...", label)
    result = run_codex(prompt, sandbox=sandbox, timeout_sec=timeout_sec)
    if result.exit_code != 0:
        log.warning("[maintenance:%s] codex вернул код %d", label, result.exit_code)
        if callable(fallback_action):
            fallback_action()


def _write_stub_audit(path: Path, date: str) -> None:
    if not path.exists():
        path.write_text(
            f"# Scheduled audit {date}\n\n"
            f"*Автогенерация не выполнена (codex недоступен). Добавь содержание вручную.*\n\n"
            f"См. grand-pipeline-tasks.md, proper-implementation-plan.md, risk-register.md.\n",
            encoding="utf-8",
        )


def _append_stub_idea() -> None:
    with open(IDEAS_BACKLOG, "a", encoding="utf-8") as f:
        f.write(f"\n- [{now_iso()}] (placeholder) Идея: расширить runbook по SLO; проверить покрытие тестов.\n")


def sleep_with_check(sec: float, shutdown_flag: callable) -> bool:
    """Спит sec секунд, но проверяет флаг завершения каждые 30 сек. Возвращает True если shutdown."""
    elapsed = 0.0
    chunk = 30.0
    while elapsed < sec:
        if shutdown_flag():
            return True
        time.sleep(min(chunk, sec - elapsed))
        elapsed += chunk
    return shutdown_flag()


def main() -> int:
    ap = argparse.ArgumentParser(description="Планировщик Grand Pipeline (каждые ~5h)")
    ap.add_argument("--interval-hours", type=float, default=INTERVAL_HOURS, help="Интервал в часах")
    ap.add_argument("--jitter-min", type=int, default=INTERVAL_JITTER_MIN, help="Случайные минуты к интервалу")
    ap.add_argument("--allow-docker", action="store_true", help="Передать --allow-docker")
    ap.add_argument("--once", action="store_true", help="Один цикл и выход")
    ap.add_argument("--maintenance-only", action="store_true", help="Только maintenance")
    ap.add_argument("--model", "-m", metavar="MODEL", help="Модель codex")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args()

    import logging
    setup_logging(level=logging.DEBUG if args.verbose else logging.INFO)

    shutdown_flag = _setup_signal_handler()
    lock = PipelineLock(LOCK_FILE)

    while True:
        if shutdown_flag():
            log.info("Получен сигнал завершения, выхожу")
            break

        log.info("--- Цикл планировщика [%s] ---", now_iso())

        state = PipelineState.load(STATE_FILE)
        wait = state.seconds_until_retry()
        if wait is not None and wait > 0:
            wait_min = int(wait // 60)
            log.info("Ожидание сброса лимита Codex: ~%d мин", wait_min)
            if sleep_with_check(min(wait, 6 * 3600), shutdown_flag):
                break
            continue

        if lock.acquire():
            try:
                last = state.last_step_completed
                last_step_num = get_last_step_number()

                if not args.maintenance_only and last >= last_step_num:
                    log.info("Pipeline завершён (шаг %d). Запуск мозгового штурма.", last)
                    code = run_brainstorm_phase(args.allow_docker, args.model)
                    if code == 0:
                        log.info("Мозговой штурм завершён. Новый pipeline готов. Запуск сразу.")
                        new_last_step_num = get_last_step_number()
                        log.info("Запуск нового pipeline: шаги 1..%d", new_last_step_num)
                        code = run_pipeline(1, new_last_step_num, args.allow_docker, args.model)
                        log.info("Новый pipeline завершён с кодом %d", code)
                    else:
                        log.warning("Мозговой штурм завершился с кодом %d", code)

                elif not args.maintenance_only:
                    from_step = last + 1
                    if from_step <= last_step_num:
                        log.info("Запуск pipeline: шаги %d..%d", from_step, last_step_num)
                        code = run_pipeline(from_step, last_step_num, args.allow_docker, args.model)
                        log.info("Pipeline завершён с кодом %d", code)
            finally:
                lock.release()

            run_maintenance(args.allow_docker)
        else:
            log.info("Lock занят. Выполняю только maintenance.")
            run_maintenance(args.allow_docker)

        if args.once:
            log.info("Режим --once: выход")
            return 0

        interval_sec = args.interval_hours * 3600 + random.randint(0, args.jitter_min * 60)
        log.info("Следующий запуск через %.1f ч", interval_sec / 3600)
        if sleep_with_check(interval_sec, shutdown_flag):
            break

    return 0


if __name__ == "__main__":
    sys.exit(main())
