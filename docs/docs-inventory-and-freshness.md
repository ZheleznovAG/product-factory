# Инвентаризация документации Product Factory

Подготовка к перебору и пересоставлению всей документации. Для каждого документа: назначение, оценка актуальности, приоритет (главный/второстепенный), замечания.

**Легенда актуальности:**
- **Актуально** — соответствует коду и процессу, ссылки рабочие
- **Частично** — в целом верно, но есть устаревшие фрагменты или битые ссылки
- **Устарело** — расхождение с кодом/реальностью или ссылки на несуществующие файлы
- **Справочно** — исторический/бэклог, не претендует на «истину»

**Приоритет:**
- **P0 (главные)** — без них нельзя понять систему, запустить, развивать; первыми при пересоставлении
- **P1** — важные для работы и решений
- **P2** — полезные, но можно объединить или вынести в архив
- **P3** — бэклог, отчёты, планы; поддерживать по мере необходимости

---

## 1. Корень репозитория

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **README.md** | Точка входа: что такое фабрика, сборка, запуск, ссылки на доки | Частично | **P0** | Длинный список ссылок; часть дублирует AGENTS.md. При пересоставлении — один «навигатор» (README или docs/README). |
| **AGENTS.md** | Правила для агентов/IDE: стек, границы, частые задачи, ключевые доки | Актуально | **P0** | Синхронизирован с текущим процессом; держать как единый источник «что соблюдать». |

---

## 2. docs/ — ядро документации

### 2.1. Архитектура и дизайн

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **solution_design.md** | SDD: Control vs Intelligence, референс-архитектура, компоненты | Частично | **P0** | Ссылается на несуществующий `deep-research-report-4.md`. Текст и диаграммы актуальны. |
| **architecture-and-path.md** | Принципы, этапы 1–5, отметки готовности, целевые роли/окружения | Актуально | **P0** | Основной документ «куда мы идём»; обновляется по roadmap. |
| **architecture-and-vision.md** | Что за система сейчас, почему «маленькая», целевая картина | Актуально | **P1** | Пересекается с architecture-and-path; при пересоставлении можно объединить в один «Architecture». |
| **control-vs-intelligence-plane.md** | Разделение детерминированного и недетерминированного слоёв | Актуально | **P1** | Дублирует идеи solution_design и ADR-0002; можно оставить как краткий конспект. |
| **monolith-vs-services.md** | Выбор монолита vs микросервисов | Справочно | P2 | Решение принято; для истории. |
| **implementation-variants-and-focus.md** | Варианты реализации, фокус | Справочно | P2 | План/рассуждение; при пересоставлении — вынести в ADR или архив. |

### 2.2. Видение и стратегия

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **strategic-fork-factory-2.md** | Развилка Factory 1.0 / 2.0, гибрид LLM | Актуально | **P0** | Текущая реализация — гибрид; часто цитируется. |
| **strategy-differentiation-and-kernel.md** | Позиционирование, Kernel + плагины, анти-паттерны | Актуально | P1 | Связь с architecture-and-path. |
| **roadmap-workstreams-and-variants.md** | Workstreams WS0–WS4, варианты A/B, Intent Layer | Актуально | **P0** | Roadmap мульти-агентов, окружений и Intent Layer; ссылается из AGENTS. |
| **market-similar-solutions.md** | Похожие решения на рынке (IDP, Cloud Dev, OSS SWE-агенты) | Справочно | P2 | Обзор; обновлять по мере необходимости. |

### 2.3. Требования и контракты

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **PRD-factory.md** | Цель, границы MVP, критерии успеха | Актуально | **P0** | Продуктовый контракт; держать синхрон с фазами. |
| **definition-of-done-mvp.md** | DoD для MVP | Актуально | P1 | Связь с roadmap-checklist. |
| **contract-drift-guard.md** | Контракты, drift guard, CI | Актуально | **P0** | Используется в CI и runbook. |
| **session-contract.md** | Контракт сессии | Частично | P2 | Уточнить границы использования. |
| **artifact-manifest-contract.md** | Схема манифеста артефактов | Актуально | P1 | Связь с ArtifactRegistry, runbook. |
| **artifact-handoff-protocol.md** | Протокол передачи артефактов | Актуально | P2 | Для интеграций. |

