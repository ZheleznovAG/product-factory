# Grand Pipeline — план «по-взрослому»

**Цель:** один полный пайплайн разработки, в который входит почти всё реализуемое сегодня — без оговорок «когда будет команда»; делаем грандиозное мировое.

**Исключено из этого пайплайна** (не готово к продакшену на текущем горизонте): расширенные визуальные и биометрические каналы уточнения намерения (взгляд/мимика, загрузка пользователем демо-примеров, продвинутая психометрия и биосенсорика). Остаются в roadmap на будущее, после стабилизации текстового и аудио-каналов.

**Всё остальное — в пайплайне:** уточнение по тексту и аудио (полноценно), Intent Layer с LLM, API intent/experience, SLO и cost по-настоящему, окружения (local-docker, remote-ssh), RAG, eval, комплаенс (SLSA, AI-risk), GitOps CD, cost budgets в рантайме, Preference Model, role registry, веб-форма решений, артефакт-manifest, сессия и референсы (выбор из предложенного системой), тесты, документация.

**Запуск:** `python3 scripts/run_grand_pipeline.py --allow-docker` (см. [grand-pipeline-tasks.md](grand-pipeline-tasks.md)).

---

## Блок 1 — Intent: уточнение по тексту и аудио (полноценно)

| # | Задача | Детали |
|---|--------|--------|
| 1 | LlmIntentClarification | Реализовать класс: nextQuestion(goal, constraints, previousAnswers) через NeuralServiceClient; промпт и контракт в docs; fallback на stub при отсутствии URL. |
| 2 | Адаптивные вопросы | Выбор и порядок вопросов в зависимости от goal/constraints (по ключевым словам или через LLM); не один и тот же набор для всех. |
| 3 | Раннее завершение | После каждого ответа обновлять черновик intent; nextQuestion возвращает null при достаточной полноте/confidence; логика в IntentClarification или отдельном сервисе. |
| 4 | Короткий свободный ответ | Принимать не только A/B, но и 1–2 фразы; маппинг в поля intent (правила или LLM); при неясности — один уточняющий вопрос или fallback на A/B. |
| 5 | LlmIntentGenerator | generate(goal, constraints) → IntentSpec по схеме; fallback на stub при ошибке. |
| 6 | LlmIntentCandidatesGenerator | generate(intent) → 3–7 кандидатов; выбор пользователя в audit. |
| 7 | Подключение в Application | При NEURAL_SERVICE_URL подставлять LLM-реализации для clarification/generator/candidates; иначе текущие stub. |
| 8 | Документация и оптимальность | Обновить intent-clarification-protocol и intent-clarification-text-audio-optimality; описать аудио-слой (ASR → текст, TTS для вопросов). |

---

## Блок 2 — API intent/experience в контуре фабрики

| # | Задача | Детали |
|---|--------|--------|
| 9 | Маршруты Ktor | POST /intent/estimate, POST /experience/generate по api-intent-experience.md. |
| 10 | Обработчики и audit | Вызов IntentClarification/IntentGenerator/IntentCandidatesGenerator; запись intent_candidate_selected и др. в audit. |
| 11 | Runbook и сценарий | Пример запроса → ответ; сценарий «intent → factory run» (после выбора кандидата вызвать POST /factory/run). |

---

## Блок 3 — Артефакт-реестр: реальные SBOM и подпись

| # | Задача | Детали |
|---|--------|--------|
| 12 | Источник SBOM/signature | Tool или пост-шаг после create_repo: вызов Syft/Cosign по workspace run или приём идентификаторов из артефактов CI; передача sbomVersion/signatureVersion в ToolStepResult. |
| 13 | Runbook и ADR | Документировать откуда берутся значения; при необходимости ADR 0011 (источник SBOM/signature). |

---

## Блок 4 — SLO, наблюдаемость, алерты

| # | Задача | Детали |
|---|--------|--------|
| 14 | SLO CI gate в ci.yml | Job, вызывающий slo_gate: чтение метрик/артефактов, проверка порогов; exit 1 при нарушении; конфиг порогов. |
| 15 | Prometheus alert rules | Файл правил в репо; подключение в prometheus.yml; алерты: success_rate, p99, budget. |
| 16 | Grafana дашборд | SLO + cost дашборд в provisioning; панели по метрикам фабрики. |
| 17 | Runbook SLO и алерты | Где смотреть, как реагировать при нарушении; ссылка на slo-ci-gate и правила. |
| 18 | Онлайн-сигналы для promotion | Реализация или скрипт: использование /metrics и артефакт-реестра для решения о promotion; обновить slo-promotion-signals. |

