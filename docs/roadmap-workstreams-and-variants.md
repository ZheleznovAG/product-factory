# Roadmap: workstreams и варианты веток

Связь целевого направления развития (мульти-агенты, окружения, Intent Layer) с конкретными инкрементами без переписывания deterministic-ядра (runs / policy / tools / audit). Ниже — **целевые/долгосрочные workstreams, часть которых ещё не реализована**.

---

## 0. Сначала база (WS0)

**Варианты A и B имеют смысл только после того, как закрыта база — WS0 Kernel Hardening.** Иначе мульти-агенты и Intent Layer будут наращивать шаги и side-effects на неустойчивом фундаменте (run в памяти, без persist/retry, без стабильного e2e-стенда).

Порядок: **сначала доводим WS0 до MVP → затем выбираем ветку (A или B)** и планируем 6–8 недель по спринтам.

---

## 1. Зачем отдельные workstreams

Видения задают направление эволюции, а не «уже есть». Чтобы не делать «сразу всё», дорожная карта разбита на **5 параллельных workstreams** с чёткими MVP и v1. Ядро (Control Plane, OPA, Tool Executor, Audit) остаётся общим.

---

## 2. Пять workstreams

### WS0. Kernel Hardening (обязательная база)

**Зачем:** мульти-агенты и Intent Layer увеличат число шагов и side-effects; нужна устойчивость. **Нужен и для multi-agent, и для Intent.** Без WS0 варианты A/B не стартуют.

| Уровень | Deliverables |
|---------|--------------|
| **MVP** | Устойчивое состояние run (persist), идемпотентность tool-calls, resume/retry. E2E стенд (compose): factory + OPA + storage + stub neural. |

