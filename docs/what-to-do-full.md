# Всё что нужно сделать — единый перечень

Сводный список задач: бэклог спринта, Grand Pipeline, открытые фазы. **Запуск пайплайнов** — в конце документа.

**Источники:** [sprint-full-backlog.md](sprint-full-backlog.md), [grand-pipeline-plan.md](grand-pipeline-plan.md), [grand-pipeline-tasks.md](grand-pipeline-tasks.md), [phases-task-list.md](phases-task-list.md), [completion-pipeline.md](completion-pipeline.md), [deep-research-report-9.md](deep-research-report-9.md).

---

## Сводка: что уже реализовано

**Проверка по коду (2026-02-27):** большая часть Grand Pipeline и бэклога уже есть в репозитории.

| Область | Реализовано |
|---------|-------------|
| **Intent** | LlmIntentClarification, LlmIntentGenerator, LlmIntentCandidatesGenerator; адаптивные вопросы и раннее завершение в IntentClarification; короткий свободный ответ (A/B + фраза до 180 символов); POST /intent/estimate, POST /experience/generate |
| **Runbook intent→run** | Секция в runbook.md, примеры curl, `scripts/run_intent_to_run.sh` (E2E в CI) |
| **SBOM/signature** | ToolStepResult.sbomVersion/signatureVersion, SupplyChainVersionResolver (tool → CI env → workspace), запись в ArtifactRegistry |
| **SLO** | Job slo-gate в ci.yml, slo_gate.py, slo_gate_evaluated в workflow |
| **Cost** | PeriodUsageStore (File/InMemory), проверка лимита перед run (run_rejected_cost_budget), CostBudgets.kt, cost-budgets.example.yaml |
| **Tenant** | tenantId в API, scopedRunId, изоляция runs/profiles/audit по tenant |
| **Preference profile** | ProfileStore (get/revoke/reset/setIncognito), API /factory/profiles |
| **Artifact manifest** | Схема, запись при stage/finish, artifact_manifest_written в audit, replay_from_manifest.sh |
| **Session / references** | sessionId, reference_ids в API и experience; сценарий «6 вариантов → выбор 2» в тестах |
| **ask_user** | AskUserStore, POST /factory/runs/{runId}/answer, FactoryAnswerApiTest, IntentExperienceApiTest (intent+ask_user flow) |
| **Тесты** | IntentExperienceApiTest, FactoryAnswerApiTest, FactoryRunTest стабильный |

**Остаётся сделать по приоритету:** онлайн-сигналы SLO/cost для promotion (S02), Prometheus/Grafana SLO/cost (10), ADR local-docker/remote-ssh (14–16), контур AI-risk (20), GitOps CD до prod (22–23), role registry (28), веб-форма решений (30), ADR/runbook/risk-register (37–38). **По [deep-research-report-9.md](deep-research-report-9.md):** AuthN/AuthZ для API (security boundary multi-tenancy), per-run budgets enforcement в workflow, OPA fail-closed по умолчанию в prod.

---

## 1. Полировка и операционка (P0)

| ID | Задача | Статус | Критерий приёмки |
|----|--------|--------|------------------|
| B01 | Health endpoint для образа фабрики | ✅ Сделано | GET /health, /health/ready, /health/neural — есть |
| B02 | Валидация контрактов в CI | ⚠️ Частично | Job в contracts-validation.yml есть; вызывается `contract-drift-guard`. При необходимости добавить шаг `product-factory validate` |
| B03 | FactoryRunTest стабилизировать | ✅ Сделано | Тест без @Ignore; e2e через Docker/CI live-run |
| B04 | Runbook: живые прогоны | ✅ Сделано | В runbook: curl для POST /factory/run, intent→experience→run, run_intent_to_run.sh, примеры ответов |

---

## 2. Factory 2.0 — LLM в Planner (P0)

| ID | Задача | Статус | Критерий приёмки |
|----|--------|--------|------------------|
| F01–F05 | LlmAgentPlanner, промпт, контур, наблюдаемость, тесты | ✅ Сделано | LlmAgentPlanner/LlmAgentCodegen при NEURAL_SERVICE_URL; audit; fallback на stub |

---

## 3. Audit и целостность (P1)

| ID | Задача | Статус |
|----|--------|--------|
| A01 | Обязательные digest в audit | 🔲 Проверить и задокументировать формат/дефолты |
| A02 | Hash-chain для audit (опционально) | 🔲 По необходимости |

---

## 4. SLO и cost (P1)

| ID | Задача | Статус |
|----|--------|--------|
| S01 | Черновик SLO и метрик cost | ✅ Есть slo-cost-draft, slo-ci-gate |
| S02 | Онлайн-сигналы SLO/cost для promotion | 🔲 Реализовать использование /metrics и реестра для promotion |
| S03 | Cost budgets и мониторинг cost per run | ⚠️ Частично | PeriodUsageStore + проверка перед run (429/403) и конфиг есть; дашборд/метрики Prometheus — при необходимости |

---

## 5. API и контракты (P1)