### 2.4. Безопасность, политики, риски

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **threat_model.md** | Угрозы, сценарии, связь с risk-register | Актуально | **P0** | Синхронизация с risk-register по процессу. |
| **risk-register.md** | Реестр рисков, митигация, SLO/cost, AI-risk, multi-tenancy | Актуально | **P0** | Живой документ; ссылка на deep-research-report-9 — ок. |
| **risk-register-v1.md** | MVP-пороги, триггеры, расширенная таблица | Актуально | P1 | Дополняет risk-register; явно «v1», не заменяет основной. |
| **risk-tier-mapping.md** | Канонический каталог risk tiers, approvals/budgets | Актуально | P1 | Ссылается из risk-register. |
| **approval-policy.md** | Политика approvals, human-in-the-loop | Актуально | **P0** | В AGENTS; используется в коде и runbook. |
| **secrets-policy.md** | Секреты: где хранить, что не логировать | Актуально | P1 | Связь с ADR-0006. |
| **prohibited-agent-actions.md** | Запрещённые действия агента | Актуально | **P0** | В AGENTS; границы слоёв. |
| **ai-risk-contour-plan.md** | План контура AI-risk: роли, контрольные точки | Актуально | P1 | Ссылается из risk-register. |
| **ai-risk-checklist.md** | Операционный чеклист AI-risk | Актуально | P1 | Короткий; связь threat_model ↔ risk-register. |
| **DEPENDENCY_POLICY.md** | Политика зависимостей | Актуально | P2 | Версионирование, обновления. |

### 2.5. Операции и как пользоваться

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **runbook.md** | Запуск, health, нейросервис, прогоны, audit, approvals, OPA, Temporal | Актуально | **P0** | Главный операционный документ; объёмный. |
| **usage-overview.md** | Как всё работает, режимы, ввод/вывод, сценарии | Актуально | **P0** | В AGENTS; дублирует часть runbook в более «продуктовом» виде. |
| **how-it-works-and-verify.md** | Как убедиться, что фабрика работает (health, прогон, audit) | Актуально | **P0** | В AGENTS; краткий поток + проверка. |
| **observability-and-logs.md** | Метрики, Prometheus/Grafana, audit log, health, реестр артефактов | Актуально | **P1** | Связь с runbook и deploy. |
| **example-scenario-full-run.md** | Один полный прогон: запрос → шаги → результат | Актуально | P1 | Полезен для онбординга. |
| **environments.md** | Окружения (dev/staging/prod) | Частично | P2 | Уточнить актуальность под текущий deploy. |
| **github-setup.md** | Настройка GitHub (токен, репо) | Актуально | P1 | Для прогонов с GitHub. |
| **sources.md** | Первичные источники (нормативы, OWASP, OPA, OTel) | Актуально | P2 | Справочник. |

### 2.6. Планы, аудит, прогресс

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **system-audit-corrective-plan.md** | План аудита: архитектура, roadmap, риски, KPI, шаблоны фаз | Актуально | **P0** | Большой «мастер-план»; много ссылок. |
| **roadmap-checklist.md** | План vs текущее состояние по фазам Alpha/Beta/MVP/Scale | Актуально | **P0** | В AGENTS; обновляется по мере закрытия. |
| **implementation-assessment.md** | Оценка реализации: что есть в коде | Актуально | **P1** | Сводка «реализовано/нет»; связь с audit. |
| **audit-report-2026-02.md** | Отчёт техаудита 2026-02 | Справочно | P2 | Исторический; выводы учтены в audit-response. |
| **audit-response.md** | Ответ на аудит: что исправлено, ссылки на доки | Актуально | P1 | Упорядочивает ссылки после аудита. |
| **audit-scheduled-2026-02-26.md** | Запланированный аудит | Справочно | P3 | Можно архивировать после выполнения. |
| **report-execution-2026-02-20.md** | Отчёт выполнения | Справочно | P3 | Снимок состояния. |