**Что уже есть в репо:** идемпотентность tool-calls (SandboxToolExecutor/DockerToolExecutor — in-memory store по idempotencyKey); compose с factory + OPA + MinIO (storage); audit в файл; state machine в памяти (WorkflowRunner). **Интеграция с Temporal:** при заданном TEMPORAL_ADDRESS фабрика поднимает Worker и выполняет run как Temporal Workflow (persist в истории Temporal, retry по шагам через activities). См. [runbook](runbook.md#режим-с-temporal-durable-workflow-ws0).  
**Чего не хватает до полного MVP:** формально зафиксированный e2e-стенд с Temporal (compose или отдельный compose для Temporal + сценарий «запрос → артефакт»); при работе без Temporal — по-прежнему нет persist/retry в процессе.

**Критерии Done WS0 MVP:** (1) состояние run сохраняется между перезапусками фабрики, возможен resume; (2) при падении шага workflow выполняет retry с последнего успешного (или явный отказ с записью в audit); (3) в репо описан и воспроизводим e2e-стенд (compose: factory + OPA + storage + при необходимости stub neural), с проверкой здоровья и одним сценарием «POST /factory/run → артефакт/статус».

---

### WS1. Multi-Agent Roles (динамические роли)

Цель: роли не только planner+codegen.

| Уровень | Deliverables |
|---------|--------------|
| **MVP** | Фиксированный набор ролей: Planner, Implementer, Tester, Reviewer. Протокол обмена артефактами между ролями (план → патчи → тест-репорт → вердикт). Policy/approval общие: каждый агент вызывает tools через тот же executor. |
| **v1** | Role registry + шаблоны промптов/правил на роль. Генерация ролей под задачу (под policy и с audit). |

Точки расширения в репо: контракты агентов, WorkflowRunner (шаги по ролям), RunContext.

---

### WS2. Environments (окружения)

Окружения для сборки и тестов: эмуляторы, браузеры, серверы, провижининг.

| Уровень | Deliverables |
|---------|--------------|
| **MVP** | Минимальный environment provider: `local-docker` (compose/kind), `remote-ssh` (подключение к машине, предоставленной человеком). Стандартизованный результат: логи/артефакты/скриншоты/метрики → в registry. |
| **v1** | Провижининг воркеров/раннеров. Browser/emulator runners (хотя бы headless browser для web-архетипов). |

Точки расширения: FACTORY_ENV, конфиг профилей, новый тип tool / env-provider.

---

### WS3. Human Loop (человек в контуре)

Не только approval, а спринты обсуждения, уточнения, выбор вариантов.

| Уровень | Deliverables |
|---------|--------------|
| **MVP** | Расширить approval до «вопросов/выборов»: `ask_user(question, options[]) → user_choice`. Один канал: HTTP API (позже Telegram/web). |
| **v1** | Веб-форма/чат: план, риски, варианты; сбор решений. Спринт-поинты: обязательные остановки между фазами. |

Точки расширения: ApprovalStore, API (approve/reject), новый тип события и API для ask/answer.

---

### WS4. Intent Layer + Protocol 90s

Цель: намерение → результат/переживание.

Фиксация MVP для WS4: **intent + build-mode**.
Иными словами, в MVP у WS4 один рантайм — текущий build-mode (репозиторий/артефакт как результат factory run).

| Уровень | Deliverables |
|---------|--------------|
| **MVP** | Новый входной контракт `intent.yaml` (Outcome / Experience / Constraints). Генерация intent из goal+constraints через диалог из 5 A/B вопросов. Рантайм один: **build-mode** (репо/артефакт как сейчас). Без сенсоров и без референсов. |
| **v1** | «6 карточек» (текст/скриншоты/сниппеты), выбор 2. Сохранение `reference_ids` в intent + audit «почему выбрано». |
| **v2+** | Stream-mode, IVM — отдельная большая ветка; ядро не меняется. |

Точки расширения: контракты (intent schema), новый endpoint или режим run по intent, протокол 90s в API/CLI.

---

## 3. Два варианта roadmap (ветка 1)

Два реалистичных варианта, **какой workstream вести первым после закрытия WS0**. До завершения базы (WS0 MVP) выбор A/B не обязателен; можно вести только WS0.

### Вариант A: Multi-agent + human loop, без Intent Engine

Порядок: **WS0 → WS1 → WS3 → WS2**.

1. **WS0** Kernel Hardening  
2. **WS1** Роли: Planner / Implementer / Tester / Reviewer  
3. **WS3** Вопросы пользователю (выбор варианта реализации, acceptance-критерии)  
4. **WS2** Окружение хотя бы `local-docker` для прогонов  

**Плюс:** быстрее превращает фабрику в «мини-команду».  
**Минус:** Intent → Reality остаётся отдельным пластом позже.

---

### Вариант B: Intent Layer MVP + один рантайм (build-mode)

Порядок: **WS0 → WS4 → WS3 → WS1 (минимально)**.

1. **WS0** Kernel Hardening  
2. **WS4** `intent.yaml` + протокол 90s в текстовом виде  
3. **WS3** Human loop (развилки A/B и подтверждение intent)  
4. **WS1** Роли минимально (planner + implementer), тестер позже  

**Плюс:** сразу минимальная реализация «Intent → Reality Engine» (без сенсоров/потока).  
**Минус:** мульти-агенты на старте проще.

---

## 4. Связь с этапами в architecture-and-path

| Workstream | Этап в architecture-and-path |
|------------|------------------------------|
| WS0 | Этапы 1–3 (уже частично сделаны); усиление: persist, retry, e2e стенд. |
| WS1 | Этап 4 «Мульти-агенты как роли». |
| WS2 | Этап 3 «Окружения» (профили + env provider). |
| WS3 | Этап 4 «Человек в контуре»; этап 5 UI/Console. |
| WS4 | Раздел «Видение: Intent → Reality Engine» (Intent Layer, протокол 90s, позже IVM). |

---

## 5. Практичный порядок (что делать сначала)

1. **WS0 (база)** — persist run, retry, e2e-стенд. Конкретно: хранение состояния run (файл/БД), восстановление и resume; retry при сбое шага; compose + скрипт/док «e2e: factory + OPA + storage + stub neural», критерии успеха.
2. **Полировка параллельно или сразу после базы:** B01 (health endpoint), B02 (валидация контрактов в CI), B04 (runbook: живые прогоны «запрос → артефакт»). Операционка и документация в порядке.
3. **После WS0 + полировки:** выбор варианта (A или B) и этап 4 (формализация шагов pipeline, QA/Security Agent или ADR), затем этап 3 (профили, бюджеты), дальше S01, API01, этап 5.

См. также: [architecture-and-path.md](architecture-and-path.md) (этапы и видения), [sprint-full-backlog.md](sprint-full-backlog.md) (полный бэклог задач).

---

## 6. Temporal/Argo: сразу или потом?

**Сразу внедрять не «плохо»** — если готовы к операционному весу (ещё один сервис/кластер, обучение, мониторинг), Temporal/Argo дают durable execution из коробки: persist, retry, история, восстановление. Тогда **WS0 можно реализовать через Temporal** вместо своего persist/retry в WorkflowRunner.

**Когда логично отложить:** один разработчик/небольшая команда, один стенд, редкие прогоны — свой минимальный persist (файл/SQLite) + retry в текущем коде быстрее вывести в прод и проверить гипотезы; перенос в Temporal — отдельный шаг, когда появятся требования к масштабу, длинным цепочкам или нескольким окружениям.

**Итог:** если цель — сразу заложить «правильную» основу под масштаб и не переписывать позже, внедрение Temporal/Argo в рамках WS0 — нормальный выбор. Если цель — быстрее закрыть базу и проверить мульти-агентов/Intent на текущем масштабе, свой persist + retry, потом при необходимости миграция на Temporal. В репо уже есть отсылка: [monolith-vs-services.md](monolith-vs-services.md) — «следующий шаг по архитектуре — подключить Temporal».
