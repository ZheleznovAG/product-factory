# Что ещё предстоит сделать

Полный перечень незакрытых задач по фазам и приоритетам. Основа: [phases-task-list.md](phases-task-list.md), [implementation-assessment.md](implementation-assessment.md), [roadmap-checklist.md](roadmap-checklist.md).

**Стратегическая развилка (Factory 1.0 → 2.0):** [strategic-fork-factory-2.md](strategic-fork-factory-2.md). Реализован гибрид: LLM для Planner и Codegen при `NEURAL_SERVICE_URL`; план и статус — [factory-2-planner-integration.md](factory-2-planner-integration.md).

**Полный бэклог и автоматизированный спринт:** [sprint-full-backlog.md](sprint-full-backlog.md) — все задачи с ID и приоритетами; [sprint-factory2-howto.md](sprint-factory2-howto.md) — как запустить `python3 scripts/run_factory2_full_sprint.py` (12 шагов).

---

## Фаза 3 (основание платформы) — остаток

| Задача | Описание |
|--------|----------|
| **Интегрировать workflow engine (Temporal или аналог)** | Сейчас workflow синхронный в памяти; для durable execution, ретраев и отказоустойчивости — подключить Temporal (или Argo Workflows и т.п.). |

---

## Фаза 4 (MVP) — остаток

| Задача | Описание |
|--------|----------|
| **RAG ingestion + pgvector, версионирование индекса** | Настроить загрузку контекста в векторное хранилище (pgvector), версионирование индекса для использования в планировщике/кодогене (опционально для MVP). |

---

## Фаза 5 (надёжность и качество)

| Задача | Описание |
|--------|----------|
| **Offline eval: RAGAs (faithfulness, relevance)** | Ввести метрики RAG-качества (RAGAs и др.) и прогоны в eval. |
| **Интегрировать durable engine (Temporal/Argo) в основной контур** | То же, что в фазе 3 — вынести выполнение шагов в durable engine. |
| **Черновик SLO и метрик cost для решения о promotion** | Определить SLO (латентность, успешность run) и метрики cost (токены, tool calls, wall-clock) для решения о продвижении артефакта. |
| **Онлайн-сигналы SLO/cost для promotion** | Реализовать сбор и использование SLO/cost в рантайме для автоматического или полуавтоматического promotion. |

---

## Фаза 6 (supply chain и комплаенс)

| Задача | Описание |
|--------|----------|
| **Аттестации (SLSA) по политике** | Настроить SLSA-уровни и аттестации по политике (build provenance и т.д.). |
| **Threat modeling (NIST AML, MITRE ATLAS)** | Углубить [threat_model.md](threat_model.md) по NIST AML, MITRE ATLAS (сейчас — базовая структура и risk register). |
| **Контур AI-risk (NIST AI RMF, ISO 42001)** | Завести процессные элементы по NIST AI RMF и/или ISO 42001 (governance, риски ИИ). |
| **Каждый release с SBOM и подписью; high-risk — ручной approval** | Добиться, чтобы каждый релиз имел SBOM и подпись (для фабрики и архетипов уже есть supply-chain gate); закрепить политику: high-risk изменения требуют ручного approval. |

---

## Фаза 7 (продуктивизация)

| Задача | Описание |
|--------|----------|
| **GitOps CD до prod, canary (по необходимости)** | Настроить прод-деплой через GitOps (Argo CD и т.п.), при необходимости — canary. |
| **Cost budgets и мониторинг cost per run** | Ввести лимиты по cost (токены, вызовы, время) и дашборд/метрики cost per run. |
| **Третий архетип (data pipeline) — по необходимости** | Добавить архетип data-pipeline по образцу catalog-service и web-app (код, CI, runbook). |
| **Multi-tenancy + portal/CLI для self-serve (опционально)** | Поддержка нескольких тенантов и портал/CLI для самостоятельного запроса сервисов. |
| **Потребитель получает сервис без копипаста; rollback за минуты** | Достичь целевого UX: запрос → артефакт/сервис без ручного копипаста; откат за минуты через GitOps revert (процедуры частично есть в runbook). |

---

## Масштабирование и AI (Scale) — закрыта

| Задача | Статус |
|--------|--------|
| **Подключить LLM к Planner и Codegen** | **Выполнено.** LlmAgentPlanner и LlmAgentCodegen при `NEURAL_SERVICE_URL`; gateway в `scripts/neural_gateway/`; audit agent_planner_call, agent_codegen_call. |
| **Расширить API контракт (опционально)** | target_stack, budget уже в FactoryRunRequest; при необходимости — output_artifacts, risk_profile, approval_mode. |

---

## Полировка и операционка

| Задача | Описание |
|--------|----------|
| **FactoryRunTest: убрать @Ignore или зафиксировать замену** | Либо починить нестабильный тест в testApplication (CWD/окружение), либо явно оставить @Ignore и описать замену (FactoryApprovalApiTest, Docker, CI) — частично уже в комментарии и implementation-assessment. |
| **Health endpoint для образа фабрики** | **Есть:** GET /health, /health/ready, /health/neural (проверка нейросервиса). См. runbook. |
| **Живые прогоны «запрос → артефакт»** | Регулярные прогоны сценария «запрос → создание репо/артефакт» и фиксация в runbook (примеры curl, ожидаемый результат). |
| **Валидация контрактов в CI** | **Есть:** job validate-contracts в [ci.yml](../.github/workflows/ci.yml) — при изменении contracts/ и при push; валидация contracts-example (или contracts/). |

---

## Сводка по приоритетам

**Ближайшие (низкий порог входа):**  
health/health/ready/health/neural есть; живые прогоны в runbook и `./scripts/run_factory_with_neural.sh`; валидация контрактов в CI (validate-contracts); FactoryRunTest с @Ignore (e2e через Docker).

**Средний приоритет:**  
cost budgets и SLO, расширение API при необходимости.

**Крупные блоки:**  
Temporal/durable engine, RAG/pgvector, SLSA и углублённый threat modeling, GitOps CD до prod, multi-tenancy/self-serve.

Обновлять по мере закрытия пунктов в [phases-task-list.md](phases-task-list.md) и [implementation-assessment.md](implementation-assessment.md).
