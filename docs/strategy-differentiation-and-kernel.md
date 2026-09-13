# Стратегия: дифференциация и модель «Kernel + плагины»

Позиционирование Product Factory, три направления развития, модель «неубиваемого ядра» и анти-паттерны. Связь с этапами — [architecture-and-path.md](architecture-and-path.md). Рынок и конкуренты — [market-similar-solutions.md](market-similar-solutions.md).

---

## 1. Позиционирование: «фишка, которой почти ни у кого нет»

**Policy-driven multi-agent factory с доказуемыми артефактами**, где:

- агентам нельзя делать side-effects напрямую;
- любой эффект идёт через типизированные tools;
- OPA (или аналог) решает allow/deny/approval;
- каждый run даёт provenance/attestation;
- одно и то же ядро работает в local/CI/k8s.

Это объединяет лучшее из **IDP (golden paths)** + **DevSecOps** + **автономных SWE-агентов** при сохранении строгого контроля и воспроизводимости.

---

## 2. Три направления «сделать ещё круче»

### 2.1. Доказуемая безопасность и supply-chain

| Направление | Смысл |
|-------------|--------|
| **Fail-closed + attestation** | Каждое действие tool — OPA decision + inputs/outputs + хэш артефактов в неизменяемый журнал. |
| **Provenance артефактов (SLSA-подход)** | Для каждого build/test/release: кто запустил, окружение, зависимости, commit, сканеры, verdict. |
| **Hermetic runners** | Tools только внутри изолированного раннера (docker/k8s), без доступа к хосту, readonly FS по умолчанию, явные mount'ы. |
| **Secrets governance** | Секреты не «передаются агенту», а выдаются tools точечно (short-lived tokens) строго по policy. |

Сейчас в репо: fail-closed (`POLICY_FAIL_MODE`), audit log, OPA; нет полноценного attestation/SLSA и hermetic runners — см. этапы 3–4 в [architecture-and-path.md](architecture-and-path.md).

### 2.2. Автономность без безумия: агенты + верификаторы

| Идея | Описание |
|------|----------|
| **Два контура** | *Generator* (план/код/изменения) и *Verifier* (тесты, статический анализ, инварианты, diff-review). Решающее слово — у deterministic gates. |
| **Spec-first** | Агент сначала генерит исполняемую спецификацию (контракты, инварианты, property-based tests), потом код. |
| **Issue localization** | Отдельный этап ранжирования файлов/функций по вероятности дефекта (как в SWE-агентах). |
| **Debate / N-best plans** | Planner выдаёт несколько вариантов плана + риски/стоимость; policy выбирает по профилю (скорость/надёжность/комплаенс). |

Сейчас: один Planner, один Codegen; тесты и security — шаги (TestRunner, SecurityRunner). Отдельного Verifier-контура и spec-first — нет.

### 2.3. Платформа-уровень: окружения, UI, экономика

| Направление | Описание |
|-------------|----------|
| **IDP-портал + Golden Paths** | Каталог шаблонов/«дорожек», self-service создание сервиса/модуля/пайплайна (аналог Backstage Software Templates). |
| **Окружения как продукт** | local/ci/k8s/prod, разные policy-профили, бюджеты, allowlists tools. |
| **FinOps для агентов** | Бюджет токены/время/раннеры/сканеры, стоимость per run, лимиты параллелизма. |
| **Run UX** | Timeline прогонов, approvals, diff view, отчёты тестов/сканов, reason codes отказа policy. |

Сейчас: API и CLI; нет портала, нет явных профилей окружений, нет FinOps — см. этап 5 в [architecture-and-path.md](architecture-and-path.md).

---

## 3. Kernel-first: что нельзя ломать и обходить

Система как ОС: **ядро стабильно**, всё остальное — модули и адаптеры.

### 3.1. Kernel (обязательный минимум)

| Компонент | Назначение |
|-----------|------------|
| **Run model** | runId, state, events, artifacts, approvals, verdicts. |
| **Execution engine** | State machine / workflow runtime: idempotency, retries, timeouts. |
| **Policy gateway** | input → decision (allow/deny/approval) с reason codes. |
| **Tool runtime** | Единственный канал side-effects; sandboxed execution. |
| **Audit log + Artifact registry** | Append-only события; адресуемые артефакты. |
| **Contracts framework** | Schemas + versioning + compatibility rules. |

