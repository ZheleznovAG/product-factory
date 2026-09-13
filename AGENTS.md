# Product Factory — руководство для агентов

Проект: платформа для автоматизированного создания deployable-артефактов (execution core + контролируемый агентный слой). Соло-режим, Kotlin/Ktor, один Docker-образ (только JVM).

## Что соблюдать

- **Язык и стек:** Kotlin, Ktor, JDK 17. В Docker — только этот образ (Dockerfile в корне). Без Node/Python в рантайме фабрики.
- **Границы слоёв:** side-effects только через Tool Executor; агент генерирует proposals (diff/ADR/тесты), не выполняет запись/деплой напрямую. См. [docs/adr/0002-layer-boundaries.md](docs/adr/0002-layer-boundaries.md), [docs/prohibited-agent-actions.md](docs/prohibited-agent-actions.md).
- **Контракты:** вход — пять YAML (product, constraints, quality_profile, risk_profile, target_stack), схемы в [contracts/schemas/](contracts/schemas/). apiVersion: productfactory.io/v1.
- **Документация:** решения фиксировать в [docs/adr/](docs/adr/), риски — [docs/risk-register.md](docs/risk-register.md), политики — [docs/approval-policy.md](docs/approval-policy.md).

## Частые задачи

- **Запуск фабрики:** только через Docker. Один контейнер: `docker run -p 8080:8080 product-factory`. Compose: `docker compose -f deploy/docker-compose.yml up -d` — фабрика на **9080**, Grafana на 3001. Локальный Gradle на хосте не требуется.
- **Проверка (сборка и тесты):** только в Docker: `docker build -t product-factory .` — в образе выполняется `gradle build installDist` (включая тесты). На хосте JDK не нужна.
- **Запуск спринтов (оркестр Codex):** на хосте: `python3 scripts/run_alpha_sprint.py`, `run_beta_sprint.py`, **`run_mvp_sprint.py`** (MVP), **`run_scale_sprint.py`** (Scale), **`run_factory2_full_sprint.py`** (полный спринт Factory 2.0: полировка + LLM Planner + доки). **`run_completion_pipeline.py`** — актуальный пайплайн завершения (16 оставшихся задач). По умолчанию `--sandbox workspace-write`; для Docker: `--allow-docker`. См. [docs/sprint-factory2-howto.md](docs/sprint-factory2-howto.md), [docs/completion-pipeline.md](docs/completion-pipeline.md).
- **Валидация контрактов:** через Docker: `docker run --rm -v "$(pwd):/w" -w /w product-factory /app/bin/product-factory validate /w/path/to/yaml`.
- **Нейросервис:** фабрика вызывает единый API (OpenAI-совместимый). URL — `NEURAL_SERVICE_URL`; бэкенд прозрачен (OpenAI, self-hosted или gateway на хосте с Codex). Шлюз: `scripts/neural_gateway/` (режимы `codex` / `openai` / `proxy`). См. [docs/neural-service-api.md](docs/neural-service-api.md).

## Ключевые документы

- [docs/usage-overview.md](docs/usage-overview.md) — как всё работает и как использовать (режимы, сценарии, что на выходе).
- [docs/how-it-works-and-verify.md](docs/how-it-works-and-verify.md) — как убедиться, что фабрика работает (health, прогон, audit).
- [docs/system-audit-corrective-plan.md](docs/system-audit-corrective-plan.md) — план, фазы, шаблоны.
- [docs/strategic-fork-factory-2.md](docs/strategic-fork-factory-2.md) — развилка Factory 1.0/2.0; реализован гибрид (LLM для Planner и Codegen при NEURAL_SERVICE_URL).
- [docs/agent-vs-llm-in-factory.md](docs/agent-vs-llm-in-factory.md) — различие «агент» и «LLM», как связаны в фабрике.
- [docs/sprint-full-backlog.md](docs/sprint-full-backlog.md) — полный бэклог задач; [docs/sprint-factory2-howto.md](docs/sprint-factory2-howto.md) — как запустить полный спринт.
- [docs/alpha-sprint.md](docs/alpha-sprint.md) — задачи Alpha.
- [docs/roadmap-checklist.md](docs/roadmap-checklist.md) — прогресс по фазам.
- [docs/architecture-and-path.md](docs/architecture-and-path.md) — принципы, роли/окружения, этапы с отметками (1–2 сделаны).
- [docs/strategy-differentiation-and-kernel.md](docs/strategy-differentiation-and-kernel.md) — позиционирование, Kernel + плагины, анти-паттерны.
- [docs/solution_design.md](docs/solution_design.md) — архитектура.

## Что не делать

- Не добавлять новый рантайм (Node и т.п.) в Docker-образ фабрики.
- Не давать агенту право выполнять write/deploy без прохождения Tool Executor и policy (approvals для privileged).
- Не хранить секреты в коде; только env / .env.example.
