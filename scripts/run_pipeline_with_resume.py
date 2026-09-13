#!/usr/bin/env python3
"""
Запуск пайплайна с автоматическим возобновлением при временном исчерпании лимита Codex.

Какой пайплайн запускать:
  --pipeline completion  (по умолчанию) — 16 шагов завершения (completion-pipeline)
  --pipeline planning    — 5 шагов продумывания плана технологий и задач (technology-and-implementation-plan)
  --pipeline development — реализация задач из раздела 3 плана (technology-and-implementation-plan)

При ошибке из-за rate limit / квоты скрипт ждёт и перезапускает выбранный пайплайн с --resume.

Запуск:
  python3 scripts/run_pipeline_with_resume.py --pipeline planning --allow-docker   # планирование
  python3 scripts/run_pipeline_with_resume.py --allow-docker                       # completion (по умолчанию)

Опции ожидания:
  --wait-min N     при лимите ждать N минут
  --max-resume N   макс. число возобновлений (по умолчанию 24)
  --no-wait        не ждать при лимите, выйти с подсказкой
"""
from __future__ import annotations

import json
import logging
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"

PIPELINES = {
    "completion": ("run_completion_pipeline.py", SCRIPTS_DIR / ".completion_pipeline_state.json"),
    "planning": ("run_planning_pipeline.py", SCRIPTS_DIR / ".planning_pipeline_state.json"),
    "development": ("run_development_pipeline.py", SCRIPTS_DIR / ".development_pipeline_state.json"),
}

log = logging.getLogger("pipeline-with-resume")


def setup_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        handlers=[logging.StreamHandler(sys.stdout)],
        force=True,
    )


def load_retry_after_ts(state_file: Path) -> int | None:
    """Читает codex_retry_after_ts из state-файла."""
    if not state_file.exists():
        return None
    try:
        data = json.loads(state_file.read_text(encoding="utf-8"))
        val = data.get("codex_retry_after_ts")
        return int(val) if val is not None else None
    except (OSError, ValueError, TypeError):
        return None


def now_ts() -> int:
    from datetime import datetime, timezone
    return int(datetime.now(tz=timezone.utc).timestamp())


def wait_until_retry(retry_after_ts: int, wait_min: int | None) -> None:
    """Ждёт до retry_after_ts или wait_min минут (что меньше)."""
    now = now_ts()
    remaining_sec = max(0, retry_after_ts - now)
    if wait_min is not None:
        cap_sec = wait_min * 60
        remaining_sec = min(remaining_sec, cap_sec)
    if remaining_sec <= 0:
        return
    mins = int(remaining_sec // 60)
    log.info(
        "Исчерпан лимит Codex. Ожидание сброса лимита: ~%d мин (до повторного запуска)",
        mins,
    )
    time.sleep(remaining_sec)


def run_pipeline(script_name: str, argv: list[str], with_resume: bool) -> int:
    """Запускает выбранный пайплайн; при with_resume добавляет --resume."""
    cmd = [sys.executable, str(SCRIPTS_DIR / script_name)]
    if with_resume:
        cmd.append("--resume")
    cmd.extend(argv)
    proc = subprocess.run(cmd, cwd=REPO_ROOT)
    return proc.returncode


def main() -> int:
    setup_logging()

    argv = list(sys.argv[1:])
    wait_min: int | None = None
    max_resume = 24
    no_wait = False
    pipeline = "completion"

    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--pipeline" and i + 1 < len(argv):
            pipeline = argv[i + 1].lower()
            if pipeline not in PIPELINES:
                log.error("--pipeline должен быть: %s. Получено: %s", ", ".join(PIPELINES), pipeline)
                return 2
            argv.pop(i)
            argv.pop(i)
            continue
        if a == "--wait-min" and i + 1 < len(argv):
            wait_min = int(argv[i + 1])
            argv.pop(i)
            argv.pop(i)
            continue
        if a == "--max-resume" and i + 1 < len(argv):
            max_resume = int(argv[i + 1])
            argv.pop(i)
            argv.pop(i)
            continue
        if a == "--no-wait":
            no_wait = True
            argv.pop(i)
            continue
        i += 1

    script_name, state_file = PIPELINES[pipeline]
    log.info("Пайплайн: %s (%s)", pipeline, script_name)

    resume_count = 0
    with_resume = False

    while True:
        code = run_pipeline(script_name, argv, with_resume=with_resume)

        if code == 0:
            log.info("Пайплайн завершён успешно.")
            return 0

        retry_after_ts = load_retry_after_ts(state_file)
        need_wait = retry_after_ts is not None and retry_after_ts > now_ts()
        can_retry = (
            (need_wait and resume_count < max_resume)
            or (retry_after_ts is not None and not need_wait and resume_count == 0 and not no_wait)
        )

        if no_wait or not can_retry:
            if retry_after_ts is not None and need_wait and resume_count >= max_resume:
                log.warning(
                    "Достигнут лимит возобновлений (%d). Запустите вручную с --resume после сброса лимита.",
                    max_resume,
                )
            elif retry_after_ts is not None and need_wait and no_wait:
                log.warning(
                    "Лимит Codex. Для возобновления запустите:\n  python3 scripts/%s --resume %s",
                    script_name,
                    " ".join(argv),
                )
            return code

        resume_count += 1
        if need_wait:
            wait_until_retry(retry_after_ts, wait_min)
        with_resume = True
        log.info("Возобновление пайплайна (попытка %d)...", resume_count)


if __name__ == "__main__":
    sys.exit(main())