### 2.7. Спринты и бэклог

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **sprint-full-backlog.md** | Полный бэклог задач (фазы, приоритеты) | Актуально | **P1** | Используется скриптами спринта; источник для what-to-do-full. |
| **sprint-factory2-howto.md** | Как запустить полный спринт Factory 2.0 | Актуально | **P1** | В AGENTS; скрипты run_*_sprint.py. |
| **completion-pipeline.md** | Пайплайн завершения: только оставшиеся 16 шагов | Актуально | **P1** | В AGENTS (run_completion_pipeline.py). |
| **what-to-do-full.md** | Единый перечень: что сделано, что осталось | Актуально | **P1** | Сводка из нескольких источников; дата 2026-02-27. |
| **whats-next.md** | Что предстоит сделать (кратко) | Частично | P2 | Может дублировать what-to-do-full и completion-pipeline. |
| **next-steps-plan.md** | План следующих шагов | Частично | P2 | Пересекается с completion-pipeline. |
| **phases-task-list.md** | Задачи по фазам (чек-лист для трекера) | Актуально | P2 | Связь с roadmap-checklist. |
| **grand-pipeline-plan.md** | План Grand Pipeline (38 шагов) | Частично | P2 | Большая часть выполнена; актуален completion-pipeline. |
| **grand-pipeline-tasks.md** | Задачи Grand Pipeline | Частично | P2 | То же. |
| **ideas-backlog.md** | Идеи в бэклог | Справочно | P3 | |
| **open-questions.md** | Не решённые вопросы | Актуально | P2 | Обновлять по мере закрытия. |
| **decision-points-v1.md** | Точки решений (архитектура, стек, процесс) | Актуально | P2 | Справочник решений. |

### 2.8. Intent, API, профили

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **api-intent-experience.md** | API intent/experience (estimate, generate) | Актуально | **P1** | Связь с кодом Intent API. |
| **profile-store.md** | Хранилище профиля предпочтений (embedding + rules) | Актуально | P1 | API /factory/profiles. |
| **intent-candidates-protocol.md** | Протокол кандидатов intent | Актуально | P2 | |
| **intent-clarification-protocol.md** | Протокол уточнения intent | Актуально | P2 | |
| **intent-clarification-text-audio-optimality.md** | Текст vs аудио при уточнении | Справочно | P2 | |
| **intent-protocol-90s.md** | Протокол «90 секунд» | Актуально | P2 | Ссылается на intent-clarification-protocol. |
| **decision-points-v1.md** | (см. выше в бэклог) | — | — | |

### 2.9. Нейросервис, RAG, агент

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **neural-service-api.md** | API нейросервиса (OpenAI-совместимый) | Актуально | **P0** | В AGENTS; контракт вызовов. |
| **neural-service-operations.md** | Операции: URL, ключи, триггеры пересмотра | Актуально | P1 | |
| **agent-vs-llm-in-factory.md** | Различие «агент» и «LLM» в фабрике | Актуально | **P1** | В AGENTS. |
| **agent-role-registry.md** | Реестр ролей агента | Частично | P2 | Статус реализации уточнить. |
| **factory-2-planner-integration.md** | Интеграция LLM в Planner | Актуально | P1 | |
| **rag-pgvector-operations.md** | RAG + pgvector: загрузка, версии, CLI | Актуально | P1 | |
| **rag-pgvector-requirements.md** | Требования к RAG/pgvector | Актуально | P2 | |
| **eval-ragas.md** | Оценка RAG (RAGAs) | Актуально | P2 | Связь с eval/. |
| **automated-development-model.md** | Модель автоматизированной разработки, конвейер | Частично | P2 | Ссылается на несуществующие report-4, report-6. |

### 2.10. Инфраструктура, деплой, SLO

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **integrations-server-deployment.md** | Интеграции, сервер, деплой | Частично | P2 | |
| **gitops-cd-prod-plan.md** | План GitOps CD до prod | Актуально | P2 | |
| **slo-ci-gate.md** | SLO gate в CI | Актуально | P1 | |
| **slo-cost-draft.md** | Черновик SLO/cost | Актуально | P2 | |
| **slo-promotion-signals.md** | Сигналы для promotion (SLO/cost) | Актуально | P2 | |
| **scale-step4-trace-eval-gate.md** | Trace/eval gate (Scale) | Актуально | P2 | |
| **slsa-attestation-plan.md** | План SLSA аттестаций | Актуально | P2 | |
| **supply-chain/slsa-attestation-coverage.md** | Покрытие SLSA | Актуально | P2 | |
| **period-usage-counters-design.md** | Дизайн счётчиков использования (period) | Актуально | P2 | |
| **runner-provisioning-and-browser-smoke.md** | Provisioning раннеров, browser smoke | Частично | P2 | |
| **scheduled-runner-howto.md** | Howto по запланированному раннеру | Актуально | P2 | |
| **e2e-temporal.md** | E2E с Temporal | Актуально | P2 | |

