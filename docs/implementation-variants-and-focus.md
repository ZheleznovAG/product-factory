# Варианты реализации и фокус документации

Обзор всех описанных в проекте вариантов развития, их взаимосвязей и того, что в фокусе, а что могло устареть или затеряться. Документ сформирован по состоянию на 2026-02.

---

## 1. Сводка: что есть сейчас

| Аспект | Текущее состояние |
|--------|-------------------|
| **Фазы** | Alpha, Beta, MVP, Scale — **закрыты** (roadmap-checklist, implementation-assessment). |
| **Ядро** | Factory 1.0 = deterministic template engine; при `NEURAL_SERVICE_URL` — LLM в Planner и Codegen (гибрид Factory 2.0). |
| **Workflow** | In-memory (WorkflowRunner); опционально Temporal при `TEMPORAL_ADDRESS` (runbook). |
| **Спринты-оркестры** | alpha, beta, mvp, scale (по фазам), **run_factory2_full_sprint** (полировка + LLM + доки), **run_grand_pipeline** (58 задач по roadmap-workstreams). |

---

## 2. Варианты реализации (где описаны)

### 2.1. Стратегическая развилка Factory 1.0 → 2.0

**Документ:** [strategic-fork-factory-2.md](strategic-fork-factory-2.md).

| Путь | Суть | Статус |
|------|-----|--------|
| **1. Консервативный** | Закрепить ядро: digest, hash-chain, SLO/cost, supply chain, Temporal. Без «AI-multiplication». | Не выбран. |
| **2. Агрессивный** | LLM везде, полная генерация решений и кода. | Не выбран (риск неконтролируемой агентности). |
| **3. Гибрид** | LLM только в Planner (и Codegen); архетипный/diff Codegen; trace-grading и eval gate. | **Выбран и реализован.** |

Текущий код соответствует пути 3: LlmAgentPlanner и LlmAgentCodegen при `NEURAL_SERVICE_URL`, те же Tool Executor и policy.

---

### 2.2. Два варианта roadmap после WS0 (ветка 1)

**Документ:** [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md).

Предполагается: **сначала закрыть WS0 (Kernel Hardening)**, затем выбрать A или B.

| Вариант | Порядок workstreams | Плюс | Минус |
|---------|---------------------|------|--------|
| **A** | WS0 → **WS1** (мульти-агенты) → **WS3** (human loop) → WS2 (окружения) | Быстрее «мини-команда» (роли + человек в контуре). | Intent → Reality откладывается. |
| **B** | WS0 → **WS4** (Intent Layer MVP) → WS3 → WS1 (минимально) | Сразу минимальный «Intent → Reality» (intent.yaml, протокол 90s). | Мульти-агенты проще на старте. |

**WS0 (обязательная база):** persist run, retry, идемпотентность, e2e-стенд (в т.ч. опционально Temporal). В репо уже есть задел: идемпотентность, compose, при `TEMPORAL_ADDRESS` — Temporal Worker.

**Пять workstreams:**

- **WS0** — Kernel Hardening (persist, retry, e2e).
- **WS1** — Multi-Agent Roles (Planner, Implementer, Tester, Reviewer; протокол артефактов).
- **WS2** — Environments (local-docker, remote-ssh, позже провижининг).
- **WS3** — Human Loop (ask_user, вопросы/выборы, API, позже веб/мессенджер).
- **WS4** — Intent Layer (intent.yaml, протокол 90s, build-mode как единственный рантайм MVP).

Выбор A или B в документах **нигде явно не зафиксирован**; оба варианта остаются опциями после WS0.

---

### 2.3. Этапы в architecture-and-path

**Документ:** [architecture-and-path.md](architecture-and-path.md).

| Этап | Содержание | Статус |
|------|------------|--------|
| 1 | Безопасность (policy fail-mode, token/cost в OPA, tool registry) | Сделан |
| 2 | Quality gates (run_tests, run_security_checks, verdict) | Сделан |
| 3 | Окружения и изоляция (профили, dockerized executor, секреты) | В работе (базово есть) |
| 4 | Мульти-агенты как роли (контракты, RunContext, шаги по ролям) | В работе (частично) |
| 5 | Платформа как продукт (UI, multi-tenancy, policy-as-code, cost) | Долгосрочно |

Этапы 1–2 закрыты; 3–4 — «в работе» с частичной реализацией; 5 — целевое видение.

---

### 2.4. Архетипы: три направления на будущее

**Документ:** [archetypes-and-roadmap.md](archetypes-and-roadmap.md).

| Направление | Суть |
|-------------|------|
| 1. Больше архетипов | Ручное добавление (data-pipeline и т.д.), фабрика только выбирает и копирует. |
| 2. Apply patch | Tool «применить patch»: codegen_patch_set применяется к скопированному архетипу (кастомизация под goal). |
| 3. Генерация из intent | Репо создаётся не копированием архетипа, а генерацией/применением с нуля. |

Сейчас реализовано только направление 1 (фиксированные архетипы + выбор + копирование). Патчи кодогена пока только в audit, не применяются к репо.

---

## 3. Спринты и бэклоги

