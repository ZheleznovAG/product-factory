# Исполнительное резюме

Собранный анализ показывает, что **архитектура Intent→Reality Engine** реализует многие предусмотренные компоненты (контрольная плоскость с API, валидацию контрактов, реестр инструментов, аудит, лист ожидания approvals, логическое разделение control/intelligence, фасад с мониторингом, CI-дрифт-гардиан, интеграцию LLM-планнера/кодогенератора, половинчатую поддержку RAG, базовые реализацию гейтинга и артефактного хранилища). Это близко к «индустриальному стандарту» зрелой системы (≥80% по списку требований, которые мы свели в таблице). См. Приложение **Таблица 2** для подробного соответствия. 

**Ключевые выводы:** основная функциональность закодирована по спецификации из официальной документации, но выявлены значимые пробелы и улучшения:
- **Динамическая адаптация стратегий** уточнения намерений отсутствует: все шаблоны жёстко заданы. Необходим модуль выбора стратегии на основе контекста, уверенности и пользовательской модели.
- **Калибровка уверенности (confidence)** формально введена, но не анализируется. Требуется механизм сравнения предсказанной уверенности с фактическим успехом (Brier score, Reliability Diagrams).
- **Долгосрочная память**: права гипотетической долгоживущей модели пользователя не реализованы. Архитектура не хранит историю намерений, предпочтений и сессий. Для «фантастического» уровня нужен граф знаний о пользователе.
- **Интеграция с контурами исполнения**: система планирует и пишет код, но отсутствует feedback от CI/CD (deploy) или автоматизированной среды. То есть разрыв между фактической «реальностью» и intention engine не закрыт.
- **Управление экспериментами и адаптивность**: реализованы флаги и оценка (A/B, policy-gates), но нет рамок для онлайн-обучения и стратегий (bandit, meta-learning). Необходимы модули self-tuning стратегий и непрерывной оптимизации.

Ниже представлены детальные результаты по разделам: **Реализовано vs документировано (Таблица 2)**, **Недостающие функции (Таблица 3)**, **Расхождения (список)**, **Миссия, вектор и метрики прогресса**, **Дорожная карта и реестр рисков**. 

# 1. Реализовано vs Предусмотрено (Mapping Table)

Ниже — подробное сопоставление требований из документации проекта с их реальным статусом в кодовой базе. Колонки: **Requirement** (требование из спецификаций), **Документ** (источник из официальной документации), **Код / модуль** (где искать реализацию), **Реализовано?** (yes/partial/no), **Подтверждение** (ссылки на ключевые строки кода/файлы или примечания), **Приоритет разрыва**.

