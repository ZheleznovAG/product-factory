# Интеграции для серверного запуска (Git, хранилища, выгрузка)

Фабрика может работать как сервис в Docker на сервере. Сейчас артефакты создаются **только в локальной файловой системе** контейнера (каталог `workspace/`). Для реального использования с аккаунтами Git, файловыми хранилищами или API выгрузки нужны дополнительные интеграции.

---

## Текущее состояние

| Что | Есть? | Как |
|-----|-------|-----|
| **Создание артефакта** | Да | Tool `create_repo_from_archetype` копирует архетип в `workspace/<repo_name>/` внутри контейнера (или на хосте, если смонтирован volume). |
| **Сразу в хранилище (S3/MinIO)** | **Да** | Если задан `ARTIFACT_STORAGE_BUCKET`, после копирования архетипа фабрика упаковывает директорию в ZIP и загружает в бакет. В результате tool call появляется поле `artifact_location` (например `s3://bucket/artifacts/runId/repoName.zip`). Workspace может быть эфемерным. См. ниже «Переменные для S3». |
| **Аккаунты Git (GitHub/GitLab)** | Нет | Нет tool «создать репо в GitHub» или «push в удалённый репо». |
| **API для выгрузки** | Нет | Нет endpoint «скачать архив по runId»; ссылку на артефакт можно брать из audit log (событие `tool_call_executed`, в result — `artifact_location`). |

Итог: можно **сразу отдавать артефакт в S3/MinIO**, задав bucket и ключи в env; тогда volume для workspace не обязателен для сохранности (артефакт уже в хранилище).

---

## Что нужно для работы с Git

Чтобы фабрика создавала репозитории в GitHub/GitLab и пушила туда код:

1. **Tool «create_remote_repo» (или расширение create_repo_from_archetype)**  
   Вызов API провайдера (GitHub/GitLab): создать репо под аккаунтом/организацией, получить URL. Учетные данные — через env/секреты (токен с правами `repo`), не в коде.

2. **Tool «push_repo» (или «commit_and_push»)**  
   После копирования архетипа в `workspace/`: инициализировать git в директории, добавить remote (URL из шага 1), commit, push. Либо один tool «create_repo_from_archetype_and_push» с параметрами `git_provider`, `owner`, `repo_name`, `visibility`.

3. **Политики и approval**  
   В [tools-list-mvp.md](tools-list-mvp.md) уже заложено: `create_repo_from_archetype` и `commit_files` — write_limited, при необходимости `requires_human_approval`. Для создания репо в реальном Git лучше требовать approval или жёсткий allowlist владельцев/организаций (OPA).

4. **Конфиг**  
   Переменные окружения или конфиг: `GITHUB_TOKEN`, `GITLAB_TOKEN`, или URL/ключ для self-hosted Git. Документировать в [runbook.md](runbook.md), [secrets-policy.md](secrets-policy.md).

Реализация: новый tool в SandboxToolExecutor (или отдельный GitToolExecutor), контракт в `contracts/tools.schema.json`, вызов из WorkflowRunner по плану (или следующий шаг после create_repo_from_archetype).

---

## S3/MinIO — сразу отдавать артефакт в хранилище (реализовано)

Если при запуске фабрики заданы переменные окружения, артефакт после `create_repo_from_archetype` автоматически упаковывается в ZIP и загружается в S3-совместимое хранилище. В результате вызова tool в audit log и в `result` появляется `artifact_location` (URI вида `s3://bucket/artifacts/runId/repoName.zip`).

**Переменные окружения:**

