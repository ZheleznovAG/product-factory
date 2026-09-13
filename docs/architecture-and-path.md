# Архитектура и путь к целевой системе

Один документ-карта: принципы, целевые сущности, этапы с отметками готовности и ссылками на детали. Детальное «что сейчас / почему маленькая / куда растем» — [architecture-and-vision.md](architecture-and-vision.md). Roadmap мульти-агентов, окружений и Intent Layer — [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md).

---

## 1. Принципы (конституция)

Не обсуждаются — база для всех решений.

| Принцип | Смысл |
|--------|--------|
| **Deterministic > Non-deterministic** | Всё, что меняет мир, идёт через tools и политики. Агент только предлагает; исполнение — в Control Plane. |
| **Everything is a run** | Любой прогон имеет `runId`, audit trail, артефакты, статусы. Воспроизводимость и расследование по run. |
| **Policy-first** | Если policy недоступна — поведение задано явно (`POLICY_FAIL_MODE=open\|closed`), не «как повезёт». |
| **Typed contracts** | Вход/выход агентов и tools формализованы (schema). Иначе нельзя встраивать в pipeline и политики. |

Источники в репо: [ADR-0002](adr/0002-layer-boundaries.md), [solution_design.md](solution_design.md), [approval-policy.md](approval-policy.md).

---

## 2. Видение: роли, агенты, окружения

### 2.1. Основные сущности (domain model)

| Сущность | Описание |
|----------|----------|
| **Run** | Единица исполнения: request → plan → actions → artifacts. Есть в коде: `WorkflowRunner`, `runId`, состояния. |
| **Environment** | Контекст выполнения: local / CI / k8s. Пока не first-class; целевая модель — профили (tools, budgets, изоляция). |
| **Role** | Кто выполняет шаг: агентная роль или человеческая. См. ниже. |
| **Artifact** | Результаты: repo, image, SBOM, отчёты, ADR. Реестр в коде: `ArtifactRegistry`, audit. |
| **Policy** | Правила: OPA + бюджеты + approvals. Реализовано: `PolicyCheck`, `policies/opa/`. |
| **Tool** | Единственный канал side-effects. Реализовано: `SandboxToolExecutor`, контракт `contracts/tools.schema.json`. |

### 2.2. Роли агентов (целевой набор)

Смысл — 5–7 ролей с максимальным эффектом при едином контроле. Мульти-агентность — контрактная и процессная модель; роли могут быть реализованы **одним** LLM с разными prompt-шаблонами и policy-профилями.

| № | Роль | Назначение | Сейчас в репо |
|---|------|------------|----------------|
| 1 | **Orchestrator (Control Plane)** | State machine, retries, timeouts, idempotency, audit. Не «умный», а строгий. | ✅ WorkflowRunner, WorkflowStateMachine |
| 2 | **Planner Agent** | goal + contracts → pipeline_plan (шаги, артефакты, инструменты). | ✅ LlmAgentPlanner / StubAgentPlanner |
| 3 | **Codegen Agent** | Генерит patch-set; не применяет сам — только через tool. | ✅ LlmAgentCodegen / StubAgentCodegen |
| 4 | **QA Agent** | Test plan + запуск/интерпретация тестов через tools. | ⚠️ Шаг run_tests (TestRunner); отдельного агента нет |
| 5 | **Security Agent** | Инициирует SAST/secret/dependency scan, risk report. | ⚠️ Шаг run_security_checks (SecurityRunner); отдельного агента нет |
| 6 | **Release Agent** | Билд image, release manifests, выкат в окружение (через tools + approvals). | ❌ Нет |
| 7 | **Observability Agent** | Метрики/логи/трейсы, регресс, verdict. | ❌ Нет (есть только метрики фабрики) |

### 2.3. Окружения (целевая тройка)

Один и тот же run должен корректно воспроизводиться в разных контекстах.

| Окружение | Назначение |
|-----------|------------|
| **Local Sandbox** | Ноутбук разработчика: быстрые прогоны, максимум safety, минимум внешних интеграций. |
| **CI Runner** | Репозиторий/организация: детерминированные gates, воспроизводимость, отчёты, артефакты. |
| **K8s Execution Cluster** | Платформа: масштаб, очереди, параллельные runs, изоляция, секреты, cost control. |

Сейчас явных профилей окружений нет; окружения — следующий этап (см. раздел 4).