---

## Блок 5 — Cost budgets в рантайме

| # | Задача | Детали |
|---|--------|--------|
| 19 | Хранение счётчиков за период | Дизайн и реализация: файл или БД для итогов за день/месяц; обновление при каждом run. |
| 20 | Проверка перед run | Перед принятием run: текущее потребление + оценка нового run vs лимит; при превышении — 429/403 с сообщением. |
| 21 | Конфиг и runbook | cost-budgets.example.yaml и загрузка в приложении; runbook и slo-cost-draft. |

---

## Блок 6 — Окружения: local-docker и remote-ssh по-настоящему

| # | Задача | Детали |
|---|--------|--------|
| 22 | ADR local-docker | Решение: только конфиг или полноценный запуск шагов в compose; зафиксировать в ADR. |
| 23 | Local-docker реализация | При выборе «полноценный запуск»: ExecutionContext/runCommand, LocalDockerEnvironmentProvider выполняет команды в контейнере; подключение к TestRunner/SecurityRunner/tool executor. |
| 24 | Remote-ssh реализация или ADR | Минимальный SSH-runner (host, user, key из env, одна команда) или ADR «out of scope» с пометкой в коде и environments.md. |
| 25 | Runbook environments | Обновить environments.md и runbook: как использовать local-docker и remote-ssh. |

---

## Блок 7 — RAG и eval

| # | Задача | Детали |
|---|--------|--------|
| 26 | RAG ingestion + pgvector | Реализация: загрузка архетипов/доков в pgvector, версионирование индекса; точка интеграции в планировщике (опционально). |
| 27 | Offline eval RAGAs | Сценарий или интеграция: faithfulness, relevance по одному датасету; документ eval-ragas.md. |
| 28 | Eval gate и датасеты | Расширить датасеты и качество gate; при необходимости дополнительный job в CI. |

---

## Блок 8 — Комплаенс: SLSA и AI-risk

| # | Задача | Детали |
|---|--------|--------|
| 29 | SLSA аттестации | План и шаги по slsa-attestation-plan; реализация provenance/build attestation в CI где возможно. |
| 30 | Контур AI-risk | По ai-risk-contour-plan: процессные элементы (governance, risk assessment, мониторинг); чеклист и ответственные; связь с threat_model и risk-register. |
| 31 | Release с SBOM и подписью | Гарантировать каждый release фабрики и архетипов с SBOM и подписью; high-risk — ручной approval в процессе. |

---

## Блок 9 — GitOps CD и prod

| # | Задача | Детали |
|---|--------|--------|
| 32 | GitOps CD до prod | Реализация или детальная процедура по gitops-cd-prod-plan: продвижение артефактов от staging к prod. |
| 33 | Canary и откат | Схема или механизм canary; процедура отката за минуты (runbook). |

---

## Блок 10 — Multi-tenancy и self-serve

| # | Задача | Детали |
|---|--------|--------|
| 34 | Изоляция по tenant | Дизайн: tenantId в API и в run/audit; изоляция артефакт-реестра и audit по tenant. |
| 35 | Portal/CLI для self-serve | Спецификация или минимальный UI/CLI: запуск run, просмотр статуса, выбор intent; приоритет по ресурсам. |
| 36 | Документация self-serve | Runbook или отдельный док: как потребитель получает сервис без ручного копипаста. |

---

## Блок 11 — Preference Model 1.0

| # | Задача | Детали |
|---|--------|--------|
| 37 | Хранилище профиля предпочтений | Реализация: профиль (embedding + правила «люблю X», «не люблю Y») на пользователя/сессию; конфиг и consent. |
| 38 | Обновление по выборам | При выборе кандидата/правке — обновление профиля; использование при ранжировании следующих кандидатов. |
| 39 | API сброс и инкогнито | Endpoint или флаг: сброс профиля, режим инкогнито-сессии; документировать в runbook. |

---

## Блок 12 — WS1 v1: role registry и шаблоны

