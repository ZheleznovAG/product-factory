# Грамотная настройка GitHub для фабрики

Чтобы фабрика создавала репозитории на GitHub и пушила в них код, нужно один раз настроить токен и при необходимости владельца. Токен в код и в репозиторий не кладём — только в переменные окружения или в файл `.env`, который не коммитится.

---

## 1. Получить токен (GitHub)

1. Откройте **GitHub → Settings** (ваш профиль) → **Developer settings** → **Personal access tokens** (или прямая ссылка: https://github.com/settings/tokens).
2. **Generate new token** (классический) или **Generate new token (fine-grained)**.
3. Укажите:
   - **Scope:** достаточно **repo** (создание репозиториев и push). Дополнительные права (admin, workflow, delete_repo и т.д.) фабрике не нужны.
   - Срок действия (например 90 дней или без срока для локальной фабрики).
4. Сгенерируйте и **скопируйте токен один раз** — потом он не показывается. Формат: `ghp_...` (classic) или `github_pat_...` (fine-grained).

---

## 2. Где не хранить токен

- **Не** коммитить в git: ни в коде, ни в `.env` внутри репозитория (файл `.env` должен быть в `.gitignore`).
- **Не** писать в Dockerfile и не хардкодить в скрипты, которые попадают в репо.
- **Не** логировать и не выводить в ответах API.

В репозитории лежит только **.env.example** с подсказками (без реальных значений).

---

## 3. Как передать токен в фабрику

### Вариант A: Файл `.env` (удобно для compose)

В корне репозитория (или рядом с `docker-compose.yml`):

```bash
cp .env.example .env
# Отредактируйте .env: раскомментируйте и подставьте свои значения
# GITHUB_TOKEN=ghp_ваш_токен
# GITHUB_OWNER=ваш_логин
```

Убедитесь, что `.env` в `.gitignore`. Дальше в compose можно подхватить переменные из файла (см. ниже).

### Вариант B: Docker Compose

В `deploy/docker-compose.yml` у сервиса `factory` в секции `environment` добавьте (значения можно подставлять из `.env`):

```yaml
factory:
  environment:
    # ... существующие переменные ...
    GITHUB_TOKEN: ${GITHUB_TOKEN}
    GITHUB_OWNER: ${GITHUB_OWNER:-}
```

Запуск с подгрузкой `.env` из текущей директории:

```bash
cd deploy
docker compose --env-file ../.env up -d
```

Или экспортируйте переменные в shell и запустите compose из корня:

```bash
export GITHUB_TOKEN=ghp_ваш_токен
export GITHUB_OWNER=ваш_логин
docker compose -f deploy/docker-compose.yml up -d
```

### Вариант C: Один контейнер (docker run)

Токен передаётся только через переменные окружения:

```bash
docker run -d -p 8080:8080 \
  -v "$(pwd)/archetypes:/app/archetypes:ro" \
  -v "$(pwd)/workspace:/app/workspace" \
  -e GITHUB_TOKEN=ghp_ваш_токен \
  -e GITHUB_OWNER=ваш_логин \
  product-factory
```

Более безопасный вариант — файл с секретами не в репо (например `~/.product-factory.env`):

```bash
# Создайте файл с одной строкой: GITHUB_TOKEN=ghp_...
docker run -d -p 8080:8080 \
  -v "$(pwd)/archetypes:/app/archetypes:ro" \
  -v "$(pwd)/workspace:/app/workspace" \
  --env-file ~/.product-factory.env \
  product-factory
```

---

## 4. GITHUB_OWNER

| Ситуация | GITHUB_OWNER |
|----------|--------------|
| Репо под вашим пользователем | Пусто или ваш логин GitHub. |
| Репо в организации | Логин организации (у токена должны быть права на создание репо в этой организации). |

Если не указать, фабрика создаёт репо от имени пользователя, которому принадлежит токен, и при push подставляет owner из API.

---

## 5. Проверка

1. Запустите фабрику с `GITHUB_TOKEN` и при необходимости `GITHUB_OWNER` (любой из способов выше).
2. Отправьте запрос:
   ```bash
   curl -s -X POST http://localhost:8080/factory/run \
     -H "Content-Type: application/json" \
     -d '{"goal":"Test GitHub","constraints":[],"target_stack":"web-app"}'
   ```
3. Возьмите `runId` из ответа и запросите запись о run:
   ```bash
   curl -s "http://localhost:8080/factory/runs/ВАШ_RUN_ID"
   ```
4. В ответе должно быть поле **repoUrl** со ссылкой на созданный репозиторий на GitHub (например `https://github.com/ваш-логин/pf-xxx`). Код уже в ветке `main`.

Если **repoUrl** пустой — фабрика не получила токен (проверьте переменные в контейнере: `docker exec <container> env | grep GITHUB`) или произошла ошибка при создании/пуше (смотрите audit log).

---

## 6. Краткий чеклист

- [ ] Токен создан на GitHub (scope **repo** — этого достаточно для создания репо и push).
- [ ] Токен не в коде и не в закоммиченном файле; используется `.env` или `export` / `--env-file`.
- [ ] В compose или в `docker run` заданы `GITHUB_TOKEN` и при необходимости `GITHUB_OWNER`.
- [ ] Фабрика запущена с архетипами (volume `archetypes`).
- [ ] После run в `GET /factory/runs/{runId}` есть **repoUrl**.

См. также: [runbook.md](runbook.md#github-создание-репозитория-и-push), [deploy/README.md](../deploy/README.md), [.env.example](../.env.example).