### 2.4. Роли людей (human-in-the-loop)

| Роль | Назначение |
|------|------------|
| **Requester** | Создал run. |
| **Approver** | Подтверждает привилегированные tools (создание репо, деплой, секреты). Реализовано: ApprovalStore, API approve/reject. |
| **Owner** | Отвечает за продукт/артефакт. |
| **Auditor** | Читает журнал, может ограничить класс действий через policy. |

В коде явно есть только Approver; остальные — для политик и будущего UI.

---

## 3. Архитектура: слои и поток

Детали и mermaid-схема — [solution_design.md](solution_design.md). Кратко:

1. **API Gateway / Factory API** — контракты, создание run, статусы, approvals, артефакты.
2. **Execution Core (Control Plane)** — state machine, планирование шагов, retries, idempotency, audit.
3. **Policy Engine (OPA + бюджеты + approvals)** — allow/deny/require_approval + reason codes.
4. **Tool Runtime** — типизированные tools, least privilege, изоляция (fs/docker/k8s).
5. **Intelligence Plane** — planner/codegen/qa/sec как «мозги»; только предложения, side-effects через tools.
6. **Artifact & Audit** — реестр, хранилище (S3/MinIO), логи, provenance.
7. **Observability** — метрики/трейсы/логи по runId.

**Поток:** Run creation → Plan → Policy → Exec → Gates (tests, security) → Stage → Finish. Реализация: [WorkflowRunner](../src/main/kotlin/productfactory/workflow/WorkflowRunner.kt).

### Схема (текстовая)

```
Client → Factory API → Run(created)
                |
                v
        Execution Core (workflow / state machine)
                |
    +-----------+-------------------+
    |                               |
    v                               v
Policy Engine (OPA)            Intelligence Plane
allow/deny/approval             (planner / codegen / qa / sec)
    |                               |
    +---------------+---------------+
                    v
            Tool Runtime (sandbox / runners)
                    |
                    v
         Artifacts (S3/registry) + Audit Log
                    |
                    v
             Observability (metrics / traces)
```

---

## 4. Путь: этапы, критерии, статус

Лестница этапов. Каждый шаг даёт прирост и остаётся совместимым с текущим репо.

### Этап 1. «Сделать безопасность реальной» — **сделан**

