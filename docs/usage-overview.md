# Как это всё работает и как использовать

Единый обзор: что такое фабрика, в каких режимах она может работать, как её запускать и что вы получаете на выходе.

---

## Что это такое

**Product Factory** — сервис (Kotlin/Ktor в одном Docker-образе), который по запросу создаёт артефакты «продукта»: репозиторий с кодом из выбранного архетипа (catalog-service, web-app и т.д.), при необходимости — с доработками от LLM и выгрузкой в GitHub или S3.

- Вы шлёте **один запрос** — `POST /factory/run` с целью (goal), ограничениями (constraints), опционально target_stack и budget.
- Фабрика проходит **workflow**: проверка политики (OPA) → планировщик (stub или LLM) → кодоген (stub или LLM) → вызов **tools** (create_repo_from_archetype, при наличии токена — create_github_repo, push_repo_to_github, при наличии патчей — apply_patch) → тесты/security (заглушки) → фиксация в артефакт-реестре.
- Все побочные эффекты (создание репо, push, загрузка в S3) идут **только через Tool Executor**; политики и при необходимости human approval контролируют, что разрешено.

Подробнее об архитектуре: [solution_design.md](solution_design.md). О том, как убедиться, что всё работает: [how-it-works-and-verify.md](how-it-works-and-verify.md).

---

## В каких режимах может работать