| Переменная | Обязательность | Описание |
|------------|----------------|----------|
| `ARTIFACT_STORAGE_BUCKET` | да (для включения загрузки) | Имя бакета. Если задано — после создания репо выполняется загрузка. |
| `ARTIFACT_STORAGE_PREFIX` | нет | Префикс ключа в бакете (по умолчанию `artifacts`). Итоговый ключ: `{prefix}/{runId}/{repoName}.zip`. |
| `ARTIFACT_STORAGE_ENDPOINT` | нет (для AWS) | URL эндпоинта для MinIO (например `http://minio:9000`). Для AWS S3 не задавать. |
| `ARTIFACT_STORAGE_ACCESS_KEY` / `ARTIFACT_STORAGE_SECRET_KEY` | по необходимости | Ключи доступа. Можно использовать `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`. |
| `ARTIFACT_STORAGE_REGION` / `AWS_REGION` | по умолчанию us-east-1 | Регион (для AWS). Для MinIO можно не задавать. |

**Пример запуска с MinIO:**
```bash
docker run -p 8080:8080 \
  -e ARTIFACT_STORAGE_BUCKET=artifacts \
  -e ARTIFACT_STORAGE_ENDPOINT=http://minio:9000 \
  -e ARTIFACT_STORAGE_ACCESS_KEY=minioadmin \
  -e ARTIFACT_STORAGE_SECRET_KEY=minioadmin \
  -v "$(pwd)/archetypes:/app/archetypes" \
  product-factory:latest
```

После успешного run в событии audit `tool_call_executed` в поле `result` будет `artifact_location: "s3://artifacts/artifacts/<runId>/pf-<runId>.zip"`. Скачивание — через AWS CLI, MinIO Console или presigned URL (отдельно не реализовано).

---

## Что нужно для API выгрузки (без внешнего хранилища)

Если не хотите поднимать S3/Git сразу, минимум для сервера:

1. **Постоянный volume для workspace**  
   Запуск: `-v /data/factory-workspace:/app/workspace`. Тогда артефакты переживают перезапуск контейнера и доступны на хосте в `/data/factory-workspace/pf-<runId>/`.

2. **Endpoint «информация об артефакте»**  
   GET `/factory/runs/{runId}` или `/factory/runs/{runId}/artifact` — возвращает JSON: `repo_path` (внутри контейнера) или инструкцию «артефакт на хосте по пути …», если путь известен из конфига. Без отдачи файлов по HTTP — только метаданные и путь.

3. **Опционально: отдача архива по HTTP**  
   GET `/factory/runs/{runId}/artifact/archive` — читает `workspace/pf-<runId>/`, пакует в tar.gz/zip, отдаёт stream. Подходит для небольших артефактов; для больших лучше S3 + presigned URL.

---

## Приоритеты для «сервер в Docker»

| Приоритет | Интеграция | Зачем |
|-----------|------------|--------|
| 1 | Volume для `workspace/` на хосте | Чтобы артефакты не терялись при перезапуске и были доступны на сервере. |
| 2 | Git: создание репо + push | Пользователь получает ссылку на репо в GitHub/GitLab; CI уже может быть привязан к репо. |
| 3 | S3/MinIO + presigned URL или API «скачать архив» | Универсальная выгрузка без привязки к конкретному Git-провайдеру. |

Контракты и политики уже предполагают CI (github-actions), containerRegistry (ghcr.io) — см. [contracts-example/constraints.yaml](../contracts-example/constraints.yaml). Реализация tools под реальный Git и хранилища — следующий шаг после текущего MVP (копирование в workspace). Список инструментов и risk tier: [tools-list-mvp.md](tools-list-mvp.md).

---

## GitHub: доступ и токены для фабрики

**Проверка доступа:** если на хосте установлен GitHub CLI (`gh`), выполните `gh auth status`. Успешный логин и scope `repo` достаточно для создания репо и push. Проверка от имени пользователя: `gh api user --jq '.login'`.

**Откуда взять токен для фабрики** (чтобы сервис создавал репо и пушил код):

