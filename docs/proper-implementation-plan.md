# План «сделать правильно»: замена заглушек и временных решений

**Цель:** все критические пути реализованы по-настоящему; заглушки и placeholder только там, где явно принято решение отложить (с фиксацией в ADR).

**Статус:** план; выполнение — по приоритету ниже.

**Уточнение по тексту и аудио:** в плане учтены текст (ввод/вывод текстом) и аудио (голосовой диалог, ASR+TTS). Остальные модальности не входят в текущий план.

---

## 1. Что сейчас временно

| Область | Сейчас | Где в коде/доках |
|--------|--------|-------------------|
| **SBOM / подпись в артефакт-реестре** | Всегда `placeholder-v1` | `ArtifactRegistry.kt` → `artifactRecordForState()`, поля `sbomVersion`, `signatureVersion` |
| **Intent: уточняющие вопросы** | Фиксированный список шаблонов | `IntentClarification.kt` — `buildQuestionBank()`, TODO про LLM |
| **Intent: генерация из goal** | Детерминированная заглушка | `IntentGenerator.kt` — без вызова LLM |
| **Intent: кандидаты результата** | Stub, возвращает список строк | `IntentCandidatesGenerator.kt` |
| **Environment: local-docker** | Заглушка или минимальная реализация | `LocalDockerEnvironmentProvider` — конфиг без реального запуска compose |
| **Environment: remote-ssh** | Placeholder параметры, не выполняет SSH | `RemoteSshEnvironmentProvider` / `StubEnvironmentProvider` |
| **API intent/experience** | Документ + опционально 501-заглушки | `docs/api-intent-experience.md`; эндпоинты не в контуре фабрики |
| **SLO CI gate** | Документ + скрипт-заглушка | `slo-ci-gate.md`, `ci/slo_gate.py` — не блокирует merge по факту |
| **Cost budgets в рантайме** | Только в API (budget в запросе), без enforcement месячного cap | OPA/лимиты по run есть; soft/hard cap по периоду — нет |

**Не считается временным (оставлено по дизайну):**
- **StubAgentPlanner / StubAgentCodegen** — штатный fallback при отсутствии `NEURAL_SERVICE_URL`; при наличии URL используются LLM-реализации.
- **TestRunner / SecurityRunner** — реальный запуск `gradlew test`, gitleaks, trivy; при недоступности инструментов — skip, не заглушка.

---

## 2. Приоритеты и «правильная» реализация

### P0 — Артефакт-реестр: реальные SBOM и подпись

**Сейчас:** в запись run всегда пишутся `sbom:cyclonedx:placeholder-v1` и `signature:cosign:placeholder-v1`.

**Правильно:**
- Вариант A: при run в CI архетипа уже генерируются SBOM и подпись; фабрика при `executeStage`/`executeFinish` может принимать опциональные `sbomRef` и `signatureRef` (например путь к артефакту или идентификатор из CI) и записывать их в реестр.
- Вариант B: фабрика после создания репо в workspace запускает Syft/Cosign (или вызывает tool), получает ссылки на артефакты и передаёт в `artifactRecordForState`.
- Минимум: расширить сигнатуру `artifactRecordForState` (или место вызова) параметрами `sbomVersion: String?`, `signatureVersion: String?`; при null — оставлять текущие placeholder; при наличии — писать реальные значения. Реализация вызова Syft/Cosign или приём из CI — отдельная задача.

**Задачи:**
1. Добавить в `ArtifactRunRecord` / вызов `artifactRecordForState` опциональные `sbomVersion` и `signatureVersion` извне (не хардкод).
2. Документировать в runbook: откуда берутся реальные значения (CI архетипа, tool, или ручная подстановка).
3. (Дальше) Реализовать вызов Syft/Cosign в контуре run или приём из артефактов CI.

---

### P1 — Intent Layer: LLM при наличии нейросервиса

**Сейчас:** IntentClarification — шаблонные вопросы; IntentGenerator и IntentCandidatesGenerator — стабы без LLM.

**Правильно:**
- При заданном `NEURAL_SERVICE_URL`: вариант вопросов и генерация intent/кандидатов через LLM (тот же контракт, что у планировщика/кодогена). При отсутствии URL — оставлять текущее детерминированное поведение (как у StubAgentPlanner).
- Интерфейсы остаются; добавляются реализации `LlmIntentClarification`, `LlmIntentGenerator`, `LlmIntentCandidatesGenerator`, принимающие `NeuralServiceClient` и подставляемые в контур при NEURAL_SERVICE_URL.