| Режим | Что задаёте | Что происходит |
|-------|-------------|-----------------|
| **Только stub (без LLM)** | Запуск без `NEURAL_SERVICE_URL` | Планировщик и кодоген — встроенные заглушки. Репо создаётся копированием архетипа, без правок по патчам. Подходит для проверки пайплайна и CI. |
| **С LLM (нейросервис)** | `NEURAL_SERVICE_URL` (и при необходимости API key) | Планировщик и кодоген вызывают LLM (OpenAI, Codex, Ollama и т.д. через единый API). Могут возвращаться патчи — тогда вызывается apply_patch. |
| **Без GitHub** | Не задаёте `GITHUB_TOKEN` | Репозиторий только внутри фабрики: директория `workspace/pf-<runId>/`. На хосте видна, только если смонтирован volume. |
| **С GitHub** | `GITHUB_TOKEN` (и при необходимости `GITHUB_OWNER`) | После create_repo_from_archetype фабрика создаёт репо на GitHub и пушит туда код (create_github_repo → push_repo_to_github). |
| **Без S3/MinIO** | Не задаёте `ARTIFACT_STORAGE_BUCKET` | Нет выгрузки артефакта в объектное хранилище. |
| **С S3/MinIO** | `ARTIFACT_STORAGE_BUCKET` и endpoint/ключи | После создания репо фабрика упаковывает его в ZIP и загружает в бакет; в результате — поле `artifact_location` (s3://…). |
| **Без OPA** | Не задаёте `OPA_URL` или OPA недоступен | Политика «allow» по умолчанию. |
| **С OPA** | `OPA_URL` указывает на работающий OPA | Разрешение/запрет и require_human_approval по правилам из `policies/opa/`. |

Комбинации любые: например «stub + GitHub» (репо только копируется из архетипа и пушится в GitHub) или «LLM + MinIO без GitHub» (репо с патчами от LLM и выгрузка в S3).

---

## Варианты ввода и вывода (универсальная система)

Чтобы фабрика была универсальной, вход и выход можно представлять в разных форматах и каналах. Ниже — что есть сейчас и во что это можно развить.

### Ввод (как задать задачу)

| Вариант | Статус | Описание |
|--------|--------|----------|
| **HTTP API (JSON)** | **Реализовано** | Основной способ: `POST /factory/run` с телом `FactoryRunRequest`: `goal`, `constraints`, `target_stack`, `budget`, опционально `productSpec`, `contracts`. Универсален для интеграций (скрипты, CI, другие сервисы). |
| **Минимальный Web-UI решений** | **Реализовано** | `GET /factory/ui`: операторская страница для human-in-the-loop (показ `plan/risks/options`, отправка `answer`, `approve/reject`). Работает как thin-wrapper над API, без отдельного frontend-runtime. |
| **Пять YAML (контракты)** | **Частично** | Канонический контракт платформы — пять файлов в директории: `product.yaml`, `constraints.yaml`, `quality_profile.yaml`, `risk_profile.yaml`, `target_stack.yaml` (схемы в `contracts/schemas/`, apiVersion: productfactory.io/v1). В `quality_profile.sloGate` задаются пороги runtime SLO gate (время генерации, доля policy-deny, success rate): перед финальным release шагом фабрика проверяет эти метрики и блокирует переход в `DONE` при нарушении. При заданном `FACTORY_CONTRACTS_DIR` фабрика при старте/шаге валидирует эту директорию. Сейчас workflow питается в основном из HTTP-запроса (goal, constraints, target_stack); полное чтение запроса из YAML (один run = один набор YAML) — возможное расширение. |
| **CLI** | **Реализовано** | Есть потребительский CLI без `curl`: `product-factory run` (запуск), `product-factory status` (статус по runId), `product-factory intent` (оценка intent, выбор варианта, опциональный запуск run), плюс `product-factory validate <директория>`. |
| **API профиля предпочтений** | **Реализовано (опционально)** | При `PROFILE_STORE_ENABLED=true`: `PUT/GET/DELETE /factory/profiles/{profileId}` для хранения `embedding + rules` с обязательным consent (по `PROFILE_REQUIRE_CONSENT`). |
| **Файл/директория (upload)** | Не реализовано | Принять ZIP или путь к директории с YAML/JSON и запустить run. Удобно для «загрузил спецификацию — получил артефакт». |
| **Очередь (SQS, Kafka и т.д.)** | Не реализовано | Подписка на очередь сообщений; каждое сообщение — запрос на run. Масштабирование и отложенный запуск. |
| **Webhook/триггер** | Не реализовано | Внешняя система вызывает фабрику по событию (например, создание issue, merge в ветку). |

Итого: **сейчас универсальный вход — HTTP JSON**; контракты в виде пяти YAML задают стандарт и валидируются при наличии `FACTORY_CONTRACTS_DIR`; остальное — варианты развития.

### Вывод (как получить результат и ссылки)

| Вариант | Статус | Описание |
|--------|--------|----------|
| **Синхронный ответ API** | **Реализовано** | `POST /factory/run` возвращает сразу: `runId`, `status` (accepted/rejected), `message`. Подходит для автоматизации: по `runId` дальше запрашивать детали. |
| **Запись о run (GET /factory/runs/{runId})** | **Реализовано** | Единая запись по run: `runId`, `workflowState`, `updatedAt`, `repositoryVersion`, `imageVersion`, `sbomVersion`, `signatureVersion`, **repoUrl** (ссылка на GitHub), **artifactLocation** (s3://… при выгрузке). Универсальный «выход» для интеграций: UI, скрипты, каталог артефактов. |
| **Audit log (JSONL)** | **Реализовано** | Файл по `AUDIT_LOG_PATH`: все события по runId (state_changed, tool_call_executed, policy_check и т.д.). Нужен для отладки, комплаенса, воспроизводимости. |
| **Метрики (Prometheus)** | **Реализовано** | `GET /metrics` — счётчики runs, duration, tool_calls, llm_calls, токены. Интеграция с Prometheus/Grafana (profile observability). |
| **Webhook/callback по завершении** | Не реализовано | URL в запросе или в конфиге: при DONE/FAILED фабрика вызывает URL с payload (runId, status, repoUrl, artifactLocation). Удобно для уведомлений и пайплайнов. |
| **SSE/стриминг** | Не реализовано | Длинное соединение: фабрика шлёт события (state_changed, tool_call, итог) по мере выполнения. Удобно для UI в реальном времени. |
| **Экспорт в каталог/регистр** | Не реализовано | Публикация метаданных артефакта (runId, repoUrl, версия, SBOM) в внешний каталог или registry для поиска и повторного использования. |

Итого: **универсальный выход сейчас — GET /factory/runs/{runId}** (все ссылки и метаданные в одной записи) плюс audit и метрики; webhook и стриминг — естественные расширения для универсальности.

### Сводка: как сделать систему универсальной

- **Ввод:** сохранить HTTP JSON как основной канал; при необходимости добавить приём директории с пятью YAML (или ZIP) как альтернативный вход; опционально — очередь или webhook-триггер.
- **Вывод:** сохранить синхронный ответ + GET runs/{runId} как основной способ получить ссылки; при необходимости добавить webhook/callback и/или SSE для уведомлений и UI в реальном времени; при необходимости — экспорт в каталог артефактов.

Текущей реализации достаточно для сценариев «один запрос → один run → ссылка на репо/артефакт»; расширения выше позволяют встроить фабрику в разные контуры (портал, CI, очереди, уведомления).

---

## Как запускать

**Фабрику всегда запускаем через Docker.** Локальный Gradle — только для сборки образа и тестов.

### Минимальный запуск (проверка, что фабрика жива)

```bash
docker build -t product-factory .
docker run -p 8080:8080 product-factory
```

После этого `curl http://localhost:8080/health` даёт `{"status":"ok"}`. Прогоны с созданием репо **не** заработают: в образе нет директории `archetypes/`.

### Прогоны с созданием репо (архетипы на хосте)

Нужно смонтировать архетипы и при желании — каталог для audit/реестра/workspace:

```bash
docker run -p 8080:8080 \
  -v "$(pwd)/archetypes:/app/archetypes:ro" \
  -v "$(pwd)/data:/app/data" \
  -e AUDIT_LOG_PATH=/app/data/audit.log \
  -e ARTIFACT_REGISTRY_DIR=/app/data/artifact-registry \
  -e FACTORY_WORKSPACE_DIR=/app/workspace \
  -v "$(pwd)/workspace:/app/workspace" \
  product-factory
```

Тогда при успешном run в `./workspace/` появится директория `pf-<runId>/` с копией архетипа, в `./data/artifact-registry/` — JSON по runId, в `./data/audit.log` — лог событий.

### Полный стек (фабрика + MinIO + OPA)

Удобно поднимать одним compose:

```bash
docker compose -f deploy/docker-compose.yml up -d
```

Поднимаются: фабрика (8080), MinIO (9000, консоль 9001), OPA (8181). Фабрика получает архетипы из volume, при настроенных `ARTIFACT_STORAGE_*` загружает артефакт в MinIO. Дальше — как в [runbook.md](runbook.md).

### С нейросервисом (LLM)

- Либо поднять **neural-gateway** и указать фабрике его URL:
  ```bash
  docker compose -f deploy/docker-compose.yml --profile with-neural up -d
  ```
  (в compose уже прописано `NEURAL_SERVICE_URL=http://neural-gateway:8090` для фабрики.)

- Либо свой gateway на хосте (например `scripts/neural_gateway/`) и при запуске фабрики: `-e NEURAL_SERVICE_URL=http://host.docker.internal:8090`.

### С GitHub

При запуске фабрики (docker run или compose) добавить:

```bash
-e GITHUB_TOKEN=ghp_...
-e GITHUB_OWNER=my-org   # опционально, для организации
```

Тогда при каждом успешном run будет создаваться репо на GitHub и в него пушиться код (имя репо: `pf-<runId>`).

### Метрики и дашборды

- Сырые метрики: `curl http://localhost:8080/metrics`.
- Prometheus + Grafana:
  ```bash
  docker compose -f deploy/docker-compose.yml --profile observability up -d
  ```
  Prometheus: http://localhost:9090, Grafana: http://localhost:3000 (admin/admin), data source Prometheus — `http://prometheus:9090`.

Подробности по всем переменным и сценариям: [runbook.md](runbook.md), [deploy/README.md](../deploy/README.md).

---

## Как использовать (сценарии)

### 1. Быстрая проверка «фабрика жива и один run проходит»

1. Запустить фабрику с архетипами (см. выше).
2. Выполнить:
   ```bash
   ./scripts/run_live_run.sh
   ```
   Скрипт делает POST /factory/run, проверяет 200 и accepted, запрашивает GET /factory/runs/{runId}. Этого достаточно для CI и smoke-test.

### 2. Получить репозиторий на диске (без GitHub и S3)

1. Запуск с монтированием `archetypes` и `workspace` (и при желании data для audit/реестра), как в примере выше.
2. Запрос:
   ```bash
   curl -s -X POST http://localhost:8080/factory/run \
     -H "Content-Type: application/json" \
     -d '{"goal":"API service for X","constraints":[],"target_stack":"web-app"}'
   ```
3. В ответе — `runId`. Репозиторий — в `./workspace/pf-<runId>/`, запись о run — в `GET /factory/runs/<runId>` и в `artifact-registry/<runId>.json`.

### 3. Ввести задачу → система разработает, зальёт код в Git и выдаст ссылку на артефакт

Цель: один запрос с формулировкой задачи → фабрика создаёт репо из архетипа (при наличии LLM — с доработками), пушит в GitHub и в ответе/реестре есть ссылка на репо и при наличии — на артефакт в S3.

**Шаг 1. Запуск фабрики с GitHub и архетипами**

```bash
docker run -d -p 8080:8080 \
  -v "$(pwd)/archetypes:/app/archetypes:ro" \
  -e GITHUB_TOKEN=ghp_ВАШ_ТОКЕН \
  -e GITHUB_OWNER=ваш-логин-или-орг \
  product-factory
```

Либо полный стек: `docker compose -f deploy/docker-compose.yml up -d`, в `environment` сервиса factory задать `GITHUB_TOKEN` и при необходимости `GITHUB_OWNER`. См. [runbook.md](runbook.md#github-создание-репозитория-и-push).

**Шаг 2. Отправить задачу (один запрос)**

```bash
curl -s -X POST http://localhost:8080/factory/run \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Небольшой API-сервис для каталога товаров",
    "constraints": ["Kotlin", "Ktor"],
    "target_stack": "catalog-service"
  }'
```

В ответе будет что-то вроде:
```json
{"runId":"abc-123-...","status":"accepted","message":"Workflow completed with sandbox tool executor."}
```

**Шаг 3. Получить ссылку на репо и артефакт**

По `runId` запросить запись о run — в ней будут поля **repoUrl** (ссылка на GitHub) и при выгрузке в S3 — **artifactLocation**:

```bash
RUN_ID="подставьте_runId_из_ответа"
curl -s "http://localhost:8080/factory/runs/$RUN_ID"
```

Пример ответа:
```json
{
  "runId": "abc-123-...",
  "workflowState": "DONE",
  "updatedAt": "2026-02-20T...",
  "repositoryVersion": "repo:product-factory/pf-abc-123:v1",
  "repoUrl": "https://github.com/ваш-логин/pf-abc-123",
  "artifactLocation": "s3://artifacts/..."
}
```

- **repoUrl** — ссылка на созданный репозиторий на GitHub (код уже запушен в main).
- **artifactLocation** — есть только если настроены MinIO/S3 и бакет; тогда там ссылка на ZIP-артефакт в хранилище.

Итого: вы вводите задачу в одном запросе, фабрика выполняет workflow (архетип ± LLM), создаёт репо на GitHub и пушит код; ссылка на репо (и при наличии — на артефакт) лежит в `GET /factory/runs/{runId}` в полях **repoUrl** и **artifactLocation**.

### 4. Получить артефакт в S3/MinIO

1. Запуск с полным compose (фабрика + MinIO) и заданными `ARTIFACT_STORAGE_BUCKET`, endpoint и ключами (в compose они уже прописаны для MinIO).
2. Создать бакет `artifacts` в MinIO (консоль http://localhost:9001 или mc).
3. Выполнить run. В audit/tool result будет `artifact_location` (s3://…). ZIP репо лежит в бакете.

### 5. Использовать LLM для планирования и кодогена

1. Запустить neural-gateway (compose profile with-neural или свой) и убедиться, что фабрика видит его: `curl http://localhost:8080/health/neural` → `"neural_service":"ok"`.
2. Делать POST /factory/run с осмысленным goal. Планировщик и кодоген ответят через LLM; при возврате патчей они применятся к репо (apply_patch).

### 6. Human-in-the-loop (approval)

Если OPA возвращает `require_human_approval`, run завершится со статусом rejected и в хранилище approvals появится запрос. Дальше:

- Посмотреть: `GET /factory/approvals/<runId>`.
- Одобрить: `POST /factory/approvals/<runId>/approve` с телом `{"decidedBy":"operator","comment":"ok"}`.
- Повторить run: `POST /factory/runs/<runId>/retry` с тем же телом, что и исходный запрос.

Подробно: раздел «Human-in-the-loop approvals» в [runbook.md](runbook.md).

Для release-операций используется отдельный manual gate: workflow `.github/workflows/release.yml` останавливается на шаге `high-risk-manual-approval` (Environment `high-risk-release`) и продолжает релиз только после явного подтверждения оператором.

---

## Что вы получаете на выходе

| Результат | Когда появляется | Где смотреть |
|-----------|------------------|--------------|
| **Ответ API** | Всегда | Тело ответа POST /factory/run: runId, status (accepted/rejected), message. |
| **Запись о run** | При успешном прохождении до STAGED/DONE | GET /factory/runs/{runId}, файл в ARTIFACT_REGISTRY_DIR. |
| **Artifact manifest** | На шагах STAGED и DONE | `artifact-registry/<runId>.manifest.<state>.json` с `manifest.version`, `manifest.checksum` (SHA-256), `manifest.provenance` (`gitCommit`, `timestamp`). Для одного и того же набора YAML checksum должен совпадать. |
| **Директория-репо** | При успешном create_repo_from_archetype | Внутри контейнера: workspace/pf-<runId>; на хосте — только если смонтирован volume workspace. |
| **Репозиторий на GitHub** | При заданном GITHUB_TOKEN | GitHub: репо pf-<runId>, ветка main. |
| **Артефакт в S3/MinIO** | При заданных ARTIFACT_STORAGE_* и доступном хранилище | Бакет, путь в artifact_location в audit/tool result. |
| **Audit log** | Всегда | Файл AUDIT_LOG_PATH (JSONL по runId, eventType, payload). |
| **Метрики** | Всегда | GET /metrics; при profile observability — также Prometheus/Grafana. |

Без GitHub и без монтирования workspace «осязаемо» у вас есть только ответ API, запись в реестре и audit — этого достаточно для проверки работы пайплайна и CI.

---

## Куда смотреть дальше

- **Запуск, переменные, откат, OPA, approvals:** [runbook.md](runbook.md).
- **Архитектура и контракты:** [solution_design.md](solution_design.md).
- **Проверка работы (health, один прогон, audit):** [how-it-works-and-verify.md](how-it-works-and-verify.md).
- **Деплой, GitHub, MinIO, нейросервис:** [integrations-server-deployment.md](integrations-server-deployment.md), [deploy/README.md](../deploy/README.md).
