# Провижининг раннеров и headless browser smoke для web-app

Цель: стандартизовать запуск раннеров (CI/scheduler/tool-runner) и добавить воспроизводимый browser smoke для архетипа `web-app` без добавления Node/Python в рантайм фабрики.

Связанные документы: [runbook.md](runbook.md), [scheduled-runner-howto.md](scheduled-runner-howto.md), [environments.md](environments.md), [prohibited-agent-actions.md](prohibited-agent-actions.md).

## 1. Топология раннеров

| Контур | Назначение | Где выполняется |
|---|---|---|
| CI runner | Build/test/supply-chain/smoke для архетипов | GitHub-hosted `ubuntu-latest` или self-hosted Linux |
| Scheduler runner | Периодический запуск Grand Pipeline | Хост с `python3 scripts/scheduled_pipeline_runner.py` |
| Tool runner | Изолированный запуск `create_repo_from_archetype` и `apply_patch` | Отдельный Docker-контейнер `product-factory-tool-runner` |
| Browser smoke runner | Smoke UI-проверка `web-app` (DOM/title) | Временный контейнер Playwright |

## 2. Провижининг раннеров

### 2.1 CI runner (Linux + Docker)

Минимум для self-hosted:
- Linux x86_64, Docker Engine, `git`, `curl`.
- Метки runner: `pf`, `docker`, `browser`.
- Доступ к registry (`ghcr.io`) для pull/push release-образов.

Рекомендация: разделить очереди:
- `pf-build` (build/test/supply-chain);
- `pf-browser` (browser smoke);
- `pf-maintenance` (планировщик и служебные задачи).

### 2.2 Scheduler runner (Grand Pipeline)

Базовый запуск:
```bash
python3 scripts/scheduled_pipeline_runner.py --allow-docker
```

Режим systemd (рекомендуется):
```ini
[Unit]
Description=Product Factory Scheduled Runner
After=network.target docker.service

[Service]
Type=simple
WorkingDirectory=/opt/ProductFactory
ExecStart=/usr/bin/python3 scripts/scheduled_pipeline_runner.py --allow-docker
Restart=always
RestartSec=30
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
```

Операционные файлы:
- state: `scripts/.grand_pipeline_state.json`;
- lock: `scripts/.pipeline_run.lock`;
- лог: `logs/scheduled_runner.log` (если запуск через `nohup`).

### 2.3 Tool runner (изолированный Docker executor)

Сборка:
```bash
docker build -f deploy/Dockerfile.tool-runner -t product-factory-tool-runner:latest .
```

Подключение к фабрике:
```bash
docker run -p 8080:8080 -v /var/run/docker.sock:/var/run/docker.sock \
  -e FACTORY_TOOL_RUNNER=docker \
  -e DOCKER_IMAGE_TOOL_RUNNER=product-factory-tool-runner:latest \
  -v "$(pwd)/workspace:/app/workspace" \
  -v "$(pwd)/archetypes:/app/archetypes" \
  product-factory:latest
```

## 3. Интеграция headless browser smoke для `web-app`

Принцип: health-smoke остаётся обязательным, browser-smoke добавляется как следующий шаг для `web-app`.

### 3.1 Локально (через Docker Playwright)

1. Поднять `web-app`:
```bash
docker build -t web-app:smoke archetypes/web-app
docker run -d --rm -p 18081:8080 --name web-app-smoke web-app:smoke
```

2. Проверить readiness:
```bash
./scripts/smoke-staging.sh http://127.0.0.1:18081/health/ready
```

3. Проверить UI в headless Chromium:
```bash
docker run --rm --network host mcr.microsoft.com/playwright:v1.51.0-jammy \
  bash -lc 'BASE_URL="http://127.0.0.1:18081/" node -e "
    const { chromium } = require(\"playwright\");
    (async () => {
      const browser = await chromium.launch({ headless: true });
      const page = await browser.newPage();
      await page.goto(process.env.BASE_URL, { waitUntil: \"domcontentloaded\", timeout: 15000 });
      const title = await page.title();
      const h1 = (await page.textContent(\"h1\"))?.trim();
      if (title !== \"Web App\") throw new Error(`Unexpected title: ${title}`);
      if (h1 !== \"Web App\") throw new Error(`Unexpected h1: ${h1}`);
      await browser.close();
      console.log(\"Browser smoke passed\");
    })().catch((e) => { console.error(e); process.exit(1); });
  "'
```

4. Остановить контейнер:
```bash
docker stop web-app-smoke
```

### 3.2 GitHub Actions (пример шага)

Добавить после `Run container and smoke test` в `.github/workflows/ci-archetype-web-app.yml`:

```yaml
      - name: Browser smoke (headless Chromium)
        run: |
          docker run --rm --network host mcr.microsoft.com/playwright:v1.51.0-jammy \
            bash -lc 'BASE_URL="http://127.0.0.1:18081/" node -e "
              const { chromium } = require(\"playwright\");
              (async () => {
                const browser = await chromium.launch({ headless: true });
                const page = await browser.newPage();
                await page.goto(process.env.BASE_URL, { waitUntil: \"domcontentloaded\", timeout: 15000 });
                const title = await page.title();
                const h1 = (await page.textContent(\"h1\"))?.trim();
                if (title !== \"Web App\") throw new Error(`Unexpected title: ${title}`);
                if (h1 !== \"Web App\") throw new Error(`Unexpected h1: ${h1}`);
                await browser.close();
              })().catch((e) => { console.error(e); process.exit(1); });
            "'
```

Примечание: Node и браузеры живут только в CI-контейнере Playwright; Docker-образ фабрики не меняется.

## 4. Runbook: эксплуатация и диагностика

Порядок выполнения smoke для `web-app`:
1. `health/ready` (L4/L7 и readiness);
2. headless browser smoke (DOM/title, базовый user-flow);
3. публикация образа/промоушен только после двух зелёных проверок.

Частые сбои:
- `ERR_CONNECTION_REFUSED`: контейнер `web-app` не поднялся или порт занят.
- `Timeout 15000ms`: приложение отвечает медленно; проверить логи контейнера и readiness.
- `Unexpected title/h1`: регресс шаблона страницы или отдана не та версия образа.

Команды диагностики:
```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
docker logs web-app-smoke --tail 200
curl -i http://127.0.0.1:18081/
curl -i http://127.0.0.1:18081/health/ready
```

## 5. Границы и безопасность

- Агентный слой только документирует/предлагает изменения; side-effects выполняются через Tool Executor и policy.
- Browser smoke не расширяет периметр привилегий фабрики: это отдельный CI/runtime-контур.
- Секреты не передавать в browser smoke-командах; только env из CI secret store.
