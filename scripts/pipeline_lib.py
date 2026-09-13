"""
Общая библиотека для pipeline/sprint-скриптов Product Factory.

Единая точка для: запуска Codex, git-операций, retry с backoff,
сохранения/загрузки состояния, логирования, progress-отчётов, lock-файлов.

Использование:
    from pipeline_lib import SprintRunner, PipelineStep

    STEPS = [PipelineStep(id="1", name="...", prompt="..."), ...]
    runner = SprintRunner(name="alpha-sprint", steps=STEPS)
    sys.exit(runner.run())
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parent.parent
SCRIPTS_DIR = REPO_ROOT / "scripts"

_RE_TRY_SECONDS = re.compile(r"try\s+again\s+in\s+(\d+)\s*seconds?", re.I)
_RE_TRY_MINUTES = re.compile(r"try\s+again\s+in\s+(\d+)\s*minutes?", re.I)
_RE_TRY_HOURS = re.compile(r"try\s+again\s+in\s+(\d+)\s*hours?", re.I)
_RE_TRY_HM = re.compile(r"try\s+again\s+in\s+(\d+)\s*h(?:our)?s?\s*(\d+)\s*m(?:in)?", re.I)
_RE_RETRY_AFTER = re.compile(r"retry[_-]?after[:\s]+(\d+)", re.I)

DEFAULT_RETRY_AFTER_SEC = 30 * 60
MAX_RETRIES = 3
RETRY_BACKOFF_BASE = 60
CODEX_TIMEOUT_SEC = 600
LOCK_STALE_HOURS = 6

log = logging.getLogger("pipeline")


def setup_logging(level: int = logging.INFO, log_file: Optional[Path] = None) -> None:
    fmt = "%(asctime)s [%(levelname)s] %(message)s"
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if log_file:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(str(log_file), encoding="utf-8"))
    logging.basicConfig(level=level, format=fmt, handlers=handlers, force=True)


def now_iso() -> str:
    return datetime.now(tz=timezone.utc).isoformat()


def now_ts() -> int:
    return int(datetime.now(tz=timezone.utc).timestamp())


# ---------------------------------------------------------------------------
# Codex execution
# ---------------------------------------------------------------------------

def parse_retry_after_seconds(stderr: str) -> Optional[int]:
    """Извлекает задержку до сброса лимита из stderr Codex."""
    if not stderr:
        return None
    for pattern, multiplier in [
        (_RE_TRY_SECONDS, 1),
        (_RE_TRY_MINUTES, 60),
    ]:
        m = pattern.search(stderr)
        if m:
            return int(m.group(1)) * multiplier
    m = _RE_TRY_HM.search(stderr)
    if m:
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60
    m = _RE_TRY_HOURS.search(stderr)
    if m:
        return int(m.group(1)) * 3600
    m = _RE_RETRY_AFTER.search(stderr)
    if m:
        return int(m.group(1))
    return None


@dataclass
class CodexResult:
    exit_code: int
    stderr: str = ""
    timed_out: bool = False
    retry_after_sec: Optional[int] = None


def _find_codex() -> str:
    """Находит путь к codex CLI (поддержка .cmd на Windows)."""
    found = shutil.which("codex")
    if found:
        return found
    return "codex"


def run_codex(
    prompt: str,
    *,
    cwd: Path = REPO_ROOT,
    sandbox: str = "workspace-write",
    model: Optional[str] = None,
    timeout_sec: int = CODEX_TIMEOUT_SEC,
    dry_run: bool = False,
) -> CodexResult:
    """Запускает codex exec с таймаутом. Возвращает структурированный результат."""
    if dry_run:
        log.info("[DRY-RUN] codex exec --sandbox %s ...", sandbox)
        return CodexResult(exit_code=0)

    codex_bin = _find_codex()
    cmd = [codex_bin, "exec", "--cd", str(cwd), "--sandbox", sandbox]
    if model:
        cmd.extend(["--model", model])
    cmd.extend(["--", prompt])

    try:
        r = subprocess.run(
            cmd, cwd=cwd, text=True, capture_output=True, timeout=timeout_sec,
        )
        if r.stdout:
            print(r.stdout, end="")
        if r.stderr:
            print(r.stderr, end="", file=sys.stderr)
        retry_sec = parse_retry_after_seconds(r.stderr) if r.returncode != 0 else None
        return CodexResult(
            exit_code=r.returncode, stderr=r.stderr or "", retry_after_sec=retry_sec,
        )
    except subprocess.TimeoutExpired:
        log.warning("Codex timed out after %d sec", timeout_sec)
        return CodexResult(exit_code=-1, timed_out=True)
    except FileNotFoundError:
        log.error("Команда 'codex' не найдена в PATH (пробовал: %s)", codex_bin)
        return CodexResult(exit_code=127)


def run_codex_with_retry(
    prompt: str,
    *,
    cwd: Path = REPO_ROOT,
    sandbox: str = "workspace-write",
    model: Optional[str] = None,
    timeout_sec: int = CODEX_TIMEOUT_SEC,
    dry_run: bool = False,
    max_retries: int = MAX_RETRIES,
) -> CodexResult:
    """Запускает codex с retry и exponential backoff при rate-limit / transient errors."""
    for attempt in range(max_retries + 1):
        result = run_codex(
            prompt, cwd=cwd, sandbox=sandbox, model=model,
            timeout_sec=timeout_sec, dry_run=dry_run,
        )
        if result.exit_code == 0 or dry_run:
            return result
        if result.exit_code == 127:
            return result

        is_retriable = result.timed_out or result.retry_after_sec is not None
        if not is_retriable and attempt == 0:
            is_retriable = _looks_transient(result.stderr)

        if not is_retriable or attempt >= max_retries:
            return result

        wait = result.retry_after_sec or (RETRY_BACKOFF_BASE * (2 ** attempt))
        wait = min(wait, 3600)
        log.warning(
            "Попытка %d/%d не удалась (code=%d). Повтор через %d сек...",
            attempt + 1, max_retries + 1, result.exit_code, wait,
        )
        time.sleep(wait)
    return result  # type: ignore[possibly-undefined]


def _looks_transient(stderr: str) -> bool:
    transient = ["rate limit", "429", "503", "502", "timeout", "timed out", "ECONNRESET"]
    lower = stderr.lower()
    return any(t.lower() in lower for t in transient)


# ---------------------------------------------------------------------------
# Git operations
# ---------------------------------------------------------------------------

def has_changes(cwd: Path = REPO_ROOT) -> bool:
    r = subprocess.run(
        ["git", "status", "--short"], cwd=cwd, capture_output=True, text=True,
    )
    return r.returncode == 0 and bool(r.stdout.strip())


def git_commit(message: str, cwd: Path = REPO_ROOT) -> bool:
    if not has_changes(cwd):
        log.debug("Нечего коммитить")
        return True
    subprocess.run(["git", "add", "-A"], cwd=cwd, check=True)
    r = subprocess.run(["git", "commit", "-m", message], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        log.warning("git commit не выполнен: %s", r.stderr.strip())
        return False
    log.info("Коммит: %s", message)
    return True


def git_push(cwd: Path = REPO_ROOT) -> bool:
    r = subprocess.run(["git", "push"], cwd=cwd, capture_output=True, text=True)
    if r.returncode != 0:
        log.warning("git push не выполнен: %s", r.stderr.strip())
        return False
    log.info("Push выполнен")
    return True


# ---------------------------------------------------------------------------
# State management
# ---------------------------------------------------------------------------

@dataclass
class PipelineState:
    last_step_completed: int = 0
    last_run_ts: str = ""
    status: str = "pending"
    codex_retry_after_ts: Optional[int] = None
    failed_steps: list[str] = field(default_factory=list)

    @classmethod
    def load(cls, path: Path) -> "PipelineState":
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return cls(
                last_step_completed=int(data.get("last_step_completed", 0)),
                last_run_ts=data.get("last_run_ts", ""),
                status=data.get("status", "pending"),
                codex_retry_after_ts=data.get("codex_retry_after_ts"),
                failed_steps=data.get("failed_steps", []),
            )
        except (json.JSONDecodeError, OSError, TypeError):
            return cls()

    def save(self, path: Path) -> None:
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            data = {
                "last_step_completed": self.last_step_completed,
                "last_run_ts": now_iso(),
                "status": self.status,
                "codex_retry_after_ts": self.codex_retry_after_ts,
                "failed_steps": self.failed_steps,
            }
            path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        except OSError as e:
            log.warning("Не удалось записать state в %s: %s", path, e)

    def seconds_until_retry(self) -> Optional[float]:
        if self.codex_retry_after_ts is None:
            return None
        remaining = self.codex_retry_after_ts - now_ts()
        return max(0.0, float(remaining)) if remaining > 0 else None


# ---------------------------------------------------------------------------
# Lock
# ---------------------------------------------------------------------------

class PipelineLock:
    """Файловый lock с проверкой PID и stale timeout."""

    def __init__(self, path: Path, stale_hours: float = LOCK_STALE_HOURS):
        self.path = path
        self.stale_hours = stale_hours
        self._held = False

    def acquire(self) -> bool:
        if self.path.exists():
            if not self._is_stale():
                return False
            self._remove()
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(
                f"{os.getpid()}\n{now_iso()}", encoding="utf-8",
            )
            self._held = True
            return True
        except OSError:
            return False

    def release(self) -> None:
        if self._held:
            self._remove()
            self._held = False

    def _is_stale(self) -> bool:
        try:
            lines = self.path.read_text(encoding="utf-8").strip().splitlines()
            if len(lines) < 2:
                return True
            pid = int(lines[0])
            ts_str = lines[1]
            alive = _pid_alive(pid)
            lock_ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
            age_h = (datetime.now(timezone.utc) - lock_ts).total_seconds() / 3600
            return not alive or age_h >= self.stale_hours
        except (OSError, ValueError, TypeError):
            return True

    def _remove(self) -> None:
        try:
            self.path.unlink(missing_ok=True)
        except OSError:
            pass

    def __enter__(self) -> "PipelineLock":
        self.acquire()
        return self

    def __exit__(self, *args: object) -> None:
        self.release()


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


# ---------------------------------------------------------------------------
# Step definition
# ---------------------------------------------------------------------------

@dataclass
class PipelineStep:
    id: str
    name: str
    prompt: str


# ---------------------------------------------------------------------------
# SprintRunner — единый runner для всех sprint-скриптов
# ---------------------------------------------------------------------------

class SprintRunner:
    """
    Универсальный runner для sprint/pipeline скриптов.

    Обеспечивает: CLI args, retry, state persistence, resume,
    git commit/push, progress, logging.
    """

    def __init__(
        self,
        name: str,
        steps: list[PipelineStep],
        *,
        description: str = "",
        state_file: Optional[Path] = None,
        default_push: bool = False,
    ):
        self.name = name
        self.all_steps = steps
        self.description = description or f"Оркестр {name} (Codex CLI)"
        self._default_state_file = state_file
        self._default_push = default_push

    def build_parser(self) -> argparse.ArgumentParser:
        ap = argparse.ArgumentParser(description=self.description)
        ap.add_argument("--dry-run", action="store_true", help="Не вызывать codex, только вывести шаги")
        ap.add_argument("--step", type=str, help="Выполнить только шаг с указанным ID")
        ap.add_argument("--from-step", type=int, metavar="N", help="Выполнить шаги начиная с N")
        ap.add_argument("--to-step", type=int, metavar="N", help="Выполнить шаги до N включительно")
        ap.add_argument("--allow-docker", action="store_true", help="Sandbox danger-full-access")
        ap.add_argument("--no-write", action="store_true", help="Sandbox read-only")
        ap.add_argument("--no-commit", action="store_true", help="Не делать git commit")
        ap.add_argument("--no-push", action="store_true", help="Не делать git push после коммита")
        ap.add_argument("--push", action="store_true", help="Делать git push после каждого коммита")
        ap.add_argument("--model", "-m", metavar="MODEL", help="Модель codex")
        ap.add_argument("--timeout", type=int, default=CODEX_TIMEOUT_SEC, help="Таймаут codex в секундах")
        ap.add_argument("--max-retries", type=int, default=MAX_RETRIES, help="Макс. число повторов при ошибке")
        ap.add_argument(
            "--state-file", metavar="PATH",
            help="Файл состояния для resume (JSON). По умолчанию: scripts/.<name>_state.json",
        )
        ap.add_argument("--resume", action="store_true", help="Продолжить с последнего выполненного шага (из state)")
        ap.add_argument("--log-file", metavar="PATH", help="Файл лога")
        ap.add_argument("--verbose", "-v", action="store_true", help="DEBUG logging")
        return ap

    def run(self, argv: Optional[list[str]] = None) -> int:
        ap = self.build_parser()
        args = ap.parse_args(argv)

        setup_logging(
            level=logging.DEBUG if args.verbose else logging.INFO,
            log_file=Path(args.log_file) if args.log_file else None,
        )

        state_path = Path(args.state_file) if args.state_file else (
            self._default_state_file or SCRIPTS_DIR / f".{self.name.replace('-', '_')}_state.json"
        )

        sandbox = self._resolve_sandbox(args)
        do_commit = not args.no_commit and not args.dry_run
        do_push = do_commit and (args.push or (self._default_push and not args.no_push))

        steps = self._select_steps(args)
        if not steps:
            log.error("Нет шагов для выполнения")
            return 1

        if args.resume:
            state = PipelineState.load(state_path)
            wait = state.seconds_until_retry()
            if wait is not None and wait > 0:
                log.info("Ожидание сброса лимита: ~%d мин", int(wait // 60))
                time.sleep(min(wait, 6 * 3600))
            steps = [s for s in steps if int(s.id) > state.last_step_completed]
            if not steps:
                log.info("Все шаги уже выполнены (last=%d)", state.last_step_completed)
                return 0

        shutdown_requested = _setup_signal_handler()
        total = len(steps)
        completed = 0
        state = PipelineState.load(state_path) if state_path.exists() else PipelineState()

        log.info("=== %s: %d шагов ===", self.name, total)

        for i, step in enumerate(steps, 1):
            if shutdown_requested():
                log.warning("Получен сигнал завершения. Сохраняю состояние и выхожу.")
                state.status = "interrupted"
                state.save(state_path)
                return 130

            log.info("[%d/%d] Шаг %s: %s", i, total, step.id, step.name)

            result = run_codex_with_retry(
                step.prompt,
                cwd=REPO_ROOT,
                sandbox=sandbox,
                model=args.model,
                timeout_sec=args.timeout,
                dry_run=args.dry_run,
                max_retries=args.max_retries,
            )

            if result.exit_code != 0:
                log.error(
                    "Шаг %s завершился с ошибкой (code=%d, timed_out=%s)",
                    step.id, result.exit_code, result.timed_out,
                )
                state.status = "failed"
                if step.id not in state.failed_steps:
                    state.failed_steps.append(step.id)
                if result.retry_after_sec:
                    state.codex_retry_after_ts = now_ts() + result.retry_after_sec
                elif result.timed_out:
                    state.codex_retry_after_ts = now_ts() + DEFAULT_RETRY_AFTER_SEC
                state.save(state_path)
                return result.exit_code

            step_num = int(step.id)
            completed += 1
            if not args.dry_run:
                state.last_step_completed = step_num
                state.status = "completed"
                state.codex_retry_after_ts = None
                state.save(state_path)

            changed = has_changes(REPO_ROOT)
            if do_commit and changed:
                msg = f"{self.name}: шаг {step.id} — {step.name}"
                git_commit(msg, REPO_ROOT)
                if do_push:
                    git_push(REPO_ROOT)
            elif not changed:
                log.debug("Шаг %s: нет изменений для коммита", step.id)

        log.info("=== %s: завершено %d/%d шагов ===", self.name, completed, total)
        return 0

    def _resolve_sandbox(self, args: argparse.Namespace) -> str:
        if args.allow_docker:
            return "danger-full-access"
        if args.no_write:
            return "read-only"
        return "workspace-write"

    def _select_steps(self, args: argparse.Namespace) -> list[PipelineStep]:
        if args.step is not None:
            return [s for s in self.all_steps if s.id == args.step]

        from_i = args.from_step
        to_i = args.to_step if args.to_step is not None else len(self.all_steps)

        if from_i is not None:
            return [s for s in self.all_steps if from_i <= int(s.id) <= to_i]

        return list(self.all_steps)


# ---------------------------------------------------------------------------
# Signal handling for graceful shutdown
# ---------------------------------------------------------------------------

def _setup_signal_handler() -> callable:
    """Настраивает обработчик SIGINT/SIGTERM. Возвращает функцию-флаг."""
    requested = [False]

    def handler(signum: int, frame: object) -> None:
        if requested[0]:
            log.warning("Повторный сигнал — принудительный выход")
            sys.exit(1)
        requested[0] = True
        log.info("Получен сигнал %d, завершение после текущего шага...", signum)

    signal.signal(signal.SIGINT, handler)
    if hasattr(signal, "SIGTERM"):
        signal.signal(signal.SIGTERM, handler)

    return lambda: requested[0]


# ---------------------------------------------------------------------------
# Utilities for grand pipeline (steps from file)
# ---------------------------------------------------------------------------

def load_steps_from_file(
    path: Path,
    fallback: list[PipelineStep],
) -> list[PipelineStep]:
    """Загружает шаги из JSON-файла; при ошибке возвращает fallback."""
    if not path.exists():
        return fallback
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, list) and len(data) > 0:
            if all("id" in s and "name" in s and "prompt" in s for s in data):
                return [PipelineStep(**s) for s in data]
    except (json.JSONDecodeError, OSError, TypeError):
        pass
    return fallback
