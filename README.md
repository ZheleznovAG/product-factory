# Product Factory

Платформа для автоматизированного создания deployable-артефактов по запросу: репозиторий с кодом, тестами, IaC, CI/CD и управляемыми AI-компонентами.

## Документация

- [Системный аудит и corrective-план](docs/system-audit-corrective-plan.md) — архитектура, roadmap, риски, KPI, шаблоны (Alpha/Beta/MVP/Scale).
- [Roadmap checklist](docs/roadmap-checklist.md) — план vs текущее состояние по фазам.
- [Как устроено и как проверить](docs/how-it-works-and-verify.md) — краткий поток (запрос → план → кодоген → tools → audit) и проверка работы (health, прогон, audit).
- [Пример сценария: один полный прогон](docs/example-scenario-full-run.md) — детально: запрос, шаги workflow, tools, что получаете (repoUrl, MinIO, audit).
- [Runbook](docs/runbook.md) — запуск, health, нейросервис, прогон с gateway (`./scripts/run_factory_with_neural.sh`), audit, approvals.
- [Дашборды, логи и наблюдение](docs/observability-and-logs.md) — метрики, Prometheus/Grafana, audit log, health, реестр артефактов.
- [Alpha sprint](docs/alpha-sprint.md) — задачи на 1–2 недели (Input Contract Spec v1).
- [Первичные источники](docs/sources.md) — ссылки на нормативы, OWASP, OPA, OTel, supply chain.
- [Модель автоматизированной разработки](docs/automated-development-model.md) — конвейер, этапы, задачи, метрики.
- [Задачи по фазам](docs/phases-task-list.md) — чек-лист для трекера.
- [Оценка реализации](docs/implementation-assessment.md), [Аудит 2026-02](docs/audit-report-2026-02.md), [Decision points v1](docs/decision-points-v1.md), [Ответ на техаудит](docs/audit-response.md), [Что предстоит сделать](docs/whats-next.md), [Не решённые вопросы](docs/open-questions.md).
- [PRD фабрики](docs/PRD-factory.md) — цель, границы MVP, критерии успеха.
- [Definition of Done для MVP](docs/definition-of-done-mvp.md).
- [Запрещённые действия агента](docs/prohibited-agent-actions.md).
- [Карта данных](docs/data-map.md), [Risk register](docs/risk-register.md), [Risk register v1 (MVP пороги)](docs/risk-register-v1.md).
- [ADR-0001: стек и prerequisites](docs/adr/0001-stack-and-prerequisites.md), [ADR-0003–0007: deployment, pinning, retention, secrets, LLM gateway](docs/adr/0003-deployment-substrate-and-cloud-strategy.md), [шаблон ADR](docs/adr/template.md).
- [Архитектура и путь к целевой системе](docs/architecture-and-vision.md) — что за система сейчас, почему «маленькая», куда растем.
- [Архитектура и путь (карта)](docs/architecture-and-path.md) — принципы, целевые роли/окружения, этапы с отметками готовности (этапы 1–2 сделаны).
- [Похожие решения на рынке](docs/market-similar-solutions.md) — обзор платформ (по классам: IDP, Cloud Dev, VCS/DevSecOps, OSS SWE-агенты), сильнее/слабее PF.
- [Стратегия: дифференциация и Kernel + плагины](docs/strategy-differentiation-and-kernel.md) — позиционирование, три направления развития, анти-паттерны, оболочка «Issue → PR с доказательствами».
- [RAG + pgvector: ingestion и версии](docs/rag-pgvector-operations.md) — загрузка `archetypes/docs` в pgvector, команды CLI, переключение активной версии индекса.
- [Хранилище профиля предпочтений](docs/profile-store.md) — профиль `embedding + rules`, consent и API `/factory/profiles/{profileId}`.
- [Solution Design (SDD)](docs/solution_design.md), [список tools MVP](docs/tools-list-mvp.md), [политика approvals](docs/approval-policy.md), [политика секретов](docs/secrets-policy.md).
- [Offline evals и trace grading](eval/README.md).

- [Инвентаризация документации](docs/docs-inventory-and-freshness.md) — список всех доков, актуальность, приоритеты; подготовка к пересоставлению документации.

