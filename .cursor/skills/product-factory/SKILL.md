---
name: product-factory
description: Руководство по репозиторию Product Factory — фазы Alpha/Beta/MVP, контракты (product/constraints/quality/risk/target_stack), валидатор, ADR, risk tiers. Использовать при вопросах про фабрику, alpha sprint, схемы в contracts/schemas, запуск scripts/run_alpha_sprint.py, Kotlin/Docker конвенции.
---

# Product Factory — скилл проекта

## Когда применять

- Пользователь спрашивает про Alpha-спринт, фазы, roadmap, контракты фабрики.
- Нужно запустить оркестр разработки, валидировать конфиги, создать/обновить ADR или risk-register.
- Задачи по Kotlin-коду фабрики, схемы в contracts/schemas, политики OPA, runbook.

## Основные артефакты

| Что | Где |
|-----|-----|
| План и фазы | docs/system-audit-corrective-plan.md, docs/roadmap-checklist.md |
| Alpha sprint | docs/alpha-sprint.md |
| Схемы контрактов | contracts/schemas/*.schema.json (product, constraints, quality_profile, risk_profile, target_stack) |
| ADR | docs/adr/0001-*.md, 0002-layer-boundaries.md, template.md |
| Риски и approvals | docs/risk-register.md, docs/approval-policy.md |
| Оркестр Codex | scripts/run_alpha_sprint.py, scripts/run_beta_sprint.py (--allow-docker для Docker в терминале) |

## Конвенции

- В Docker только Kotlin (один Dockerfile в корне). Codex запускается на хосте.
- Side-effects только через Tool Executor; агент — только proposals. См. docs/adr/0002-layer-boundaries.md, docs/prohibited-agent-actions.md.
- Новые решения — ADR в docs/adr/ по шаблону; изменения risk/approval — обновить docs/risk-register.md и docs/approval-policy.md.

## Команды

Фабрику всегда запускаем через Docker. Codex/оркестр — на хосте.

```bash
# Запуск фабрики (только Docker)
docker build -t product-factory . && docker run -p 8080:8080 product-factory

# Валидация контрактов (в контейнере)
docker run --rm -v "$(pwd):/w" -w /w product-factory /app/bin/product-factory validate /w/contracts

# Alpha/Beta-спринт (Codex на хосте, с Docker и коммитами)
python3 scripts/run_alpha_sprint.py
python3 scripts/run_beta_sprint.py --allow-docker
python3 scripts/run_alpha_sprint.py --step 2 --model gpt-5.3-codex
```