### 2.11. Прочее (инструменты, данные, шаблоны)

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **tools-list-mvp.md** | Список инструментов MVP | Актуально | P1 | |
| **data-map.md** | Карта данных (что где хранится, retention) | Актуально | P1 | Связь с ADR-0005, 0006. |
| **alpha-sprint.md** | Задачи Alpha (Input Contract Spec v1) | Актуально | P1 | Исторически фаза закрыта; для контекста. |
| **golden-scenarios.md** | Золотые сценарии | Актуально | P2 | |
| **proper-implementation-plan.md** | План реализации (placeholder → реализовано) | Частично | P2 | |
| **implementation-assessment.md** | (см. выше в планы/аудит) | — | — | |
| **policy-simulation-dashboard.md** | Дашборд симуляции политик | Частично | P3 | Статус реализации уточнить. |
| **roadmap-workstreams-and-variants.md** | Варианты и воркстримы roadmap | Актуально | P2 | |
| **archetypes-and-roadmap.md** | Архетипы и roadmap | Актуально | P1 | |
| **definition-of-done-mvp.md** | (см. выше в требования) | — | — | |

### 2.12. Аналитика и отчёты

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **deep-research-report-9.md** | Аудит: соответствие доков и кода, пробелы, рекомендации | Актуально | **P1** | Используется в risk-register, what-to-do-full, audit-response. |
| **deep-research-report-11.md** | Детальный аудит: таблица требований, недостающее, расхождения, роадмап | Актуально | **P1** | Более детальный срез, чем report-9. |
| **planning-pipeline.md** | Пайплайн продумывания: генерация полного плана технологий и задач из architecture-and-path/solution_design | Актуально | P1 | Запуск: run_planning_pipeline.py; результат — technology-and-implementation-plan.md. |
| **technology-and-implementation-plan.md** | План технологий и реализации (стек, архитектура, задачи, интеграция) | Справочно | P1 | Заполняется пайплайном planning-pipeline; open-source + альтернативы, «осталось только делать». |
| **factory-run-test-analysis.md** | Анализ FactoryRunTest (e2e/Docker) | Актуально | P2 | |

---

## 3. docs/adr/ — архитектурные решения

| Файл | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **0001-stack-and-prerequisites.md** | Стек и предварительные условия | Актуально | **P0** | Базовый ADR. |
| **0002-layer-boundaries.md** | Границы слоёв, side-effects только через tools | Актуально | **P0** | В AGENTS; ключевой. |
| **0003-deployment-substrate-and-cloud-strategy.md** | Деплой, облако | Актуально | P1 | |
| **0004-dependency-version-pinning.md** | Пinning зависимостей | Актуально | P1 | |
| **0005-log-audit-trace-retention.md** | Retention логов, audit, trace | Актуально | P1 | Ссылается из data-map. |
| **0006-secrets-management-mvp.md** | Секреты для MVP | Актуально | P1 | |
| **0007-llm-gateway-api-shape.md** | Форма API LLM gateway | Актуально | P1 | |
| **0008-agent-roles-pipeline.md** | Роли агента, пайплайн | Актуально | P1 | |
| **0009-ask-user-contract-and-store.md** | Контракт AskUser, хранилище | Актуально | **P1** | Используется в runbook/API. |
| **0010-environment-provider-abstraction.md** | Абстракция провайдера окружений | Актуально | P1 | |
| **0011-sbom-signature-source-in-run.md** | SBOM/подпись в run | Актуально | P1 | |
| **0012-local-docker-and-remote-ssh-scope.md** | Local Docker и remote SSH (scope) | Актуально | P1 | Упоминается в completion-pipeline. |
| **0013-release-sbom-signature-and-manual-high-risk-approval.md** | Релиз, SBOM, high-risk approval | Актуально | P1 | |
| **0014-tenant-id-api-and-isolation.md** | tenantId, изоляция | Актуально | **P1** | Multi-tenancy. |
| **template.md** | Шаблон ADR | Актуально | P2 | |

---

## 4. Документация вне docs/