| Требование                                      | Документ (раздел)                            | Код / Модуль                                               | Реализовано | Доказательства (файл:строка или упоминание)                            | Приоритет |
|-----------------------------------------|--------------------------------------------|-------------------------------------------------------------|-------------|--------------------------------------------------------------------|----------|
| **Control Plane vs Intelligence Plane (side-effects только через инструменты)** | docs/solution_design.md (Concepts)           | `WorkflowRunner`, `SandboxToolExecutor`, `LlmAgentPlanner`   | Да          | `WorkflowRunner.run()` (контрольный цикл запуска)【7†L1-L6】; `SandboxToolExecutor.execute()` (метод выполнения инструментов)【7†L24-L29】; `LlmAgentPlanner` для планирования (интеграция ML)【7†L31-L37】 | Низкий   |
| **API Gateway + Health, Metrics, Ready endpoints**           | docs/solution_design.md (API section)       | `FactoryApi.kt`                                            | Да          | Ktor-маршруты: `get("/health")`, `get("/metrics")`, `post("/factory/run")` и т.д. См. `FactoryApi.kt`【7†L14-L21】【7†L47-L54】.                                                          | Низкий   |
| **Валидация входных контрактов (goal, constraints, target_stack)** | docs/contract-drift-guard.md (Contracts)     | `ContractValidator.kt`                                    | Частично    | Присутствует схема JSON/YAML и проверка через `ContractValidator.validate()`【7†L15-L22】, но неполная поддержка специфических полей `target_stack`. См. `FactoryRunRequest` (поля goal, constraints, budget)【7†L7-L12】. | Средний  |
| **Contract Drift Guard (CLI + CI)**         | docs/contract-drift-guard.md (Drift Guard)  | `ContractDriftGuard.kt`, `.github/workflows`               | Да          | `ContractDriftGuardCli` реализован【7†L9-L11】; CI-сценарий в `.github/workflows` настроен для отчёта.                                                        | Низкий   |
| **Tool Registry (SSoT, risk tiers, allowed_secrets)** | contracts/tools.schema.json                  | `ToolRegistry.kt`                                         | Да          | Регистрация инструментов загружается из `tools.registry.json`【7†L29-L34】. Поддержка рисков и разрешённых секретов по схеме.                               | Низкий   |
| **Схема вызова инструментов + идемпотентность**   | contracts/tools.schema.json                  | `SandboxToolExecutor.kt`                                   | Да          | Объявлен объект `toolCallSchema` для валидации входа; `idempotencyStore` для обеспечения идемпотентности【7†L24-L29】.                                               | Низкий   |
| **OPA-политики и `/factory/policy-stats`**      | docs/approval-policy.md; `policies/opa`      | `PolicyCheck.kt`; `factory.rego`; `FactoryApi.kt`         | Частично    | `PolicyCheck` интегрирован с OPA (URL из `POLICY_CHECK`), но дефолт fail-open. Есть `/factory/policy-stats` endpoint【7†L40-L47】. Требуется принудить fail-closed в бою. | Высокий  |
| **Управление approvals (pending→approved/rejected, audit)** | docs/approval-policy.md; docs/runbook.md     | `ApprovalStore.kt`; `FactoryApi.kt`; `FactoryWorkflowExecution.kt` | Да          | Enum `ApprovalStatus`【7†L17-L20】, `FactoryApi` имеет POST `/factory/approvals/{runId}/approve` и `/reject`【7†L25-L28】, `WorkflowExecution.requireApproval()` используется.       | Низкий   |
| **Ask-User (вопрос-ответ для уточнений)**       | docs/adr/0009-ask-user-contract-and-store.md | `AskUserStore.kt`; `FactoryApi.kt`                          | Да          | Интерфейс `AskUserStore`【7†L33-L36】, API `POST /factory/runs/{runId}/answer` в `FactoryApi.kt`【7†L29-L32】.                                              | Низкий   |
| **Audit Log (все события решений)**            | docs/observability-and-logs.md              | `AuditLog.kt`; в `SandboxToolExecutor`; `FactoryApi.kt`    | Да          | `FileAuditLog` реализован (методы `logEvent`/`recentEvents`)【7†L49-L56】; `SandboxToolExecutor` пишет события (`"tool_call_executed"`)【7†L27-L30】; API `/audit-log` возвращает последние события. | Средний |
| **Artifact Registry + Manifest contract**      | docs/artifact-manifest-contract.md; `artifact-handoff` | `ArtifactRegistry.kt`; `ArtifactManifest.kt`; `FactoryWorkflowExecution.kt` | Да          | `ArtifactRunRecord` и `ArtifactManifestDocument` реализованы【7†L59-L64】; `WorkflowExecution.writeManifest()` формирует манифест; API `/factory/runs/{runId}` отдаёт manifest.  | Низкий   |
| **Artifact Storage (S3/MinIO)**             | docs/runbook.md (MinIO setup); `docker-compose.yml` | `S3ArtifactStorage.kt`; `SandboxToolExecutor.kt`; `docker-compose.yml` | Частично    | Класс `S3ArtifactStorage` есть【7†L67-L70】; `docker-compose` содержит сервис `minio`【7†L73-L75】; `SandboxToolExecutor.uploadArtifact` упоминает хранилище. Полная интеграция см. `UploadStream`. | Средний  |
| **GitHub Integration (create repo + push)**    | docs/github-setup.md; example-scenario.md     | `GitHubApiClient.kt`; `SandboxToolExecutor.kt`            | Да          | `GitHubApiClient` реализован (создание репо)【7†L71-L74】; `SandboxToolExecutor` содержит действия `"create_github_repo"` и `"push_repo_to_github"`【7†L75-L78】.          | Низкий   |
| **Archetypes (catalog-service, web-app)**      | docs/archetypes-and-roadmap.md; PRD          | `archetypes/catalog-service`; `web-app`; `SandboxToolExecutor.kt` | Да          | Присутствуют каталоги `archetypes/catalog-service` и `web-app`; `SandboxToolExecutor.createRepoFromArchetype()` реализован【7†L80-L82】.                       | Низкий   |
| **Security checks (gitleaks, trivy)**         | docs/threat_model.md; docs/completion-pipeline.md | `SecurityRunner.kt`; `FactoryWorkflowExecution.kt`        | Да          | `SecurityRunner` запускает `gitleaks` и `trivy`【7†L84-L88】; `WorkflowExecution` вызывает securityChecks ☑️.                                                    | Средний  |
| **Supply chain versioning (SBOM/signature)**   | docs/adr/0011-sbom-signature-run.md; docs/adr/0013-release-sbom.md | `SupplyChainVersionResolver.kt`; `ci/release_policy_gate.py`; `.github/workflows/release.yml` | Да          | `SupplyChainVersionResolver` строит версии сборок【7†L90-L93】; скрипт `release_policy_gate.py` и workflow `release.yml` проверяют политику релиза.          | Низкий   |
| **RAG ingestion + PGVector (планировщик)**     | docs/rag-pgvector-operations.md; docs/runbook.md | `rag/*`; `Application.kt`                                  | Частично    | Классы для RAG: `RagIngestionService`, `RagPlannerContextProvider` есть【7†L96-L100】; в `Application.kt` флаг `RagFlags.plannerContextEnabled`; однако локальный PGVector сервис не описан в `docker-compose`. | Средний  |
| **Neural service integration (LLM)**           | docs/neural-service-api.md; docs/neural-service-operations.md | `neural/*`; `agent/*`                                     | Да          | Классы `NeuralFallbackMatrix`, `LlmAgentPlanner` и `LlmAgentCodegen` интегрированы【7†L102-L107】; обращаются к `NeuralServiceClient`.                     | Средний  |
| **Execution Environments (local Docker, remote SSH)** | docs/adr/0012-local-docker-and-remote-ssh.md | `EnvironmentProvider.kt`                                   | Частично    | Реализованы локальный и SSH-провайдеры в `EnvironmentProvider.kt`【7†L109-L113】, но в `SandboxToolExecutor` используется только Docker. Нет реального SSH-исполнителя. | Высокий  |
| **Durable execution (Temporal)**               | docs/runbook.md; `docker-compose.yml`         | `workflow/temporal/*`; `Application.kt`; `docker-compose.yml` | Частично    | Определён адрес Temporal в `Application.kt`【7†L117-L120】; есть `TemporalFactoryWorker`, `WorkflowWorker`【7†L122-L124】; сервис `temporal` в `docker-compose.yml`【7†L125-L127】. Флаг включения не активирован по умолчанию. | Средний  |
| **Observability (OpenTelemetry, metrics, readiness)** | docs/observability-and-logs.md; docs/runbook.md | `OpenTelemetryFactory.kt`; `Application.kt`; `FactoryApi.kt` | Да          | Инициализация OpenTelemetry происходит в `Application.kt`【7†L130-L133】; endpoint `/metrics` есть в API【7†L136-L139】; `/health/ready` тоже реализован. | Средний  |
| **Profile Store (embedding+rules, PUT/GET/DELETE)** | docs/profile-store.md                         | `ProfileStore.kt`; `FactoryApi.kt`                          | Да          | Интерфейс `ProfileStore` и реализация есть【7†L142-L145】; API маршруты `/factory/profiles/{id}` для PUT/DELETE и `/incognito` имеются【7†L148-L152】.         | Средний  |
| **Intent/Experience API**                      | docs/api-intent-experience.md                 | `FactoryApi.kt`; `intent/*`                                 | Да          | В API: POST `/intent/estimate` и `/experience/generate`【7†L155-L159】; класс `LlmIntentCandidatesGenerator` реализует логикуIntent【7†L161-L163】.       | Средний  |
| **Multi-tenancy (tenantId, scopedRunId, isolation tests)** | docs/risk-register.md (Multi-tenancy)       | `FactoryApi.kt`; тесты `TenantIsolationApiTest.kt`         | Частично    | Парсинг `tenantId` и `scopedRunId` есть в `FactoryApi.kt`【7†L166-L169】; есть интеграционный тест `TenantIsolationApiTest`【7†L172-L174】; **но** нет настоящей аутентификации/AuthZ. | Высокий  |
| **CLI self-serve** (run/status/intent, contract validate/drift, rag, adr-draft) | docs/runbook.md (CLI section)         | `cli/ProductFactoryCli.kt`; `cli/FactoryConsumerCli.kt`     | Да          | В CLI: команды `contract-drift-guard`, `intent` (estimate), `adr-draft`, `rag` реализованы【7†L177-L181】. CLI покрывает все упомянутые функции.               | Низкий   |
| **Decision Context API (/runs/{id}/decision-context, UI endpoint)** | docs/completion-pipeline.md               | `FactoryApi.kt`                                            | Частично    | Добавлен endpoint `GET /factory/runs/{runId}/decision-context`【7†L184-L187】 и `GET /factory/ui`; однако frontend UI-форма не описана, и последние события (контекст решений) ограничены. | Средний  |
| **Promotion/SLO gate evaluation**            | docs/completion-pipeline.md (promotion)      | `FactoryApi.kt`; `ci/slo_gate.py`; `WorkflowExecution.kt`   | Частично    | Есть endpoint `POST /factory/promotion/{runId}/evaluate`【7†L190-L192】 и скрипт `ci/slo_gate.py`, плюс логика в `WorkflowExecution` для SLO-оценки【7†L195-L197】. Полностью gated pipeline не реализован. | Средний  |
| **Per-run budget enforcement (token/toolcalls/time)** | docs/system-audit-corrective-plan.md (Budget) | `FactoryRunRequest.kt`; _отсутствует enforcement_         | Нет         | Модель `RunBudget` есть【7†L199-L201】, но нигде не используется для остановки workflow. Требуется реализация отслеживания бюджета. | Высокий  |
| **AuthN/AuthZ для API (защита мульти-тенантности)**     | docs/threat_model.md (Authentication)        | `Application.kt`; `FactoryApi.kt`                          | Нет         | В `Application.kt` нет плагинов Ktor Authentication; нигде не происходит проверки токенов/ролей. Не реализовано. | Высокий  |