**Задачи:**
1. Реализовать `LlmIntentClarification.nextQuestion(goal, constraints, previousAnswers)`: один запрос к LLM, возврат одного вопроса или null. Промпт и формат ответа описать в docs, контракт — в коде.
2. Реализовать `LlmIntentGenerator.generate(goal, constraints)`: вызов LLM, возврат IntentSpec по схеме intent.schema.json. Fallback на текущий stub при ошибке/невалидном JSON.
3. Реализовать `LlmIntentCandidatesGenerator.generate(intent)`: вызов LLM, возврат 3–7 кандидатов (описания или идентификаторы). Выбор пользователя уже пишется в audit — сохранить.
4. В Application (или точке входа intent-API) при NEURAL_SERVICE_URL подставлять LLM-реализации, иначе текущие stub/детерминированные классы.
5. Удалить TODO в IntentClarification или заменить на ссылку на LlmIntentClarification.

---

### P2 — API intent/experience в контуре фабрики

**Сейчас:** API описан в docs; эндпоинты либо отсутствуют, либо возвращают 501.

**Правильно:**
- Реальные маршруты в Ktor: `POST /intent/estimate`, `POST /experience/generate` (или по документу api-intent-experience.md). Внутри — вызов IntentClarification / IntentGenerator / IntentCandidatesGenerator (LLM или stub в зависимости от NEURAL_SERVICE_URL). Ответы по контракту из документа; запись в audit (intent_candidate_selected и т.д.) уже предусмотрена.
- Интеграция с factory run: после выбора кандидата или финального intent можно запускать `POST /factory/run` с полученным goal/constraints (или отдельный сценарий в runbook).

**Задачи:**
1. Добавить в FactoryApi (или отдельный IntentApi) маршруты из api-intent-experience.md.
2. Реализовать обработчики с вызовом IntentClarification, IntentGenerator, IntentCandidatesGenerator и записью в audit.
3. Обновить runbook: пример запроса → ответ; при необходимости пример «intent → factory run».

---

### P3 — Environment provider: local-docker по-настоящему

**Сейчас:** LocalDockerEnvironmentProvider возвращает конфиг (путь к compose, workspace); реального запуска шагов в контейнере нет.

**Правильно:**
- Либо фабрика при выборе окружения `local-docker` выполняет шаги (tool executor, тесты, security) внутри контейнера/compose (например один сервис «runner» с volume workspace), либо явно документировать, что «запуск в Docker» — следующий этап и текущая реализация только отдаёт конфиг. Принимаемое решение зафиксировать в ADR.
- Если «правильно» = реальный запуск: контракт EnvironmentProvider.provide() должен возвращать не только конфиг, но и способ выполнения команды (например интерфейс ExecutionContext с методом runCommand), и Tool Executor / TestRunner / SecurityRunner вызывают его. Больший объём работ.

**Задачи:**
1. ADR: local-docker — «только конфиг до версии X» или «полноценный запуск шагов в compose». При выборе «только конфиг» — явно пометить в environments.md и в коде, что выполнение в контейнере — roadmap.
2. Если полноценный запуск: спроектировать ExecutionContext / runCommand; реализовать LocalDockerEnvironmentProvider с запуском команд в контейнере; подключить к WorkflowRunner/FactoryWorkflowExecution.

---

### P4 — Remote-ssh: контракт и реализация или явный отказ

**Сейчас:** Контракт в environments.md; в коде заглушка с placeholder параметрами.

**Правильно:**
- Либо реализовать минимальный вариант: по конфигу (host, user, key path из env) выполнять команду на удалённой машине (например через ProcessBuilder ssh) и возвращать результат (success, stdout, stderr). Либо зафиксировать в ADR, что remote-ssh в текущей версии не поддерживается (out of scope), и оставить заглушку с чёткой пометкой в коде и в docs.

**Задачи:**
1. Решение: реализовать минимальный SSH-runner или объявить out of scope с ADR.
2. Если реализуем: интерфейс выполнения одной команды по SSH; конфиг из env; безопасность (ключи не в коде). Если out of scope: обновить environments.md и класс-заглушку комментарием «Not implemented; see ADR …».

---

### P5 — SLO CI gate: реальная блокировка merge

**Сейчас:** Документ и скрипт есть; в CI gate не включён или не блокирует при нарушении.

