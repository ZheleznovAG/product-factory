---
name: product-factory
description: "Репозиторий Product Factory. Kotlin/Ktor, фазы Alpha/Beta/MVP/Scale, контракты product/constraints/quality_profile/risk_profile/target_stack, валидатор, workflow state machine, ADR, risk tiers. Оркестр Codex run_alpha_sprint.py, run_beta_sprint.py, run_mvp_sprint.py, run_scale_sprint.py (--allow-docker для Docker в терминале). Использовать при задачах по фабрике, спринтам, схемам contracts/schemas, Kotlin/Docker конвенциям."
---

# Product Factory — скилл для Codex

## Когда применять

- Задачи по Alpha/Beta/MVP/Scale-спринту, фазам, roadmap, контрактам фабрики.
- Изменения Kotlin-кода в src/main/kotlin/productfactory/, схем в contracts/schemas/, политик OPA.
- Запуск оркестра (run_alpha_sprint.py, run_beta_sprint.py, run_mvp_sprint.py, run_scale_sprint.py), валидация контрактов, ADR, risk-register.

## Основные артефакты

| Что | Где |
|-----|-----|
| План и фазы | docs/system-audit-corrective-plan.md, docs/roadmap-checklist.md |
| Alpha / Beta / MVP / Scale | docs/alpha-sprint.md, scripts/run_alpha_sprint.py, run_beta_sprint.py, run_mvp_sprint.py, run_scale_sprint.py |
| Схемы контрактов | contracts/schemas/*.schema.json |
| ADR | docs/adr/ (0002-layer-boundaries.md, template.md) |
| Риски и approvals | docs/risk-register.md, docs/approval-policy.md |

## Конвенции

- В Docker-образе фабрики только Kotlin (один Dockerfile в корне). Codex и оркестр запускаются на хосте.
- Side-effects только через Tool Executor; агент — только proposals. См. docs/adr/0002-layer-boundaries.md.
- Для сборки образов и запуска контейнеров использовать команды в терминале (`docker build`, `docker run`, `./gradlew test` и т.д.). MCP не используется.

## Оркестр спринтов (на хосте)

```bash
# Alpha (с записью и коммитами)
python3 scripts/run_alpha_sprint.py
python3 scripts/run_alpha_sprint.py --allow-docker --model gpt-5.3-codex

# Beta — все шаги, с Docker и коммитами
python3 scripts/run_beta_sprint.py --allow-docker

# MVP — первый archetype (catalog-service, CI, SBOM/Trivy/Cosign, staging)
python3 scripts/run_mvp_sprint.py --allow-docker
python3 scripts/run_mvp_sprint.py --allow-docker --model gpt-5.3-codex

# Scale — proposal engine (планировщик, кодоген, approvals, trace grading)
python3 scripts/run_scale_sprint.py --allow-docker
```

## Фабрика (только Docker)

```bash
docker build -t product-factory . && docker run -p 8080:8080 product-factory
docker run --rm -v "$(pwd):/w" -w /w product-factory /app/bin/product-factory validate /w/contracts
```
