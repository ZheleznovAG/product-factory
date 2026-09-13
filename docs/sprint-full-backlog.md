# Полный бэклог: всё что предстоит сделать

Единый перечень задач для фабрики. Источники: [phases-task-list.md](phases-task-list.md), [whats-next.md](whats-next.md), [strategic-fork-factory-2.md](strategic-fork-factory-2.md), [implementation-assessment.md](implementation-assessment.md). Метки: `phase`, `risk-tier: low|medium|high`, `blocker` (блокирует другие).

Использование: перенос в Issues, ручной спринт или автоматизированный спринт [run_factory2_full_sprint.py](../scripts/run_factory2_full_sprint.py) (см. [sprint-factory2-howto.md](sprint-factory2-howto.md)).

---

## Условные обозначения

| Метка | Значение |
|-------|----------|
| phase: 3–7 | Номер фазы по phases-task-list |
| risk: L/M/H | low / medium / high |
| P0/P1/P2 | Приоритет для спринта: P0 — сначала, P2 — позже |

---

## Полировка и операционка (P0)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| B01 | Health endpoint для образа фабрики | GET /health и/или /health/ready возвращают 200; задокументировано в runbook | 3 | L | — |
| B02 | Валидация контрактов в CI | Job в .github/workflows (ci.yml или отдельный), запускающий `product-factory validate` над contracts или example-конфигами | 3 | L | — |
| B03 | FactoryRunTest: стабилизировать или задокументировать | Либо убрать @Ignore и починить testApplication (CWD/окружение), либо оставить @Ignore и добавить в implementation-assessment и комментарий в тесте явное описание замены (FactoryApprovalApiTest, Docker, CI) | 3 | M | — |
| B04 | Runbook: живые прогоны «запрос → артефакт» | Секция в docs/runbook.md с примерами curl для POST /factory/run и ожидаемым результатом; при необходимости скрипт scripts/run_live_example.sh | 4 | L | — |

---

## Factory 2.0 — LLM в Planner (P0)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| F01 | Реализация LlmAgentPlanner | Класс реализует AgentPlanner; использует NeuralServiceClient; вход AgentPlannerInput, выход AgentPlannerArtifacts; парсинг JSON с валидацией по contracts/agent_outputs.schema.json; temperature 0.2–0.3; таймаут 30s; при ошибке/таймауте — fallback на StubAgentPlanner или отказ с записью в audit | Scale | M | — |
| F02 | Промпт и ограничение tool surface | Системный промпт задаёт роль Planner и формат JSON; в контекст передаются goal, constraints, productSpec, список tools из реестра; валидация после парсинга: только tools из реестра | Scale | M | F01 |
| F03 | Внедрение Planner в контур | В Application.module (или месте создания WorkflowRunner): если задан NEURAL_SERVICE_URL — создаётся LlmAgentPlanner(neuralClient), иначе StubAgentPlanner; HttpNeuralServiceClient создаётся из env (NEURAL_SERVICE_URL, NEURAL_SERVICE_API_KEY) | Scale | L | F01 |
| F04 | Наблюдаемость Planner | В audit логируются: факт вызова Planner, latency, успех/fallback/ошибка; без полных промптов/ответов по умолчанию (ADR-0005); при необходимости дочерний OTel span на planner.generate | Scale | L | F01 |
| F05 | Тесты LlmAgentPlanner | Unit-тест с моком NeuralServiceClient (возвращает валидный JSON); проверка, что generate() возвращает корректные AgentPlannerArtifacts; при необходимости интеграционный тест с fallback при 5xx/таймауте | Scale | L | F01 |

---

## Audit и целостность (P1)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| A01 | Обязательные digest в audit | События, где требуются inputDigest/outputDigest (по roadmap-checklist), заполняются; при отсутствии — явная пометка или дефолт; документировано в формате audit log | 3 | L | — |
| A02 | Hash-chain для audit (опционально) | При необходимости: связь записей по хешу предыдущей для целостности; описание в ADR или runbook | 5 | M | A01 |

---

## SLO и cost (P1)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| S01 | Черновик SLO и метрик cost | Документ (или раздел в decision-points-v1/runbook): SLO (латентность run, успешность), метрики cost (токены, tool calls, wall-clock); как будут использоваться для решения о promotion | 5 | L | — |
| S02 | Онлайн-сигналы SLO/cost для promotion | Реализация сбора метрик в рантайме и использование при решении о promotion (авто или полуавто); опционально поля в API (budget) | 5 | M | S01 |
| S03 | Cost budgets и мониторинг cost per run | Лимиты по cost (токены, вызовы, время); дашборд или экспорт метрик (Prometheus/OTel); политика в OPA при необходимости | 7 | M | S01 |