| ID | Задача | Статус |
|----|--------|--------|
| API01 | Расширить FactoryRunRequest (опционально) | 🔲 target_stack, output_artifacts, risk_profile, approval_mode, budget |

---

## 6. Supply chain и комплаенс (P1–P2)

| ID | Задача | Статус |
|----|--------|--------|
| SC01 | Supply-chain gate на образ фабрики | ✅ supply-chain-factory в ci.yml |
| SC02 | SLSA аттестации по политике | ✅ Документ и настройка |
| SC03 | Threat model: NIST AML, MITRE ATLAS | ⚠️ Базовый заполнен; углубить при необходимости |
| SC04 | Контур AI-risk (NIST AI RMF, ISO 42001) | 🔲 Процессные элементы, раздел в risk-register |
| SC05 | Каждый release с SBOM и подписью; high-risk — approval | ✅ Политика зафиксирована, release.yml + CI gate (`release-policy-gate`) |

---

## 7. Надёжность и качество (P1–P2)

| ID | Задача | Статус |
|----|--------|--------|
| Q01 | Offline eval RAGAs (faithfulness, relevance) | 🔲 При наличии RAG |
| Q02 | Eval для прогонов с LLM Planner | 🔲 Расширить датасеты/градацию |
| Q03 | Durable engine (Temporal/Argo) | ⚠️ Temporal опционально подключён (WS0); полная интеграция — в бэклоге |

---

## 8. RAG и память (P2)

| ID | Задача | Статус |
|----|--------|--------|
| RAG01 | RAG ingestion + pgvector, версионирование индекса | ✅ Реализовано: ingestion/status/activate + опциональный read-path в planner (off by default) |

---

## 9. Продуктивизация (P2)

| ID | Задача | Статус |
|----|--------|--------|
| P01 | GitOps CD до prod, canary | 🔲 Реализация или процедура |
| P02 | Третий архетип (data pipeline) | 🔲 По образцу catalog-service/web-app |
| P03 | Multi-tenancy + portal/CLI | ⚠️ Частично | tenantId в API, изоляция по tenant есть; отдельный portal/UI — не делался |
| P04 | Потребитель получает сервис без копипаста; rollback за минуты | 🔲 UX и runbook |

---

## 10. Документация (P0–P1)

| ID | Задача | Статус |
|----|--------|--------|
| D01 | Синхронизировать phases-task-list.md | ✅ Выполненные отмечены |
| D02 | Обновить implementation-assessment.md | ✅ Scale закрыта |

---

## 11. Grand Pipeline — 38 шагов (детальный пайплайн)

Полный список: [grand-pipeline-tasks.md](grand-pipeline-tasks.md). Ниже — сводка по блокам с **фактическим статусом по коду** (✅ = реализовано).

### Блок 1 — Intent: текст и аудио
- **1** LlmIntentClarification + промпт и fallback ✅
- **2** Адаптивные вопросы и раннее завершение ✅ (IntentClarification.buildQuestionPlan, shouldStopEarly)
- **3** Короткий свободный ответ в протоколе ✅ (resolveChoice: A/B + inferChoiceByKeywords до 180 символов)
- **4** LlmIntentGenerator и LlmIntentCandidatesGenerator ✅
- **5** Документация уточнения и аудио-слой 🔲 (аудио-слой не описан)

### Блок 2 — API intent/experience
- **6** Маршруты POST /intent/estimate и /experience/generate ✅
- **7** Runbook intent → factory run ✅ (runbook.md, run_intent_to_run.sh, CI)

### Блок 3–6 — SBOM, SLO, cost, окружения
- **8** Источник SBOM/signature в run ✅ (ToolStepResult, SupplyChainVersionResolver)
- **9** SLO CI gate в ci.yml ✅ (job slo-gate, slo_gate.py)
- **10** Prometheus rules и Grafana SLO/cost 🔲
- **11** Онлайн-сигналы для promotion 🔲
- **12** Хранение счётчиков за период ✅ (PeriodUsageStore)
- **13** Проверка лимита перед run и конфиг cost ✅ (run_rejected_cost_budget, COST_BUDGETS_PATH)
- **14** ADR local-docker и remote-ssh 🔲
- **15** Local-docker выполнение в контейнере 🔲
- **16** Remote-ssh runner или out of scope 🔲

### Блок 7–10 — RAG, комплаенс, GitOps, multi-tenancy
- **17** RAG ingestion + pgvector ✅
- **18** Offline eval RAGAs и датасеты 🔲
- **19** SLSA provenance и аттестации ✅ (в CI уже есть)
- **20** Контур AI-risk 🔲
- **21** Release с SBOM/подписью и high-risk approval ✅
- **22** GitOps CD до prod 🔲
- **23** Canary и откат 🔲
- **24** Изоляция по tenant ✅ (tenantId, scopedRunId, изоляция runs/profiles)
- **25** Portal/CLI self-serve ⚠️ (CLI есть; отдельный portal — нет)

