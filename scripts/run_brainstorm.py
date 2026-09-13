#!/usr/bin/env python3
"""
Мозговой штурм: агент работает с документацией, составляет новый pipeline (JSON).
Результат — scripts/.grand_pipeline_steps.json и docs (ideas-backlog, next-pipeline-plan).
Вызывается планировщиком после завершения текущего pipeline.

При неудаче codex (файл не создан) — fallback-генерация pipeline из бэклога идей.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from pipeline_lib import (
    REPO_ROOT,
    SCRIPTS_DIR,
    CodexResult,
    PipelineState,
    log,
    run_codex_with_retry,
    setup_logging,
)

STEPS_FILE = SCRIPTS_DIR / ".grand_pipeline_steps.json"
STATE_FILE = SCRIPTS_DIR / ".grand_pipeline_state.json"
DOCS_DIR = REPO_ROOT / "docs"

BRAINSTORM_PROMPT = """ВАЖНО: ты работаешь в режиме exec (неинтерактивно). НЕ ЗАДАВАЙ вопросов. НЕ НАЧИНАЙ диалог. Сразу выполняй действия с файлами.

Задача: сгенерировать следующий pipeline для Product Factory (Intent-to-Reality Engine).
Текущий grand pipeline из 38 шагов полностью завершён. Нужен новый цикл работ.

ОБЯЗАТЕЛЬНЫЕ ДЕЙСТВИЯ (выполни все по порядку):

ШАГ 1: Прочитай файлы:
- docs/ideas-backlog.md
- docs/roadmap-checklist.md
- docs/risk-register.md
- docs/implementation-assessment.md
- последний docs/audit-scheduled-*.md

ШАГ 2: Добавь 2-5 новых идей в конец файла docs/ideas-backlog.md (маркер списка - и текст).

ШАГ 3: Создай файл docs/next-pipeline-plan-{date}.md с кратким планом (1-2 страницы markdown).

ШАГ 4 (САМЫЙ ВАЖНЫЙ): Создай файл scripts/.grand_pipeline_steps.json.
Содержимое — ТОЛЬКО валидный JSON-массив (без markdown, без комментариев). Пример:
[
  {{"id": "1", "name": "Название", "prompt": "В Product Factory сделай ..."}},
  {{"id": "2", "name": "Название", "prompt": "В Product Factory сделай ..."}}
]
Требования:
- 20-35 шагов
- Каждый шаг: "id" (строка "1","2",...), "name" (короткое название), "prompt" (полный промпт для агента)
- Промпт каждого шага должен быть конкретным и выполнимым: какой файл создать/изменить, что проверить
- Приоритеты: незакрытые пункты из ideas-backlog, техдолг, новые фичи, тесты, документация
- НЕ дублируй буквально старые 38 шагов