---

## API и контракты (P1)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| API01 | Расширить FactoryRunRequest (опционально) | Опциональные поля: target_stack, output_artifacts, risk_profile, approval_mode, budget (токены, tool calls, wall clock); документировано в API и контрактах | Scale | L | — |

---

## Supply chain и комплаенс (P1–P2)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| SC01 | Supply-chain gate на образ фабрики | В ci.yml при push в main уже есть supply-chain-factory (build, push, SBOM, Trivy, Cosign); убедиться, что покрыто и задокументировано | 6 | L | — |
| SC02 | SLSA аттестации по политике | Настроить SLSA-уровни и аттестации (build provenance и т.д.) по политике; документ в docs/ | 6 | M | — |
| SC03 | Threat model: NIST AML, MITRE ATLAS | Углубить docs/threat_model.md — раздел по NIST AML, MITRE ATLAS или ссылки на таксономии; обновить таблицу угроз при необходимости | 6 | L | — |
| SC04 | Контур AI-risk (NIST AI RMF, ISO 42001) | Процессные элементы по NIST AI RMF и/или ISO 42001 (governance, риски ИИ); документ или раздел в risk-register | 6 | M | — |
| SC05 | Каждый release с SBOM и подписью; high-risk — approval | Закрепить политику: каждый релиз с SBOM и подписью; high-risk изменения — ручной approval; проверка в CI/gates | 6 | M | SC01 |

---

## Надёжность и качество (P1–P2)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| Q01 | Offline eval: RAGAs (faithfulness, relevance) | Метрики RAG-качества (RAGAs и др.) и прогоны в eval; при наличии RAG | 5 | M | RAG01 |
| Q02 | Eval для прогонов с LLM Planner | Датасеты или градация для сценариев с LLM Planner; eval-regression-gate учитывает изменения в planner/промптах | Scale | M | F01 |
| Q03 | Интегрировать durable engine (Temporal/Argo) | Workflow вынесен в Temporal или Argo; ретраи, история, восстановление после сбоев | 3/5 | H | — |

---

## RAG и память (P2)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| RAG01 | RAG ingestion + pgvector, версионирование индекса | Загрузка контекста в pgvector, версионирование индекса; использование в планировщике/кодогене опционально; decision-points-v1: RAG off by default | 4 | M | — |

---

## Продуктивизация (P2)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| P01 | GitOps CD до prod, canary | Прод-деплой через GitOps (Argo CD и т.п.); canary по необходимости; runbook | 7 | M | — |
| P02 | Третий архетип (data pipeline) | По образцу catalog-service и web-app: код, CI, supply-chain-gate, runbook | 7 | M | — |
| P03 | Multi-tenancy + portal/CLI (опционально) | Поддержка нескольких тенантов; self-serve портал или CLI | 7 | H | — |
| P04 | Потребитель получает сервис без копипаста; rollback за минуты | UX: запрос → артефакт/сервис; откат за минуты через GitOps revert; процедуры в runbook | 7 | M | P01 |

---

## Документация и синхронизация (P0–P1)

| ID | Задача | Критерий приёмки | phase | risk | Зависимости |
|----|--------|------------------|-------|------|-------------|
| D01 | Синхронизировать phases-task-list.md | Отметить выполненные пункты MVP и фазы 5 (eval gate и т.д.); актуальные [ ] для оставшихся | — | L | — |
| D02 | Обновить implementation-assessment.md | Текущее состояние: Alpha/Beta/MVP закрыты, Scale ~70%; опционально обновить про threat_model и supply-chain | — | L | — |

---

## Сводка по приоритетам спринта

**Сначала (P0):** B01–B04, F01–F05, D01–D02.  
**Затем (P1):** A01, S01, API01, SC01, SC03, Q02.  
**Позже (P2):** Temporal, RAG, SLSA, AI-risk контур, прод, архетип data-pipeline, multi-tenancy.

Автоматизированный спринт [run_factory2_full_sprint.py](../scripts/run_factory2_full_sprint.py) покрывает в первую очередь P0 и часть P1. Полный список шагов — в [sprint-factory2-howto.md](sprint-factory2-howto.md).