| Вариант | Как | Плюсы / минусы |
|--------|-----|-----------------|
| **Personal Access Token (PAT)** | GitHub → Settings → Developer settings → Personal access tokens → Generate. Права: `repo` (или Fine-grained: Repository access + Contents read/write, Metadata read). | Просто, один токен на аккаунт; ротация вручную; при утечке — доступ ко всем репо этого аккаунта. |
| **Токен из gh** | На машине, где уже выполнен `gh auth login`: `gh auth token` (вывести токен в stdout). Передать в фабрику через env, например `GITHUB_TOKEN=$(gh auth token)`. | Удобно для локального/ dev; тот же scope, что у gh (gist, read:org, repo, workflow и т.д.). Не подходит для продакшена (привязка к сессии пользователя). |
| **GitHub App** | Создать App в организации/аккаунте, установить, получать Installation Access Token (JWT → API). Фабрика использует этот токен для вызовов GitHub API. | Лучше для прода: ограниченные права (per repo или per org), отзыв через App, аудит. Требует реализации обмена JWT → installation token в фабрике или отдельном сервисе. |
| **OAuth App** | Пользователь логинится через OAuth, фабрика получает access token на его имя. | Нужно, если «фабрика создаёт репо от имени того, кто нажал кнопку». Для одного оператора фабрики обычно достаточно одного PAT или GitHub App. |

**Что передать в фабрику:** переменная окружения `GITHUB_TOKEN` (или имя секрета в K8s/Vault). В коде фабрики секрет не логировать; читать только в момент вызова Git API ([secrets-policy.md](secrets-policy.md), [ADR-0006](adr/0006-secrets-management-mvp.md)).

**Минимальные права для «создать репо + push»:** классический PAT — scope `repo`; Fine-grained — доступ к репозиториям (или All) с правами Contents (read/write), Metadata (read). Для GitHub App — те же права на уровне репо или организации.

---

## Использование готовой фабрики (варианты)

Когда фабрика развёрнута как сервис (Docker / K8s / docker-compose из `deploy/`), пользоваться ею можно так:

| Кто | Как | Секреты и конфиг |
|-----|-----|-------------------|
| **Оператор вручную** | `curl -X POST http://<factory>/factory/run -H "Content-Type: application/json" -d '{"goal":"...","constraints":[]}'`. Ответ — `runId`, статус; детали и `artifact_location` — в audit log. | Фабрика уже запущена с нужными env: `NEURAL_SERVICE_URL`, `ARTIFACT_STORAGE_*`, при появлении Git — `GITHUB_TOKEN`. Секреты задаются при деплое (env, K8s Secrets), не в запросе. |
| **Скрипт / CI (GitHub Actions и др.)** | Тот же `POST /factory/run`. В CI можно передать `goal` из issue/PR или из конфига; по `runId` проверить статус (audit или будущий GET `/factory/runs/{runId}`) и забрать артефакт (из S3 по `artifact_location` или по API). | Токен для вызова фабрики (если позже появится auth на API) — в секретах CI. Токен GitHub для фабрики — в окружении фабрики, не в CI. |
| **Портал / UI (позже)** | Frontend отправляет запрос на backend, backend вызывает `POST /factory/run`; пользователь видит статус и ссылку на репо/артефакт. | Секреты только на стороне фабрики (или backend); пользователь не передаёт Git-токен, только цель/ограничения. |
| **Другой сервис (оркестратор)** | То же API. Оркестратор может дергать фабрику по расписанию или по событию (webhook, очередь). | Аутентификация между сервисами — по принятой в инфраструктуре схеме (mTLS, API key, IAM). |

**Типовой сценарий «готовый сервис»:**  
1) Развернуть фабрику (образ + env: OPA_URL, NEURAL_SERVICE_URL, ARTIFACT_STORAGE_*, при Git — GITHUB_TOKEN).  
2) Создать бакет в MinIO (или указать S3), при Git — токен с правами `repo`.  
3) Вызывать `POST /factory/run` от имени оператора или из CI; результат смотреть в ответе и в audit, артефакт забирать по `artifact_location` или из workspace (если смонтирован volume).  
4) При появлении approval — вызывать `GET/POST /factory/approvals/{runId}` по runbook.