| # | Задача | Детали |
|---|--------|--------|
| 40 | Role registry | Реестр ролей (Planner, Implementer, Tester, Reviewer + при необходимости другие); конфигурируемый список. |
| 41 | Шаблоны промптов по ролям | Шаблоны или конфиг промптов/правил на роль; использование в LlmAgentPlanner/Codegen при генерации. |

---

## Блок 13 — WS2 v1: провижининг и browser runner

| # | Задача | Детали |
|---|--------|--------|
| 42 | Провижининг раннеров | Документ и при необходимости реализация: провижининг воркеров/раннеров для выполнения шагов. |
| 43 | Headless browser для web-архетипов | Интеграция или вызов headless browser (Playwright/Puppeteer) для smoke/UI тестов web-app архетипа; опционально в TestRunner. |

---

## Блок 14 — WS3 v1: веб-форма решений

| # | Задача | Детали |
|---|--------|--------|
| 44 | Веб-форма/чат для решений | Минимальный UI: отображение плана, рисков, вариантов; сбор решений (approve/reject, выбор A/B); вызов API answer/approvals. |
| 45 | Спринт-поинты | Документ или флаг: обязательные остановки между фазами для подтверждения; запись в audit. |

---

## Блок 15 — Artifact manifest и evidence

| # | Задача | Детали |
|---|--------|--------|
| 46 | Контракт artifact_manifest.json | Схема и описание: manifest + seed + inputs для воспроизведения артефакта. |
| 47 | Заполнение manifest в run | При stage/finish записывать manifest в реестр и audit; связь с ArtifactRunRecord. |
| 48 | Воспроизведение по manifest | Документ или скрипт: как пересобрать артефакт по manifest и audit. |

---

## Блок 16 — Session и референсы (выбор из предложенного)

| # | Задача | Детали |
|---|--------|--------|
| 49 | session.yaml в потоке | Использование session (sessionId, intent ref, preferences) в API и audit; контракт уже есть. |
| 50 | Референсы: 6 карточек, выбор 2 | API или сценарий: система предлагает 6 вариантов (текст/описание или id); пользователь выбирает 2; запись reference_ids в intent и audit. |

---

## Блок 17 — Тесты

| # | Задача | Детали |
|---|--------|--------|
| 51 | FactoryRunTest стабильный | Починить или зафиксировать решение по factory-run-test-analysis; стабильный e2e в CI. |
| 52 | Тест ask_user flow | Полный сценарий: вопрос → answer → продолжение run; интеграционный тест. |
| 53 | Тесты intent API | Тесты POST /intent/estimate, /experience/generate и сценария intent → run. |
| 54 | E2E с intent → run | Скрипт или шаг в CI: уточнение (текст) → выбор кандидата → POST /factory/run → проверка результата. |

---

## Блок 18 — Документация и ADR

| # | Задача | Детали |
|---|--------|--------|
| 55 | ADR 0011, 0012 | Артефакт-реестр (источник SBOM/signature); окружения (local-docker/remote-ssh scope). |
| 56 | implementation-assessment | Обновить после каждого блока: убрать «placeholder»/«stub», отметить реализованное. |
| 57 | Runbook полный | Актуализировать все разделы: intent, SLO, cost, environments, self-serve, откат. |
| 58 | risk-register | Новые риски по SLO/cost, AI-risk, multi-tenancy; митигации. |

---

## Порядок выполнения

Рекомендуемый порядок по зависимостям:

1. **Блоки 1–2** — Intent (LLM, адаптивность, раннее завершение, свободный ответ) и API intent/experience.
2. **Блок 3** — SBOM/signature в реестре.
3. **Блоки 4–5** — SLO gate, наблюдаемость, cost budgets в рантайме.
4. **Блок 6** — Окружения (ADR и реализация).
5. **Блоки 7–8** — RAG, eval, SLSA, AI-risk.
6. **Блоки 9–10** — GitOps CD, multi-tenancy, self-serve.
7. **Блоки 11–14** — Preference Model, WS1/WS2/WS3 v1.
8. **Блоки 15–16** — Artifact manifest, session, референсы.
9. **Блоки 17–18** — Тесты и документация (параллельно и в конце).

Скрипт пайплайна может идти по шагам 1–58 или группировать часть шагов в один вызов Codex.

---

## Связь с другими документами

- [proper-implementation-plan.md](proper-implementation-plan.md) — детализация P0–P6.
- [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md) — предыдущие волны roadmap (WS0–WS4).
