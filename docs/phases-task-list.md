# Задачи по фазам постройки фабрики (solo)

Список задач по фазам из [automated-development-model.md](automated-development-model.md) для переноса в трекер (GitHub Issues, доска и т.п.). Метки: `phase: <название>`, `risk-tier: low|medium|high`. WIP-лимит: 1–2 темы в спринте.

---

## Фаза 1: Инициация и риск (1–2 нед)

- [x] Написать PRD фабрики (цель, границы, успех)
- [x] Составить карту данных: PII, назначение обработки, retention
- [x] Провести риск-тиринг фабричных операций
- [x] Создать и вести risk register (формат в report-6)
- [x] Зафиксировать definition of done для MVP
- [x] Зафиксировать список запрещённых действий агента
- [x] Оформить external prerequisites (репо, CI, секреты, логи) как ADR
- [x] Decision points v1 (пороги смены стека) — [decision-points-v1.md](decision-points-v1.md)

**Критерий успеха фазы:** чёткий DoD для MVP, зафиксированные запреты.

---

## Фаза 2: Архитектура и контракты (2–4 нед)

- [x] Написать SDD (референс-архитектура из report-4)
- [x] Завести ADR-набор, первый ADR — выбор workflow engine
- [x] Составить список tools для MVP
- [x] Описать контракт tool-calling (JSON schema, risk_tier, approvals)
- [x] Зафиксировать политику approvals (где требуется человек)
- [x] Выбрать и задокументировать политику секретов (Vault / аналог)
- [x] Проверить: спеки/задачи только в structured формате, side-effects только через tool executor

**Критерий успеха фазы:** агент генерирует только структурированные выходы; side-effects запрещены без tool executor.

---

## Фаза 3: Основание платформы (4–8 нед)

- [x] Реализовать прототип API (Gateway) для factory run
- [x] Интегрировать workflow engine (Temporal или аналог)
- [x] Реализовать базовый policy (OPA: allowlist + бюджеты)
- [x] Вызов OPA из приложения при заданном OPA_URL (fallback allow при отсутствии)
- [x] Настроить OTel traces для одного сквозного run
- [x] Реализовать audit log для каждого tool call
- [x] Достичь одного «factory run» трассируемого end-to-end (stub)
- [x] Интеграционный тест POST /factory/run

**Критерий успеха фазы:** один factory run трассируется end-to-end, все tool calls в audit log.

---

## Фаза 4: MVP фабрики (8–12 нед)

- [x] Создать первый archetype «API service» (catalog-service)
- [x] Реализовать создание репо из archetype (Repo Service / tool create_repo_from_archetype)
- [x] Подключить CI (lint, unit, integration) к созданному репо (ci-archetype-catalog.yml)
- [x] Настроить деплой в staging (GitOps-style: docker-compose, smoke-staging.sh)
- [x] Реализовать sandbox executor для tool calls
- [x] Настроить RAG ingestion + pgvector, версионирование индекса
- [x] Достичь воспроизводимого «запрос → staging» (процедуры в runbook)
- [x] Обеспечить стабильно зелёный pipeline для archetype

**Критерий успеха фазы:** «запрос → staging» воспроизводимо 3 раза, pipeline зелёный.

---

## Фаза 5: Надёжность и качество (3–4 мес)

- [ ] Ввести offline eval: RAGAs (faithfulness, relevance)
- [x] Ввести agent missions + trace grading (run_offline_evals.py, trace.schema.json, 3 сценария)
- [x] Добавить eval gate в CI (блокировка при регрессии) — job eval-regression-gate в ci.yml
- [x] Интегрировать durable engine (Temporal/Argo) в основной контур
- [x] Подготовить набор eval-датасетов / миссий (agent_trace_minimal.json)
- [x] Подключить LLM к планировщику и кодогену (LlmAgentPlanner, LlmAgentCodegen при NEURAL_SERVICE_URL; fallback на stub)
- [x] Определить черновик SLO и метрик cost для решения о promotion (slo-cost-draft.md, slo-ci-gate.md)
- [ ] Обеспечить онлайн-сигналы SLO/cost для promotion

**Критерий успеха фазы:** offline eval gate блокирует регресс; есть SLO/cost для решения о promotion.

---

## Scale (AI proposal engine) — закрыта

- [x] Планировщик с LLM/Codex (LlmAgentPlanner, gateway с полным промптом, audit agent_planner_call)
- [x] Кодоген с LLM/Codex (LlmAgentCodegen, audit agent_codegen_call, fallback на StubAgentCodegen)
- [x] Approvals API и eval gate в CI; WS1/WS3/WS4 MVP (roles, human loop, intent layer build-mode)

---

## Фаза 6: Supply chain и комплаенс (3–4 мес)

- [x] Встроить в CI: SBOM (Syft), scan (Trivy), sign (cosign) — для архетипа (supply-chain-gate в ci-archetype-catalog.yml)
- [x] Настроить аттестации (SLSA) по политике
- [x] Провести threat modeling (NIST AML, MITRE ATLAS)
- [ ] Завести контур AI-risk (NIST AI RMF, процессные элементы ISO 42001)
- [ ] Обеспечить: каждый release с SBOM и подписью; high-risk — ручной approval

**Критерий успеха фазы:** любой release с SBOM и подписью; high-risk изменения требуют ручного approval.

---

## Фаза 7: Продуктивизация (3–4 мес)

- [ ] Настроить GitOps CD до prod, canary (по необходимости)
- [ ] Ввести cost budgets и мониторинг cost per run
- [x] Добавить второй архетип (web-app): код, CI ci-archetype-web-app.yml, runbook; при необходимости — третий (data pipeline)
- [ ] (Опционально) Multi-tenancy + portal/CLI для self-serve
- [ ] Достичь: потребитель получает новый сервис без ручного копипаста; rollback за минуты через GitOps revert

**Критерий успеха фазы:** потребитель получает сервис без копипаста; rollback за минуты.