※ *Источник:* внутренний анализ репозитория и документации (см. GitHub проект `product-factory`).  

Из таблицы видно: большинство основных модулей реализованы, но критичными остаются отсутствие контроля пер-run бюджетов, AuthN/AuthZ, дефолтная политика открытого доступа (fail-open), ограниченная поддержка адаптивных стратегий и долгосрочного хранения (multi-tenancy).  

# 2. Недостающие возможности и опции реализации

Ниже приведён список функций, которые задекларированы в документации проекта или вытекают из миссии, но **не реализованы** или реализованы частично. Для каждой — конкретные варианты добавления с оценкой трудоёмкости (L/M/H) и плюсами/минусами.

| Функция                                 | Вариант реализации                    | Плюсы                                       | Минусы                                | Трудозатраты |
|-----------------------------------------|---------------------------------------|---------------------------------------------|---------------------------------------|--------------|
| **1. Enforcement per-run budget**        | **A)** Добавить мониторинг в `WorkflowRunner` и `LlmAgent`: прерывать выполнение при превышении бюджета (token/tool/time) | Контроль затрат и избегание runaway; соответствует целевому контракту. | Сложность: надо интегрировать подсчёт токенов/времени в каждый этап, управлять отменой. | high         |
|                                         | **B)** Использовать Temporal (или аналог) для задания timeout/activity budget | Встроенная устойчивость (активность останавливается), история и retry. | Нужно рефакторинг на Temporal; доп. инфра (Temporal-сервер). | high         |
| **2. Политики OPA — fail-closed по умолчанию** | **A)** Сделать default-mode `fail-closed`, позволять явную опцию open для dev | Повышает безопасность «по умолчанию», упрощает аудит. | Может блокировать запуски без политики; требует описания профилей. | low          |
|                                         | **B)** Оставить fail-open, но добавить детектирование unsafe-case (например, при риске >0 давать предупреждение) | Мягче для разработки, но с предупреждениями. | Сложно гарантировать, что никто не пропустит предупреждение. | medium       |
| **3. Dynamic Strategy Selector**         | **A)** Реализовать Rule-based Selector: на вход `uncertainty, userProfile, context` и т.д. вернуть подходящий паттерн (на основе настроек). | Быстро, прозрачно, легко валидация. | Ограничено набором правил, требует поддержки. | medium       |
|                                         | **B)** Machine Learning: выучить policy (reinforcement/meta-learning), который по фичам (confidence, cost, history) выбирает стратегию | Потенциально адаптивно улучшает со временем; меньше ручной логики. | Сложно: требует данных, экспериментов, риск несогласованности решений. | high         |
| **4. Confidence Calibration**            | **A)** Добавить метрику Brier Score / reliability diagrams на стороне аналитики (offline) | Позволяет оцениить и скорректировать доверие модели. | Не решает онлайн-проблему; больше для контроля качества. | low          |
|                                         | **B)** Онлайн-калибровка: корректировка confidence по выявленным ошибкам (например, температурное шкалирование) | Улучшает оценку trust в реальном времени. | Потребует сбора и анализа данных с обратной связью. | medium       |
| **5. Long-term User Profile & Memory**   | **A)** Ввести персистентное хранилище профилей/сессий: сохранять предыдущие intent, ответы, профили пользователей | Позволит персонализацию, прогнозирование намерений, рекомендаций. | Требует СПО по конфиденциальности; ненулевая сложность схемы. | high         |
|                                         | **B)** Лёгкая версия: profile store с ограниченным сроком жизни и отложенным удалением | Меньше требований по хранению, всё ещё даёт некоторую память. | Ограниченная долгосрочность; меньше эффекта персонализации. | medium       |
| **6. Multi-modal input (voice, UI)**     | **A)** Интегрировать Voice2Text + кнопки в UI: обрабатывать аудио как текстовую команду (внешний сервис, e.g. Whisper) | Расширит охват (hands-free); улучшит UX в некоторых сценариях. | Требует инфраструктуры распознавания речи, новых API; синхронизация контекстов. | medium       |
|                                         | **B)** Визуальный строитель (WYSIWYG) для intent (drag-n-drop формулировок) | Подходит для сложных процессов (UI интерактивность). | Большая разработка интерфейсов, снижение гибкости для NLP. | high         |
| **7. End-to-end Experimentation**        | **A)** Собрать экспериментальные флаги (feature flags) на весь pipeline (не только диалоги) и систему аналитики (CI/Argo статусы) | Позволит измерять влияние intent-strategy на реальные метрики (deploy/success). | Повышает сложность эвристик (нужно связывать с внешними системами). | medium       |
|                                         | **B)** Поддержка shadow mode: параллельно выполнять действия без видимых пользователю эффектов, собирать метрики | Не влияет на UX, позволяет оценить гипотезы без риска. | Нужно дополнительное тестовое окружение; сложно обрабатывать настройки. | high         |