| Скрипт / бэклог | Назначение | Связь с вариантами |
|------------------|------------|---------------------|
| **run_alpha_sprint.py** | Контракты, валидатор, risk tiers, ADR. | Фаза Alpha (закрыта). |
| **run_beta_sprint.py** | State machine, audit, OPA, tool registry, sandbox. | Фаза Beta (закрыта). |
| **run_mvp_sprint.py** | Архетип catalog-service, CI, staging, supply-chain. | Фаза MVP (закрыта). |
| **run_scale_sprint.py** | LLM в Planner/Codegen, approvals, eval gate. | Фаза Scale (закрыта). |
| **run_factory2_full_sprint.py** | Полировка (health, CI validate, runbook), LlmAgentPlanner/Codegen, доки (phases, implementation-assessment, SLO, threat model). | Текущий «полный» спринт по гибриду Factory 2.0; 12 шагов. |
| **run_grand_pipeline.py** | 58 задач по roadmap-workstreams: Intent (LLM), API intent/experience, SLO, cost budgets, окружения, RAG/eval, комплаенс, GitOps CD и др. | См. [grand-pipeline-plan.md](grand-pipeline-plan.md). |
| **sprint-full-backlog.md** | Единый бэклог с ID (B01–B04, F01–F05, A01, S01, API01, SC*, Q*, RAG*, P* и т.д.), P0/P1/P2. | Питает run_factory2_full_sprint. |

---

## 4. Что может быть устаревшим или несинхронизированным

- **solution_design.md:** Упоминает «Temporal или аналог» как основной Workflow Engine; в реальности основной путь — in-memory WorkflowRunner, Temporal опционален. Соответствует видению, но не текущему дефолту.
- **phases-task-list.md:** Часть пунктов (Temporal, RAG, SLO/cost) помечена как невыполненные; при этом roadmap-checklist и implementation-assessment считают Scale закрытой. Нужна явная пометка «фаза закрыта по критериям приёмки, отдельные пункты — бэклог».
- **ADR-0001 (stack-and-prerequisites):** Стоит проверить, что выбранный стек (Temporal или аналог) согласован с текущей политикой «Temporal опционален».
- **automated-development-model.md:** Упоминается в solution_design как источник этапов; не проверялась актуальность списка этапов относительно roadmap-workstreams и этапов 1–5.

---

## 5. Что затерялось или не в фокусе

- **run_grand_pipeline.py** — не упомянут в AGENTS.md и в scripts/README.md. Тот, кто ориентируется только на AGENTS.md, не узнает про вариант A (WS1→WS3→WS2).
- **Явный выбор варианта A или B** — в roadmap-workstreams описан порядок «сначала WS0, потом A или B», но решения «мы идём по A» или «по B» нигде не зафиксировано. Grand pipeline по шагам реализует скорее вариант A.
- **WS4 (Intent Layer)** — в текущем фокусе фабрики (Scale закрыт, полировка, Factory 2.0) Intent Layer и протокол 90s почти не фигурируют в приоритетных задачах; они остаются как поздние шаги roadmap.
- **Вариант B (сначала Intent)** — упомянут только в roadmap-workstreams; ни спринтов, ни отдельного бэклога под «сначала WS4» нет.
- **RAG / pgvector** — в бэклоге как P2/RAG01; в strategic-fork и в текущих приоритетах явно отложен («когда будет реальная генерация сложных систем»). Может казаться «затерянным», хотя решение — сознательное отложение.

---

## 6. Рекомендуемая навигация по документам

| Цель | Документы |
|------|-----------|
| Понять текущее состояние и что считать сделанным | [roadmap-checklist.md](roadmap-checklist.md), [implementation-assessment.md](implementation-assessment.md) |
| Понять стратегию (гибрид, что не делаем) | [strategic-fork-factory-2.md](strategic-fork-factory-2.md), [factory-2-planner-integration.md](factory-2-planner-integration.md) |
| Понять все варианты развития (A/B, workstreams) | [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md), [architecture-and-path.md](architecture-and-path.md) |
| Запустить текущий «полный» спринт (полировка + Factory 2.0) | [sprint-full-backlog.md](sprint-full-backlog.md), [sprint-factory2-howto.md](sprint-factory2-howto.md), `run_factory2_full_sprint.py` |
| Запустить grand pipeline (58 задач по roadmap-workstreams) | [grand-pipeline-plan.md](grand-pipeline-plan.md), `run_grand_pipeline.py` |
| Конституция и границы | [architecture-and-path.md](architecture-and-path.md) (принципы), [adr/0002-layer-boundaries.md](adr/0002-layer-boundaries.md), [solution_design.md](solution_design.md) |

---

## 7. Краткие выводы

1. **Варианты реализации:** стратегически выбран гибрид (Factory 2.0); по roadmap после WS0 возможны ветка A (мульти-агенты + human loop) или B (Intent Layer MVP); явного выбора A/B в документах нет.
2. **В фокусе сейчас:** полировка (health, CI, runbook), уже подключённые LLM Planner/Codegen, документация (phases, implementation-assessment), при необходимости — задачи из sprint-full-backlog (P1/P2).
3. **Grand pipeline:** отдельная линия (run_grand_pipeline + grand-pipeline-plan), по сути вариант A после WS0; в основных точках входа (AGENTS.md, scripts/README) не указана.
4. **Могло устареть:** solution_design (акцент на Temporal как основном engine), части phases-task-list (разрыв с формулировкой «Scale закрыта»).
5. **Затерялось в фокусе:** явный выбор A/B, run_grand_pipeline в главной документации, приоритизация WS4 (Intent) при желании идти по варианту B.

Если нужно, можно добавить в AGENTS.md и scripts/README упоминание grand pipeline и явно зафиксировать «текущий приоритет: полировка + Factory 2.0; опционально — grand pipeline по варианту A».