Путь к файлу шагов: scripts/.grand_pipeline_steps.json
Запиши ТОЛЬКО JSON, БЕЗ markdown-обёртки, БЕЗ ```json``` блоков."""

FALLBACK_STEPS = [
    {"id": "1", "name": "Golden scenarios для archetype-потоков",
     "prompt": "В Product Factory добавь golden scenarios (эталонные тесты) для ключевых archetype-потоков: (1) валидный прогон catalog-service, (2) валидный прогон web-app, (3) отказ policy, (4) rollback. Размести в eval/golden/ или tests/golden/. Каждый сценарий — JSON с input и expected output. Добавь скрипт или тест для прогона. Документируй в docs/golden-scenarios.md."},
    {"id": "2", "name": "LLM fallback matrix (multi-provider)",
     "prompt": "В Product Factory реализуй LLM fallback matrix: конфиг приоритетов и таймаутов для нескольких OpenAI-совместимых провайдеров (primary/secondary). При деградации NEURAL_SERVICE_URL автоматически переключаться на secondary. Конфиг через ENV или YAML. Обнови HttpNeuralServiceClient. Документируй в docs/neural-service-api.md."},
    {"id": "3", "name": "Contract Drift Guard",
     "prompt": "В Product Factory добавь Contract Drift Guard: автоматическое сравнение входных YAML с contracts/schemas/*.schema.json, генерация отчёта о несовместимостях. Реализуй как CLI команду или CI шаг. Документируй в docs/contract-drift-guard.md."},
    {"id": "4", "name": "Policy simulation dashboard (документ)",
     "prompt": "В Product Factory создай docs/policy-simulation-dashboard.md: сводку по срабатываниям approvals/блокировок по risk-tier и типам действий Tool Executor. Добавь API endpoint GET /factory/policy-stats или скрипт для анализа audit log."},
    {"id": "5", "name": "Версионирование output-артефактов",
     "prompt": "В Product Factory реализуй версионирование output-артефактов: manifest + provenance + checksum. При каждом run записывать в artifact_manifest поля version, checksum (SHA-256 от содержимого), provenance (git commit, timestamp). Проверка воспроизводимости: один и тот же набор YAML → тот же checksum. Документируй."},
    {"id": "6", "name": "SLO gate перед выпуском артефакта",
     "prompt": "В Product Factory реализуй SLO gate перед выпуском артефакта: проверка целевых метрик (время генерации, доля policy-deny, процент успешных прогонов) с блокировкой релиза при выходе за пороги quality_profile. Интеграция с CI или как пост-шаг в workflow. Документируй."},
    {"id": "7", "name": "Reference products (эталонные наборы)",
     "prompt": "В Product Factory создай каталог reference products: эталонные наборы 5 YAML + ожидаемые outputs. Размести в eval/reference-products/. Добавь скрипт для сравнения текущего вывода с эталонным. Используй для регрессионного тестирования стратегий Planner."},
    {"id": "8", "name": "Dry-run режим для Tool Executor",
     "prompt": "В Product Factory добавь dry-run режим для Tool Executor: детальный план side-effects (что было бы выполнено, параметры, policy result) без реального выполнения. API: POST /factory/run с полем dry_run:true. Запись плана в audit. Документируй в runbook."},
    {"id": "9", "name": "Автогенерация ADR из спринта",
     "prompt": "В Product Factory добавь автогенерацию ADR-черновика из результатов спринта: контекст, решения, альтернативы, риски. Реализуй как пост-шаг в workflow или отдельную команду. Привязка к risk-register и approvals. Шаблон в docs/adr/template.md."},
    {"id": "10", "name": "Улучшить IntentClarification: контекстные вопросы",
     "prompt": "В Product Factory улучши IntentClarification: вопросы должны зависеть от домена (web-app vs API vs data pipeline). Добавь domain detection по goal и constraints. Разные наборы вопросов для разных доменов. Тесты для каждого домена."},
    {"id": "11", "name": "Метрики качества кодогена",
     "prompt": "В Product Factory добавь метрики качества кодогена: (1) процент успешно применённых патчей, (2) количество конфликтов, (3) размер патча. Записывать в audit при codegen_patch_set. Экспорт в /metrics. Документируй в slo-cost-draft.md."},
    {"id": "12", "name": "Webhook notifications при завершении run",
     "prompt": "В Product Factory добавь webhook notifications: при завершении run (DONE/FAILED) отправлять POST на настроенный URL с payload (runId, status, duration, artifacts). Конфиг через ENV WEBHOOK_URL. Документируй в runbook."},
    {"id": "13", "name": "Batch run API",
     "prompt": "В Product Factory добавь batch run API: POST /factory/batch-run принимает массив запросов, выполняет последовательно или параллельно (по конфигу), возвращает массив результатов. Полезно для регрессии и массовой генерации. Тесты и документация."},
    {"id": "14", "name": "Кэширование планов для повторяющихся goal",
     "prompt": "В Product Factory добавь кэширование планов Planner: если goal+constraints совпадают с предыдущим run, предложить использовать кэшированный план (с confirmation). Хранение: файл или in-memory с TTL. Документируй."},
    {"id": "15", "name": "Расширить архетипы: data-pipeline",
     "prompt": "В Product Factory добавь третий архетип data-pipeline: минимальный ETL/batch processing на Kotlin. Структура: build.gradle.kts, src/, Dockerfile, тесты. CI workflow по аналогии с catalog-service и web-app. Обнови docs/archetypes-and-roadmap.md."},
    {"id": "16", "name": "Улучшить observability: structured logging",
     "prompt": "В Product Factory замени текстовые логи на structured JSON logging (logback-encoder или аналог). Поля: timestamp, level, runId, stepId, eventType. Обнови deploy/docker-compose.yml при необходимости. Документируй в observability-and-logs.md."},
    {"id": "17", "name": "API versioning (v1 prefix)",
     "prompt": "В Product Factory добавь версионирование API: все эндпоинты доступны по /v1/factory/run, /v1/intent/estimate и т.д. Старые пути (/factory/run) остаются как alias. Документируй миграцию в runbook."},
    {"id": "18", "name": "Тесты для multi-tenant изоляции",
     "prompt": "В Product Factory расширь тесты multi-tenant: (1) run одного tenant не виден другому, (2) approvals изолированы, (3) audit фильтруется по tenant, (4) profile изолирован. Размести в src/test/kotlin/productfactory/api/. Починь TenantIsolationApiTest если падает."},
    {"id": "19", "name": "Документация: полный API reference",
     "prompt": "В Product Factory создай docs/api-reference.md: полный справочник всех HTTP эндпоинтов с request/response форматами, кодами ответов, примерами curl. Включить: factory/run, approvals, answer, intent, experience, profiles, metrics, health."},
    {"id": "20", "name": "CI: параллельные jobs для ускорения",
     "prompt": "В Product Factory оптимизируй CI: разделить сборку, тесты архетипов и supply-chain gate на параллельные jobs с зависимостями. Цель: сократить общее время CI. Обнови .github/workflows/ci.yml."},
    {"id": "21", "name": "Синхронизация roadmap и implementation-assessment",
     "prompt": "В Product Factory приведи в соответствие docs/roadmap-checklist.md, docs/phases-task-list.md и docs/implementation-assessment.md с текущим состоянием после завершения grand pipeline (38 шагов). Отметь выполненные пункты. Обнови раздел 'Чего нет'."},
    {"id": "22", "name": "Security: rate limiting для API",
     "prompt": "В Product Factory добавь rate limiting для API: ограничение числа запросов по IP или tenantId за период. Реализуй как Ktor plugin или middleware. Конфиг через ENV. Документируй в runbook."},
    {"id": "23", "name": "Runbook: troubleshooting guide",
     "prompt": "В Product Factory добавь в docs/runbook.md раздел Troubleshooting: типичные проблемы (codex timeout, OPA недоступен, Docker build fail, тесты падают) с пошаговыми решениями."},
    {"id": "24", "name": "Финальная синхронизация документации",
     "prompt": "В Product Factory проведи финальную синхронизацию всей документации: проверь что все ссылки между docs/ файлами работают, нет устаревших секций, README актуален. Обнови docs/implementation-assessment.md с итогами."},
]


def validate_steps_file() -> bool:
    if not STEPS_FILE.exists():
        return False
    try:
        data = json.loads(STEPS_FILE.read_text(encoding="utf-8"))
        if not isinstance(data, list) or len(data) == 0:
            return False
        return all(
            isinstance(s, dict) and "id" in s and "name" in s and "prompt" in s
            for s in data
        )
    except (json.JSONDecodeError, OSError):
        return False


def write_fallback_pipeline() -> None:
    """Записывает fallback-pipeline из встроенного бэклога идей."""
    log.info("Генерация fallback pipeline (%d шагов)", len(FALLBACK_STEPS))
    STEPS_FILE.parent.mkdir(parents=True, exist_ok=True)
    STEPS_FILE.write_text(
        json.dumps(FALLBACK_STEPS, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )


def reset_state_for_new_pipeline() -> None:
    state = PipelineState(
        last_step_completed=0,
        status="brainstorm_completed",
    )
    state.save(STATE_FILE)


def main() -> int:
    ap = argparse.ArgumentParser(description="Мозговой штурм: новые доки и новый pipeline")
    ap.add_argument("--allow-docker", action="store_true", help="Sandbox danger-full-access")
    ap.add_argument("--dry-run", action="store_true", help="Не вызывать codex")
    ap.add_argument("--reset-state", action="store_true", help="После успешного brainstorm сбросить state")
    ap.add_argument("--model", "-m", metavar="MODEL", help="Модель codex")
    ap.add_argument("--timeout", type=int, default=900, help="Таймаут codex в секундах")
    ap.add_argument("--fallback", action="store_true", default=True,
                    help="При неудаче codex использовать fallback pipeline (по умолчанию: да)")
    ap.add_argument("--no-fallback", action="store_false", dest="fallback",
                    help="Не использовать fallback, вернуть ошибку")
    args = ap.parse_args()

    setup_logging()

    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    prompt = BRAINSTORM_PROMPT.replace("{date}", date)
    sandbox = "danger-full-access" if args.allow_docker else "workspace-write"

    if not args.dry_run:
        result: CodexResult = run_codex_with_retry(
            prompt,
            cwd=REPO_ROOT,
            sandbox=sandbox,
            model=args.model,
            timeout_sec=args.timeout,
            dry_run=False,
            max_retries=1,
        )

        if result.exit_code != 0:
            log.warning("Codex brainstorm завершился с ошибкой (code=%d)", result.exit_code)

        if not validate_steps_file():
            log.warning(".grand_pipeline_steps.json не создан или невалиден после codex")
            if args.fallback:
                write_fallback_pipeline()
            else:
                return 1
    else:
        log.info("[DRY-RUN] brainstorm пропущен")

    if not args.dry_run and not validate_steps_file():
        log.error("Pipeline не создан даже после fallback")
        return 1

    if args.reset_state:
        reset_state_for_new_pipeline()
        log.info("State сброшен для нового pipeline")

    log.info("Мозговой штурм завершён успешно")
    return 0


if __name__ == "__main__":
    sys.exit(main())