| Путь | Назначение | Актуальность | Приоритет | Замечания |
|------|------------|--------------|-----------|-----------|
| **deploy/README.md** | Деплой, compose, профили | Актуально | **P1** | |
| **deploy/staging/README.md** | Staging | Актуально | P2 | |
| **deploy/prod/README.md** | Prod | Актуально | P2 | |
| **deploy/prod-canary/README.md** | Canary | Актуально | P2 | |
| **eval/README.md** | Offline evals, trace grading | Актуально | P1 | |
| **scripts/README.md** | Скрипты (run_*_sprint, gateway) | Актуально | P1 | |
| **scripts/neural_gateway/README.md** | Нейро-шлюз | Актуально | P1 | |
| **policies/opa/README.md** | OPA, Rego | Актуально | P1 | |
| **infra/README.md** | Инфраструктура | Частично | P2 | |
| **env.d/README.md** | Переменные окружения | Актуально | P2 | |
| **src/rag/README.md** | RAG модуль | Актуально | P2 | |
| **src/workflow/README.md** | Workflow | Актуально | P2 | |
| **src/agent/README.md** | Агент | Актуально | P2 | |
| **src/api/README.md** | API | Актуально | P2 | |
| **archetypes/catalog-service/README.md** | Архетип catalog-service | Актуально | P2 | |
| **archetypes/web-app/README.md** | Архетип web-app | Актуально | P2 | |
| **.cursor/skills/product-factory/SKILL.md** | Скилл Cursor (то же, что .agents/skills) | Актуально | P2 | Синхронизировать с AGENTS.md при пересоставлении. |
| **.agents/skills/product-factory/SKILL.md** | Скилл для агентов | Актуально | P2 | |
| **.github/PULL_REQUEST_TEMPLATE.md** | Шаблон PR | Актуально | P2 | |

---

## 5. Битые или устаревшие ссылки (требуют правки)

| Документ | Ссылка на | Действие |
|----------|------------|----------|
| solution_design.md | deep-research-report-4.md | Убрать или заменить на «источник: внутренний анализ» / ссылку на docs/ |
| automated-development-model.md | deep-research-report-4.md, deep-research-report-6.md | То же |
| README.md | Много ссылок на отдельные доки | При пересоставлении — сократить до навигатора по разделам |

---

## 6. Рекомендуемый порядок при пересоставлении документации

1. **Согласовать структуру**  
   Один навигатор (README или docs/README.md): разделы «Архитектура», «Запуск и операции», «Контракты и политики», «Риски и безопасность», «Развитие и бэклог», «ADR».

2. **P0 (главные) — привести в порядок первыми**  
   README, AGENTS.md, solution_design, architecture-and-path, roadmap-workstreams-and-variants, strategic-fork-factory-2, PRD-factory, contract-drift-guard, threat_model, risk-register, approval-policy, prohibited-agent-actions, runbook, usage-overview, how-it-works-and-verify, system-audit-corrective-plan, roadmap-checklist, neural-service-api, ADR 0001, 0002, 0009.

3. **Исправить битые ссылки**  
   solution_design, automated-development-model — убрать ссылки на несуществующие report-4/report-6.

4. **Объединить или пометить дубли**  
   architecture-and-vision ↔ architecture-and-path; whats-next / next-steps-plan ↔ completion-pipeline / what-to-do-full. Либо один «текущий бэклог», либо явная иерархия: sprint-full-backlog → completion-pipeline → what-to-do-full.

5. **Бэклог и отчёты**  
   Оставить grand-pipeline-* как справочные; акцент на completion-pipeline и what-to-do-full; deep-research-report-* — как отчёты с пометкой «аудит/анализ», не заменяющие основные доки.

6. **ADR**  
   Проверить статусы (accepted/deprecated), при пересоставлении — индекс ADR в docs/adr/README.md с кратким описанием.

7. **Периодическое обновление**  
   Раз в квартал или после крупных релизов: roadmap-checklist, risk-register, implementation-assessment, what-to-do-full; раз в полгода — пересмотр списка «главных» доков в README и AGENTS.

---

## 7. Сводная таблица по приоритетам

| Приоритет | Количество (docs/ + ADR) | Действие при пересоставлении |
|-----------|---------------------------|------------------------------|
| **P0** | ~20 | Ядро: обновить, проверить ссылки, не удалять без замены. |
| **P1** | ~35 | Важные: проверить актуальность, при необходимости объединить с другими. |
| **P2** | ~45 | Полезные: оставить или свести в разделы «Справочник» / «История». |
| **P3** | ~10 | Бэклог/отчёты: хранить, не выносить в «главные» навигаторы. |

**Итого в docs/ и docs/adr/:** порядка 95 уникальных документов (без учёта дублей по смыслу). Для «перебрать всё и пересоставить» разумно начать с P0, затем P1, затем решить по P2 (объединение, архив, перенос в wiki/archive).