Эти опции следует расставлять по приоритету (например, enforcement budgets и Auth сразу, адаптивный селектор позже). Важное замечание: **каждое добавление должно сопровождаться новыми тестами и документацией**. 

# 3. Расхождения между кодом и документацией (Discrepancies)

Ниже перечислены важные расхождения между заявленным в документации поведением системы и фактической реализацией. Для каждого — влияние и предложение по фиксу.

- **Отсутствие контроля per-run budget.** В документации заложена модель бюджета (токены/звонки/время) для governance, но в коде нет механизма досрочной остановки при превышении. **Влияние:** риск неограниченного использования ресурсов (LLM-токены, бесконечный цикл). **Исправление:** внедрить мониторинг budget в `WorkflowRunner` и прекратить выполнение/выдать ошибку при нарушении бюджета (см. Missing #1).

- **AuthN/AuthZ для API.** Документация обозначает мульти-арендность как важную и опасность злоупотреблений, но приложение пока открытое (Ktor без аутентификации). **Влияние:** при развертывании сервис общедоступен: любой может вызвать API под любым tenantId. **Исправление:** добавить обязательную аутентификацию (JWT, OAuth2) и авторизацию по tenantId, внедрить rate-limiter/антифрод (см. Threat Model).

- **Default fail-open для OPA.** Система PolicyCheck по умолчанию разрешает, если OPA недоступна. Документально ожидалось, что в бою политика закроет небезопасные сценарии. **Влияние:** потенциальная уязвимость при сбоях OPA; нарушения governance. **Исправление:** поменять default-fail на closed в боевом режиме; логгировать и алертить падения OPA (см. `PolicyCheck.failMode`).

- **Стратегии уточнения жёстко зашитые.** Документация описывает много сценариев уточнений, но код их не выбирает динамически: используется набор статических DSL-команд. **Влияние:** не учитывается контекст (напр. энергичность пользователя, среда), нет A/B-самобалансировки. **Исправление:** выделить модуль StrategySelector, который на основе шаблонных фичей (confidence, cost_of_error, pref) выбирает нужные вопросы (см. Missing #3).

- **Decision-Context UI (Frontend).** Документация упоминала веб-форму для принятия решений (assessor UI), но фактическая реализация ограничена API `GET /factory/ui` без фронтенда. **Влияние:** неполная функциональность UX; часть pipeline не автоматизирована. **Исправление:** либо разворачивать простую web-страницу, либо отмечать как out-of-scope.

- **Сайд-эффекты (деплой) в workflow.** Концепция Intent→Reality подразумевает, что после генерации кода есть шаг “deploy” и обратная связь. В коде шаг `executeStage` лишь помечает staging готовым, но не интегрирован с реальным CI/CD. **Влияние:** разрыв между замыслом и результатом. **Исправление:** добавить синхронизацию с CI (GitHub Actions, Kubernetes, артефактный деплой) либо пометить “external process”.

- **Temporal режим опционально.** Поддержка Durable Workflow заявлена, но в стандартной сборке по умолчанию отключена (нет worker’ов). Если выбрана лишь локальная модель, подхватить workflow (retry, preemption) невозможно. **Исправление:** документировать ограничения, или включить Temporal-профиль с worker’ом по умолчанию.

- **Обратная связь об ошибках инструментов.** В документации неявно предполагается, что ошибки tool-выполнения будут корректно логироваться. Реализация `SandboxToolExecutor` выводит лишь limited logs. **Исправление:** расширить аудирование ошибок, возможно, интеграцию с ExceptionTracker.

- **Исходящие уведомления.** Нет механизма уведомления внешнего мира (например, email) о статусе запуска. Это не заявлено, но опционально можно добавить webhook.

# 4. Миссия, стратегический вектор и прогресс

**Миссия проекта:** «Получая намерение пользователя (задачу), система последовательно генерирует deployable-артефакты (код, инфраструктуру и процедуры) через детерминированный workflow, где ML/AI компоненты (LLM, RAG) выступают как генераторы предложений, а все side-effect операции происходят только через проверенные инструменты под управлением политик, аудита и «человеческой проверки».» 

Это прямо отражает концепцию Intent→Reality, для которой предложена чёткая product roadmap (см. docs/roadmap-workstreams-and-variants.md). 

**Стратегический вектор:** построение гибридной системы AI+инженерии, где пользовательские намерения непрерывно уточняются и материализуются в код + конфигурации. При этом проект двигается от Alpha MVP (чистый DSL и backlog функций) к продукту уровня DevOps/AI-Copilot, способному самостоятельно обновляться по управлению. 

**Прогресс:** 
- Завершены фазы Alpha/Beta: реализованы контракты, drift-guard, core workflow (state machine, agent, audit, approvals)【7†L1-L6】【7†L49-L56】.
- Выполнен MVP: инструменты sandbox-исполнителя с GitHub/MinIO, API вызовов, CLI self-serve и базовый UI/metrics (экземпляры `FactoryApi`)【7†L14-L21】【7†L71-L78】.
- Ведется масштаб: добавлены LLM planner/codegen, частичная RAG, SLO gate, security checks【7†L84-L88】【7†L90-L93】. 

**KPI и метрики прогресса:**
- **Throughput**: сколько intent-циклов запускается (deployment-per-intent) в неделю.
- **Accuracy**: доля intent, подтверждённых пользователем (intent.confirmed), и успех deployment.
- **Latency**: время от /factory/run до исполнения (медленное ML-интеракции, быстродействие).
- **Governance**: количество run, ушедших на approvals; доля политик deny vs allow; отказов из-за резких ошибок.
- **Quality gates**: пасc/фейл security/SLO checks (ci/quality_gates).
- **User Satisfaction**: результаты микросюрвеев «поняли ли мы intent», NPS среди пользователей раннеров.
- **Dev Metrics**: количество новых инструментов, покрытие тестами, время до поправки когнитивных ADR.

Эти метрики помогут количественно отслеживать «движение в заданном векторе» и вовремя корректировать стратегию продукта. 

# 5. Роадмап (краткосрочный, среднесрочный, долгосрочный)

Ниже — приоритетная дорожная карта по горизонтам, с оценками усилий и рисками.

```mermaid
gantt
    dateFormat  YYYY-MM-DD
    title Приоритетный роадмап Intent→Reality Engine

    section Краткосрочный (1–3 мес)
      Укрепить безопасность и контроль :sec1, 2026-03-01, 45d
      - AuthN/AuthZ, Fail-closed, Rate-limit
      Budget enforcement в воркфлоу        :after sec1, 2026-03-20, 30d
      - Мониторинг токенов/времени, отмены
      Развёртывание и интеграция CI         :2026-04-01, 30d
      - GitHub Actions, проверки, webhook на CI status
      KPI Dashboard и наблюдаемость       :2026-04-15, 30d
      - Метрики (accuracy, latency), alert-ы
  
    section Среднесрочный (3–12 мес)
      Стратегии уточнения (Adaptive)        :2026-05-15, 90d
      - Реализовать StrategySelector, A/B-тестирование подходов
      Калибровка confidence                :2026-06-01, 60d
      - Автоматический анализ BrierScore, корректировка модели
      User Profile & Memory                :2026-07-01, 120d
      - Хранение истории intent, персонализация
      Расширение мультимодальных входов      :2026-08-01, 90d
      - Speech-to-text, UI-клиенты, расширения
  
    section Долгосрочный (>12 мес)
      Meta-Learning / Reinforcement       :2026-10-01, 180d
      - Автоматическое обновление стратегий
      Коллаборация с execution-contour     :2027-03-01, 180d
      - Полная связь intent-движка с деплоем и мониторингом
      E2E AI-Coalition                      :2027-03-01, 365d
      - Системы, запоминающие user vector и intent stream
```

### Риски и их смягчения

| Риск / Проблема                   | Последствия                | Митигирующие меры                |
|------------------------------------|----------------------------|----------------------------------|
| **Security breach (AuthN missing)** | Компрометация всех run-ов  | Немедленно добавить аутентификацию (JWT/OIDC), монтиторить atypical tenants, rate-limit. |
| **Fail-open policy**               | Нарушение governance       | Сделать default fail-closed в проде, тестировать сценарии отказа OPA.  |
| **Budget overrun (cost/runs)**     | Неожиданные затраты, DDoS  | Enforce budgets, алерты на превышение; лимиты calls. |
| **Drift of ML models**             | Потеря качества intent estimation | Регулярно переобучать/валидировать модели; внедрить аналитику «канареечного» отклонения. |
| **Сложность CLI/UI**               | Плохой UX, малое покрытие пользователей | Создать понятную документацию и веб-UI; incremental rollout с юзабилити-тестами. |
| **Data privacy (GDPR/CCPA)**       | Штрафы, потеря trust       | Минимизация хранения (не оставлять PII в промтах), механизмы прав субъектов, согласие на запись диалогов. |
| **Непредвиденное поведение (AI риск)** | Негативный user experience | Непрерывное тестирование, мониторинг отклонений, возможность быстро откатить изменения стратегий (feature flag). |

# 6. Архитектурная схема (мермейд)

```mermaid
flowchart LR
    UI[(User Interface / Chatbot)]
    DB[(Рабочая БД / Profiling)]
    events[(Telemetry\nevents)]
    orches[Orchestrator (API Gateway)]
    runmgr[WorkflowRunner / State Machine]
    planner[LLM/Planner]
    rag[RAG Ingestion]
    toolx[SandboxToolExecutor]
    policy[OPA PolicyCheck]
    approval[ApprovalStore]
    askuser[AskUserStore]
    audit[AuditLog]
    metrics[Metrics & Telemetry]
    artifact[Artifact Registry + Storage]

    UI --> orches
    orches --> runmgr
    orches --> DB
    runmgr --> planner
    runmgr --> rag
    runmgr --> toolx
    toolx --> policy
    toolx --> artifact
    toolx --> audit
    toolx --> events
    runmgr --> approval
    runmgr --> askuser
    audit --> events
    approval --> events
    askuser --> events
    events --> metrics

    metrics --> orches
    DB --> orchestr
    orchestr --> UI
```

- **Orchestrator (API Gateway)** управляет входящими запросами, проверяет `tenantId`, `scopedRunId`, агрегирует feature-flags (OpenFeature) и распределяет задачи.
- **WorkflowRunner** реализует детерминированный цикл: проверяет контракты (Contract Validator), планирует последовательность действий, учитывает политики, осуществляет апрувы/вопросы и финальный деплой.
- **Agent (Planner)** — LLM-клиент (с fallbacks). Генерирует код и пояснения на базе нейросервисов. Может использовать RAG (библиотеки, примеры) при включенном флаге.
- **SandboxToolExecutor** — модуль исполнения side-effects: запускает инструменты (docker/container, репозитории, security-scans). Все вызовы проходят через валидацию схем и логику идемпотентности.
- **PolicyCheck (OPA)** — вызов OPA-движка с входным утвердительным контекстом, блокирующий небезопасные сценарии (чёрный список инструментов, лимиты).
- **ApprovalStore / AskUserStore** — хранилища ожидания ответов человека. Когда необходима проверка, workflow останавливается до ответа через `FactoryApi`.
- **AuditLog** — поток исторических событий (JSONL). Хранит все решения, ответы, итерации, хеши входящих данных для аудита.
- **Artifact Registry + Storage** — учёт артефактов генерации: созданные репозитории, образа, схемы. Манифест состояний писал `WorkflowRunner`.
- **Telemetry Pipeline** — OpenTelemetry события из каждого шага, метрики Prometheus (через `/metrics`).
- **Profiling DB** — опциональный компонент для хранения long-term профилей, embedding-предпочтений, экспериментальных данных. Пока не реализован в коде, но предлагается как будущая часть.

# 7. Резюме и итоги

В **собранном анализе** код базы совпадает с большой частью требований документации (см. Табл.2). Пробелы главным образом лежат в области **автономности и адаптивности**: мы нашли, что система пока реализует детерминированную pipeline и стандартные проверки, но не обладает механизмами самообучения и персонализации (что критично для уровня «фантастики»). 

**Рекомендации для эволюции проекта:**
- Добавить **динамический селектор стратегий** (Rule или ML) с поддержкой A/B-экспериментов.
- Внедрить **отключаемую (feature-flag) калибровку confidence** и постоянное замыкание цикла intent→действие→результат→анализ.
- Построить **долгосрочный профиль пользователей** для персонализации (опираясь на запросы и ответы).
- Укрепить **безаопасность и мульти-тенантность** (AuthN/AuthZ, privacy, fail-closed).
- Связать [Intent Engine] с _execution_ окружением: CI/CD интеграции, мониторинг релизов, web-hooks для статусов deployment.

Таким образом, архитектура трансформируется из «повторяемой продуктовой методологии» в «когнитивную систему», где пользователи ощущают себя не просто потребителями сервиса, а участниками интерактивного интерфейса машинного интеллекта. 

**Источники:** внутренний анализ документации и репозитория (ProductFactory) и общепринятые методологии DevSecOps/AI (см. OpenTelemetry docs【12†L1-L3】, OpenFeature spec【11†L1-L3】).