В репо: [WorkflowRunner](../src/main/kotlin/productfactory/workflow/WorkflowRunner.kt), [PolicyCheck](../src/main/kotlin/productfactory/policy/PolicyCheck.kt), [SandboxToolExecutor](../src/main/kotlin/productfactory/workflow/tools/SandboxToolExecutor.kt), [AuditLog](../src/main/kotlin/productfactory/workflow/AuditLog.kt), [ArtifactRegistry](../src/main/kotlin/productfactory/workflow/ArtifactRegistry.kt), `contracts/schemas/`.

### 3.2. Слои поверх Kernel (адаптеры, не замена ядра)

| Слой | Примеры |
|------|---------|
| **Adapter layer** | VCS (GitHub/GitLab), issue tracker (Jira/GitHub Issues), IDP (Backstage), chat (Slack/Telegram), secrets (Vault/K8s), storage (S3/MinIO). |
| **Runtime layer** | Где выполняются tools: local-sandbox, docker-runner, k8s-runner, remote-ci-runner (Actions/GitLab Runner как backend). |
| **Intelligence layer** | LLM-провайдеры, agent-planner, agent-codegen, agent-qa, agent-sec — все выдают только артефакты/предложения; исполняет kernel. |
| **Product layer** | CLI, Web UI (timeline, approvals, diffs, reports), Backstage plugin, «GitHub App / GitLab bot» (issue→PR). |

---

## 4. Контракты расширений (суть)

### 4.1. Контракт Tool

- `tool_name`, `input_schema`, `output_schema`
- `risk_tier` (safe / elevated / privileged)
- `required_approvals`, `resource_access` (fs/network/secrets)
- `idempotency_key_rules`, `allowed_runtimes` (local/ci/k8s)

**Tool Registry** — единственный источник правды; исполнитель читает реестр и не допускает «самовольщины». Сейчас: [contracts/tools.schema.json](../contracts/tools.schema.json); единый реестр с risk tier в runtime — в планах (этап 1, [architecture-and-path.md](architecture-and-path.md)).

### 4.2. Контракт Agent

- `agent_role`, `input_context_pack_schema`, `output_artifacts_schema`
- `allowed_tools` (решает policy, не агент)
- `cost_budget_profile`

---

## 5. «Оболочка» с максимальным вау-эффектом

**GitHub/GitLab Bot: Issue → PR (с доказательствами)**

- Пользователь открывает issue с goal + contracts.
- Бот создаёт run → planner → plan artifact → policy (approval при необходимости) → tool runtime создаёт ветку, применяет patch, запускает tests/security.
- Результат: **PR** с diff, test report, security report, policy decisions (reason codes), provenance (какие tools, версии, раннеры).

Такой UX воспринимается как «серебряная пуля»: один вход (issue), один выход (PR с доказательствами), без обхода контроля.

---

## 6. Анти-паттерны

| Не делать | Почему |
|-----------|--------|
| Агент сам решает и сам пушит | Исчезает governance, audit бессмысленен. |
| Жёстко привязывать ядро к конкретному VCS/CI/LLM | Теряется переносимость и смена «оболочек». |
| Хранить бизнес-логику в prompt'ах | Всё важное — в contracts + policy. |
| Позволять обходить tool runtime | Без этого attestation и контроль невозможны. |

См. также [prohibited-agent-actions.md](prohibited-agent-actions.md), [adr/0002-layer-boundaries.md](adr/0002-layer-boundaries.md).

---

## 7. Формула

**Kernel (deterministic) + Plugin contracts + Policy bundles + Runtimes + оболочки под рынок**

«Серебряная пуля» — не один супер-агент, а **неубиваемое ядро + строгие контракты + плагины/адаптеры**. Тогда GitHub/GitLab/Backstage/CI/LLM становятся сменными модулями без переписывания основы.

Дальнейшие шаги: конкретные extension points (интерфейсы Kotlin), пакеты PR под модульную структуру (kernel/, runtimes/, plugins/, tools/, agents/) — см. [architecture-and-path.md](architecture-and-path.md) (этапы 3–5) и [roadmap-checklist.md](roadmap-checklist.md).