| Задача | Статус | Где |
|--------|--------|-----|
| Policy fail-mode `POLICY_FAIL_MODE=open\|closed` | ✅ | [PolicyCheck](../src/main/kotlin/productfactory/policy/PolicyCheck.kt), [runbook](runbook.md#opa-политики) |
| Token/cost wiring в policy input | ✅ | WorkflowRunner: `accumulatedTokenUsage`, передача в OPA; [AgentPlannerResult](../src/main/kotlin/productfactory/agent/AgentPlanner.kt), [AgentCodegenResult](../src/main/kotlin/productfactory/agent/AgentCodegen.kt) |
| Tool registry как single source of truth | ✅ | [contracts/tools.registry.json](../contracts/tools.registry.json), [ToolRegistry](../src/main/kotlin/productfactory/workflow/tools/ToolRegistry.kt); executor использует реестр как allowlist; OPA data.json синхронизирован с реестром (см. [runbook](runbook.md#opa-политики)). |

**Критерий этапа:** нельзя выполнить privileged tool без policy+approval; при падении OPA поведение предсказуемо. **Выполнен.**

---

### Этап 2. «Quality gates вместо заглушек» — **сделан**

| Задача | Статус | Где |
|--------|--------|-----|
| Реальный run_tests (gradle/junit, отчёт в audit) | ✅ | [TestRunner](../src/main/kotlin/productfactory/workflow/TestRunner.kt), шаг run_tests в WorkflowRunner |
| Реальный run_security_checks (gitleaks, trivy, минимум) | ✅ | [SecurityRunner](../src/main/kotlin/productfactory/workflow/SecurityRunner.kt), шаг run_security_checks |
| Verdict model (PASS/WARN/FAIL + reasons + ссылки на артефакты) | ✅ | [GateVerdict](../src/main/kotlin/productfactory/workflow/GateVerdict.kt); тесты/security возвращают verdict, audit пишет verdict в payload |

**Критерий этапа:** run автоматически FAIL при красных тестах/секретах. **Выполнен.**

---

### Этап 3. «Окружения и изоляция» — **в работе**

| Задача | Статус |
|--------|--------|
| Профили окружений: `local`, `ci`, `k8s` (выбор через FACTORY_ENV, запись в audit) | ✅ Базово: [FactoryEnvironment](../src/main/kotlin/productfactory/config/Environment.kt), [runbook](runbook.md#профиль-окружения-factory_env). Дальше: привязка бюджетов/tools к профилю. |
| Изоляция раннеров: dockerized tool executor без доступа к хосту | ✅ Базово: [DockerToolExecutor](../src/main/kotlin/productfactory/workflow/tools/DockerToolExecutor.kt), образ [deploy/Dockerfile.tool-runner](../deploy/Dockerfile.tool-runner); create_repo_from_archetype и apply_patch в контейнере; при недоступности Docker — fallback на sandbox. [runbook](runbook.md#изолированный-запуск-tools-docker-runner). |
| Secret handling: policy запрещает утечки; tools получают секреты по allowlist | ✅ [allowed_secrets](contracts/tools.registry.json) в реестре; [SecretProvider](../src/main/kotlin/productfactory/workflow/tools/SecretProvider.kt), [OPA правило](policies/opa/rego/factory.rego) deny по argument_keys (token/password/secret/api_key). [runbook](runbook.md#секреты-allowlist-и-политика). |

**Критерий:** один и тот же run корректно воспроизводится в local и в CI.

---

### Этап 4. «Мульти-агенты как роли» — **в работе**

| Задача | Статус |
|--------|--------|
| Agent contracts: planner.output, codegen.output, qa.output, sec.output | ✅ Типы заданы: [QaGateOutput](../src/main/kotlin/productfactory/workflow/QaSecOutputs.kt), [SecGateOutput](../src/main/kotlin/productfactory/workflow/QaSecOutputs.kt); pipeline заполняет их в run_tests/run_security_checks. |
| Шаги pipeline: «agent task» → «policy check» → «tool tasks» | Частично |
| Memory layer на уровне run: context pack, knowledge base по артефактам | ✅ Базово: [RunContext](../src/main/kotlin/productfactory/workflow/RunContext.kt) — runId, plannerArtifacts, codegenArtifact, qaOutput, secOutput; WorkflowRunner обновляет контекст по шагам. Протокол handoff: [artifact-handoff-protocol.md](artifact-handoff-protocol.md). |

**Критерий:** разные роли дают отдельные артефакты и причины решений; всё проверяется policy.

---

### Этап 5. «Платформа как продукт» — **долгосрочно**

| Направление | Описание |
|-------------|----------|
| UI/Console | Runs, approvals, diffs, отчёты, сравнение прогонов. |
| Multi-tenancy | Проекты/команды/политики по namespaces. |
| Policy-as-code | Review/merge политик как репозиторий. |
| Cost governance | Бюджеты по команде/run, лимиты параллелизма. |

---

## 5. Где что почитать

| Тема | Документ |
|------|----------|
| Текущая система и ограничения, путь к «крутой» | [architecture-and-vision.md](architecture-and-vision.md) |
| Control vs Intelligence, компоненты, mermaid | [solution_design.md](solution_design.md) |
| План по фазам и чек-лист | [roadmap-checklist.md](roadmap-checklist.md) |
| Workstreams WS0–WS4, варианты A/B, Intent Layer | [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md) |
| Запуск, OPA, approvals, переменные | [runbook.md](runbook.md) |
| Границы слоёв, запреты агента | [adr/0002-layer-boundaries.md](adr/0002-layer-boundaries.md), [prohibited-agent-actions.md](prohibited-agent-actions.md) |
| Позиционирование, Kernel + плагины, анти-паттерны | [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md) |
| Рынок: классы решений, сильнее/слабее PF | [market-similar-solutions.md](market-similar-solutions.md) |

Итог: принципы и целевые сущности зафиксированы здесь; этапы 1–2 закрыты; этапы 3–4 в работе; этап 5 и roadmap workstreams (мульти-агенты, окружения, человеческий контур, Intent Layer) задают следующий путь без переписывания ядра. Формула: **Kernel (deterministic) + Plugin contracts + Policy bundles + Runtimes + оболочки под рынок** — см. [strategy-differentiation-and-kernel.md](strategy-differentiation-and-kernel.md).