**Правильно:**
- Job в ci.yml (или отдельный workflow), вызывающий скрипт/программу SLO gate: читает метрики или артефакты последнего прогона (например из job live-run), проверяет пороги (success_rate, p99, budget). При нарушении — exit code 1 и блокировка merge. Пороги задаются в конфиге (файл или env).

**Задачи:**
1. Реализовать в ci/slo_gate.py (или аналоге) чтение метрик/артефактов и проверку порогов; возврат ненулевого кода при нарушении.
2. Добавить job в .github/workflows/ci.yml, зависящий от live-run или от артефактов; при fail — блокировка.
3. Обновить slo-ci-gate.md и runbook: как работает gate, как временно отключить при известном нарушении (например env SKIP_SLO_GATE).

---

### P6 — Cost budgets: enforcement в рантайме

**Сейчас:** В запросе run можно передать budget (token_budget, tool_calls_budget, wall_clock_seconds); месячный/недельный cap только в документации.

**Правильно:**
- Конфиг (файл или env) с лимитами на период (например месяц): макс. токены, макс. tool_calls, макс. runs. Перед принятием нового run фабрика проверяет: текущее потребление за период + оценка нового run не превышают лимит. При превышении — отказ (429 или 403) с сообщением. Данные о потреблении брать из метрик (/metrics или персистентное хранилище).

**Задачи:**
1. Дизайн: где хранить счётчики за период (in-memory с записью в файл при каждом run, или экспорт из Prometheus). Документ: [period-usage-counters-design.md](period-usage-counters-design.md).
2. Реализовать проверку перед запуском run: чтение текущих итогов за период, сравнение с лимитом, отказ при превышении.
3. Конфиг cost-budgets (пример в cost-budgets.example.yaml) и загрузка в приложении. Обновить runbook и slo-cost-draft.

---

## 3. Порядок выполнения

Рекомендуемый порядок (по зависимостям и ценности):

1. **P0** — артефакт-реестр (параметризация sbom/signature). Быстро убирает placeholder из основного контракта.
2. **P5** — SLO CI gate. Не зависит от остального; даёт реальную защиту от деградации.
3. **P1** — Intent LLM. Даёт полноценный Intent Layer при наличии нейросервиса.
4. **P2** — API intent/experience. Использует P1.
5. **P6** — cost budgets в рантайме. Независимо, но требует хранения/агрегации метрик.
6. **P3** — local-docker: сначала ADR, затем либо конфиг-only, либо полноценный запуск.
7. **P4** — remote-ssh: решение + либо минимальная реализация, либо ADR out of scope.

---

## 4. Документы и ADR

- Все решения «оставить заглушку до версии X» или «не реализовывать» фиксировать в ADR (например 0011-artifact-registry-sbom-source, 0012-environment-provider-scope).
- После каждой реализованной группы (P0, P1, …) обновлять implementation-assessment.md и убирать формулировки «placeholder»/«stub» из описания соответствующей области (с заменой на «реализовано» или «см. ADR …»).

---

## 5. Связь с бэклогом

- Предыдущие волны roadmap закрыты документами и заглушками.
- Данный план — следующий этап: **«Proper implementation»** (Phase 3 по смыслу). Задачи из §2 можно перенести в отдельный бэклог и при необходимости вынести в скрипт пайплайна или в трекер (GitHub Issues).

---

## 6. Что делать после реализации плана (P0–P6)

Когда заглушки заменены и SLO/cost/budgets работают, следующий горизонт — [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md): Preference Model 1.0 (профиль предпочтений, active learning), усиление Artifact Runtime (manifest, evidence), дальнейшее развитие Intent Layer.

**Workstreams v1:** WS1 — role registry и шаблоны; WS2 — провижининг, browser runners; WS3 — веб-форма/чат, спринт-поинты; WS4 — «6 карточек» референсов, reference_ids в intent.

**Видение v1–v4:** референсы (6 карточек) → калибровка по поведению → опциональные сенсоры → Stream-mode и IVM (Intent Virtual Machine).

**Операционка:** Фаза 6 (SLSA, AI-risk контур, каждый release с SBOM/подписью); Фаза 7 (GitOps CD до prod, cost budgets в рантайме, multi-tenancy при необходимости).

**Практичный порядок:** (1) стабилизация E2E и метрик; (2) Фаза B или WS4 v1 по приоритету продукта; (3) Фаза 6 комплаенс; (4) Stream/IVM после закрепления intent + build-mode.