### Блок 11–16 — Preference, WS1/2/3 v1, manifest, session
- **26** Хранилище профиля предпочтений ✅ (ProfileStore)
- **27** Обновление профиля по выборам и API сброс ✅ (reset, setIncognito, использование в experience)
- **28** Role registry и шаблоны по ролям 🔲
- **29** Провижининг и headless browser ✅ (задокументировано)
- **30** Веб-форма решений и спринт-поинты 🔲 (API decision-context/answer есть; отдельный UI — нет)
- **31** Контракт artifact_manifest.json ✅ (схема и запись есть)
- **32** Заполнение manifest в run и воспроизведение ✅ (writeManifest при stage/finish, replay_from_manifest.sh)
- **33** session в потоке и референсы 6 карточек ✅ (sessionId, reference_ids, валидация «2 из options»)

### Блок 17–18 — Тесты и документация
- **34** FactoryRunTest стабильный ✅
- **35** Тесты ask_user и intent API ✅ (FactoryAnswerApiTest, IntentExperienceApiTest)
- **36** E2E скрипт intent → run ✅ (run_intent_to_run.sh, в CI)
- **37** ADR 0011/0012 и implementation-assessment 🔲
- **38** Runbook полный и risk-register 🔲 (runbook богатый; актуализация по всем разделам — непрерывно)

---

## 12. Фазы 5–7 — открытые пункты (phases-task-list)

**Фаза 5:** RAGAs eval, онлайн-сигналы SLO/cost для promotion.  
**Фаза 6:** SLSA аттестации, контур AI-risk; политика «каждый release с SBOM и подписью; high-risk — approval» — выполнена.  
**Фаза 7:** GitOps CD до prod, cost budgets и мониторинг cost per run, multi-tenancy (опционально), сценарий «потребитель без копипаста; rollback за минуты».

---

# Запуск пайплайнов

## Пайплайн завершения (Completion Pipeline) — актуальный

Только оставшиеся задачи (16 шагов). План: [completion-pipeline.md](completion-pipeline.md). Чеклист: [completion-pipeline-tasks.md](completion-pipeline-tasks.md).

**Требования:** Codex CLI в PATH (или выполнять шаги вручную по промптам из скрипта).

```bash
# Из корня репозитория

# Просмотр шагов
python3 scripts/run_completion_pipeline.py --dry-run

# Полный пайплайн (Docker разрешён для сборки/тестов)
python3 scripts/run_completion_pipeline.py --allow-docker

# Один шаг (например 2 — Prometheus/Grafana)
python3 scripts/run_completion_pipeline.py --allow-docker --step 2

# Диапазон
python3 scripts/run_completion_pipeline.py --allow-docker --from-step 1 --to-step 5
```

Состояние: `scripts/.completion_pipeline_state.json`.

---

## Grand Pipeline (исторический, 38 шагов)

Многие шаги уже реализованы; для актуальной работы используйте **Пайплайн завершения** выше.

Бэклог: [grand-pipeline-plan.md](grand-pipeline-plan.md). Чеклист: [grand-pipeline-tasks.md](grand-pipeline-tasks.md).

**Требования:** Codex CLI в PATH (`codex exec`), репозиторий готов к коммитам.

```bash
# Из корня репозитория

# Просмотр шагов без выполнения
python3 scripts/run_grand_pipeline.py --dry-run

# Полный пайплайн (Docker разрешён для сборки/тестов)
python3 scripts/run_grand_pipeline.py --allow-docker

# Без коммитов
python3 scripts/run_grand_pipeline.py --allow-docker --no-commit

# Один шаг (например 9 — SLO CI gate)
python3 scripts/run_grand_pipeline.py --allow-docker --step 9

# Диапазон шагов
python3 scripts/run_grand_pipeline.py --allow-docker --from-step 1 --to-step 7
```

Состояние сохраняется в `scripts/.grand_pipeline_state.json`.

**Важно:** пайплайн вызывает Codex CLI (`codex exec`) для каждого шага. Если Codex возвращает 403 (нет доступа к API), шаги не выполнятся. В этом случае можно выполнять задачи вручную по списку выше или через другого агента (Cursor и т.д.), ориентируясь на промпты из `scripts/run_grand_pipeline.py` (STEPS_BUILTIN).

---

## Factory 2.0 Full Sprint (P0 + часть P1)

Бэклог: [sprint-full-backlog.md](sprint-full-backlog.md). Как запускать: [sprint-factory2-howto.md](sprint-factory2-howto.md).

```bash
# Просмотр шагов
python3 scripts/run_factory2_full_sprint.py --dry-run

# Полный спринт
python3 scripts/run_factory2_full_sprint.py --allow-docker

# Один шаг (например 2 — валидация контрактов в CI)
python3 scripts/run_factory2_full_sprint.py --allow-docker --step 2
```

---

## Другие спринты

- **Alpha:** `python3 scripts/run_alpha_sprint.py` (по умолчанию `--sandbox workspace-write`; для Docker: `--allow-docker`)
- **Beta:** `python3 scripts/run_beta_sprint.py --allow-docker`
- **MVP:** `python3 scripts/run_mvp_sprint.py --allow-docker`
- **Scale:** `python3 scripts/run_scale_sprint.py --allow-docker`

---

*Документ обновлён: 2026-02-27. Синхронизировать с roadmap-checklist и implementation-assessment после закрытия задач.*