Дополнительные материалы: см. `docs/` (solution_design, threat_model, risk-register, evaluation) и отчёты в репозитории при наличии.

## Структура репозитория

- `docs/` — PRD, DoD, risk register, ADR, runbook, SDD (фаза 2).
- `contracts/` — JSON schema для tools и выходов агента.
- `policies/` — OPA (Rego), бюджеты, allowlists.
- `eval/` — датасеты и раннеры для RAG/agent/security evals (фаза 5).
- `src/` — API, workflow, agent, RAG (код фабрики).
- `infra/` — K8s, GitOps (фаза 4+).
- `ci/` — quality_gates; `.github/workflows/` — CI.

## Сборка и запуск (фаза 3)

**Фабрику всегда запускаем через Docker.** Локальная сборка — через Gradle wrapper (не нужна установка Gradle):

```bash
./gradlew build
```

При создании архива репозитория не включайте каталоги `build/` и `.gradle/` (они в `.gitignore`).

**Штатный запуск — только через Docker:**

```bash
docker build -t product-factory:latest .
docker run -p 8080:8080 product-factory:latest
```

Сервер слушает порт 8080. Запрос: `POST /factory/run` с телом `{"goal": "...", "constraints": []}`. Опционально: `tenantId` (или `X-Tenant-Id`), `target_stack` (целевой стек) и `budget` (`token_budget`, `tool_calls_budget`, `wall_clock_seconds`). См. [runbook](docs/runbook.md).

Потребительский CLI (внутри образа):

```bash
/app/bin/product-factory run --url http://localhost:9080 --goal "API service for X" --target-stack web-app
/app/bin/product-factory status <runId> --url http://localhost:9080
/app/bin/product-factory intent --url http://localhost:9080 --query "Нужен onboarding для B2B SaaS" --variants 3 --pick 1 --run
```

## Валидация контрактов

- **Через Docker:** `docker run --rm -v "$(pwd):/workspace" -w /workspace product-factory:latest /app/bin/product-factory validate /workspace/path/to/contracts` (или смонтировать нужную директорию с YAML).
- **Contract Drift Guard (CLI):** `product-factory contract-drift-guard /path/to/contracts --format json --report ./reports/contract-drift-report.json`.
- **В CI (`contracts-validation`):** job запускается при изменениях в `contracts/` или `contracts-example/` (push/PR), а также на каждый `push` в `main`; выполняется Contract Drift Guard для `contracts-example/` (или `contracts/`, если `contracts-example/` отсутствует), отчёт публикуется как artifact. См. [.github/workflows/contracts-validation.yml](.github/workflows/contracts-validation.yml).
- Проверяются обязательные файлы:
  - `product.yaml`
  - `constraints.yaml`
  - `quality_profile.yaml`
  - `risk_profile.yaml`
  - `target_stack.yaml`
- Схемы берутся из `contracts/schemas/*.json`.
- Для валидации перед `factory run` задайте `FACTORY_CONTRACTS_DIR=/path/to/contracts`: при ошибках запуск отклоняется (`rejected`) до policy/tool шагов.
- Подробно: [docs/contract-drift-guard.md](docs/contract-drift-guard.md).

## Для агентов (Codex / Cursor)

- **AGENTS.md** — краткое руководство для агентов (границы, команды, ключевые доки).
- **.cursor/skills/product-factory/** — скилл Cursor для фабрики (фазы, контракты, оркестр).
- **.cursor/rules/product-factory.mdc** — правило при работе с Kotlin/docs/contracts/scripts.

## Текущий статус

Alpha, Beta, MVP и Scale закрыты: схемы, валидатор, state machine, audit log, OPA, OTel, архетипы catalog-service и web-app, планировщик и кодоген с LLM при `NEURAL_SERVICE_URL` (gateway в `scripts/neural_gateway/`), approvals, eval gate, supply-chain для фабрики и архетипов. **Откуда продолжить:** [roadmap-checklist → «Откуда продолжить»](docs/roadmap-checklist.md#откуда-продолжить). Проверка работы: [how-it-works-and-verify](docs/how-it-works-and-verify.md), `./scripts/run_factory_with_neural.sh`.
