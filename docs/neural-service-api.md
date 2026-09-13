# Единый API нейросервиса (LLM gateway)

Фабрика и тесты обращаются к «нейросервису» по одному контракту. **Различие «агент vs LLM» и как они связаны в фабрике** — в [agent-vs-llm-in-factory.md](agent-vs-llm-in-factory.md). **Где подключать, как управлять и масштабировать** — в [neural-service-operations.md](neural-service-operations.md). Для вызывающего кода **не важно**, кто за фасадом: OpenAI, self-hosted модель или хост с Codex-агентом — меняется только URL и при необходимости ключ.

## Контракт

- **Эндпоинт:** `POST {NEURAL_SERVICE_URL}/v1/chat/completions`
- **Формат:** совместим с [OpenAI Chat Completions](https://platform.openai.com/docs/api-reference/chat/create): тело с `model` (опционально), `messages` (обязательно). Ответ — `choices[0].message.content` или поток.
- **Авторизация:** опционально `Authorization: Bearer {NEURAL_SERVICE_API_KEY}` (если бэкенд требует).

Переменные окружения (фабрика / клиенты):

| Переменная | Описание |
|------------|----------|
| `NEURAL_SERVICE_URL` | Base URL сервиса (например `http://localhost:8090` или `https://api.openai.com`) |
| `NEURAL_SERVICE_API_KEY` | Ключ при необходимости (для OpenAI — ключ API; для своего шлюза — по желанию) |

Если не задана ни fallback matrix-конфигурация, ни `NEURAL_SERVICE_URL`, фабрика не вызывает внешний LLM (используются stub-планировщик и кодоген).

## LLM fallback matrix (primary/secondary/…)

`HttpNeuralServiceClient` поддерживает несколько OpenAI-совместимых провайдеров с приоритетами и таймаутами.  
При деградации primary (timeout, сетевой сбой, 5xx/4xx, невалидный ответ) запрос автоматически пробуется на следующем провайдере по приоритету.

Порядок источников конфигурации:
1. ENV с индексами `NEURAL_SERVICE_PROVIDER_<N>_*`
2. ENV `NEURAL_SERVICE_URL_PRIMARY` / `NEURAL_SERVICE_URL_SECONDARY`
3. YAML-файл из `NEURAL_SERVICE_FALLBACK_MATRIX_PATH`
4. Legacy single-provider: `NEURAL_SERVICE_URL` (+ `NEURAL_SERVICE_API_KEY`)

### ENV: матрица по индексам (рекомендуется для 2+ провайдеров)

| Переменная | Описание |
|------------|----------|
| `NEURAL_SERVICE_PROVIDER_1_URL` | URL провайдера #1 (обязательно для записи) |
| `NEURAL_SERVICE_PROVIDER_1_PRIORITY` | Приоритет (меньше = раньше, например `100`) |
| `NEURAL_SERVICE_PROVIDER_1_TIMEOUT_SECONDS` | Таймаут этого провайдера в секундах |
| `NEURAL_SERVICE_PROVIDER_1_API_KEY` | Ключ для этого провайдера |
| `NEURAL_SERVICE_PROVIDER_1_API_KEY_ENV` | Имя env-переменной, из которой брать ключ |
| `NEURAL_SERVICE_PROVIDER_1_MODEL` | Модель для этого провайдера |

Пример:

```bash
NEURAL_SERVICE_PROVIDER_1_NAME=primary
NEURAL_SERVICE_PROVIDER_1_URL=https://api.openai.com
NEURAL_SERVICE_PROVIDER_1_PRIORITY=100
NEURAL_SERVICE_PROVIDER_1_TIMEOUT_SECONDS=30
NEURAL_SERVICE_PROVIDER_1_API_KEY_ENV=OPENAI_API_KEY
NEURAL_SERVICE_PROVIDER_1_MODEL=gpt-4o-mini

NEURAL_SERVICE_PROVIDER_2_NAME=secondary
NEURAL_SERVICE_PROVIDER_2_URL=http://host.docker.internal:8090
NEURAL_SERVICE_PROVIDER_2_PRIORITY=200
NEURAL_SERVICE_PROVIDER_2_TIMEOUT_SECONDS=45
```

### ENV: быстрый primary/secondary

```bash
NEURAL_SERVICE_URL_PRIMARY=https://api.openai.com
NEURAL_SERVICE_API_KEY_PRIMARY=...
NEURAL_SERVICE_TIMEOUT_SECONDS_PRIMARY=30
NEURAL_SERVICE_PRIORITY_PRIMARY=100

NEURAL_SERVICE_URL_SECONDARY=http://host.docker.internal:8090
NEURAL_SERVICE_TIMEOUT_SECONDS_SECONDARY=45
NEURAL_SERVICE_PRIORITY_SECONDARY=200
```

### YAML: fallback matrix

```yaml
apiVersion: productfactory.io/v1
kind: NeuralFallbackMatrix
providers:
  - name: primary
    base_url: https://api.openai.com
    priority: 100
    timeout_seconds: 30
    api_key_env: OPENAI_API_KEY
    model: gpt-4o-mini
  - name: secondary
    base_url: http://host.docker.internal:8090
    priority: 200
    timeout_seconds: 45
```

```bash
NEURAL_SERVICE_FALLBACK_MATRIX_PATH=/app/config/neural-fallback.yaml
```

Если включена matrix-конфигурация, `NEURAL_SERVICE_URL` используется только как legacy fallback (когда matrix не задана).

## Варианты бэкенда

1. **OpenAI** — `NEURAL_SERVICE_URL=https://api.openai.com`, ключ в `NEURAL_SERVICE_API_KEY`. Клиентский код без изменений.
2. **Self-hosted** (Ollama, vLLM, LiteLLM и т.д.) — выставить URL сервера с OpenAI-совместимым `/v1/chat/completions`. Ключ обычно не нужен.
3. **Хост как сервис (Codex gateway)** — на той же машине поднимается обёртка над Codex CLI (`scripts/neural_gateway`), которая реализует тот же API и под капотом вызывает Codex (или проксирует в OpenAI / другой URL). Фабрике указываем `NEURAL_SERVICE_URL` на этот сервис (с хоста: `http://localhost:8090`; из Docker: `http://host.docker.internal:8090`). По ответам не видно, Codex это или облако.

**Совместимость:** клиент фабрики (HttpNeuralServiceClient) использует HTTP/1.1, чтобы запросы не вызывали ошибку «Unsupported upgrade request» у uvicorn. Gateway принимает тело запроса в формате OpenAI и при необходимости извлекает `messages` из сырого JSON для совместимости с разными клиентами.

## Gateway на хосте (Codex / proxy)

Скрипт в `scripts/neural_gateway/`: один сервер, режим задаётся переменными.

| Переменная | Значения | Описание |
|------------|----------|----------|
| `NEURAL_BACKEND` | `codex` \| `openai` \| `proxy` | Режим: Codex CLI, прокси в OpenAI, прокси на произвольный URL |
| `CODEX_PATH` | путь | Команда для Codex (по умолчанию `codex`) |
| `OPENAI_API_KEY` | ключ | Для режима `openai` |
| `PROXY_TARGET_URL` | URL | Для режима `proxy` — куда проксировать запросы |
| `NEURAL_GATEWAY_MAX_PROMPT_LENGTH` | число | Лимит длины промпта в режиме codex (по умолчанию 50000) |

Подробнее: `scripts/neural_gateway/README.md`.

## Нейронки и агенты других фирм (по API)

Подразумевалось так: фабрика вызывает **один контракт** — OpenAI-совместимый `POST …/v1/chat/completions`. Кто за этим контрактом — не важно.

**Варианты:**

1. **Провайдер сам даёт совместимый API**  
   Azure OpenAI, часть Google Vertex, многие self-hosted (vLLM, Ollama) уже отдают `/v1/chat/completions`. Выставляете `NEURAL_SERVICE_URL` на их URL, при необходимости ключ в `NEURAL_SERVICE_API_KEY` — фабрика просто ходит туда.

2. **Роутер/адаптер как единая точка входа**  
   Провайдер с другим API (Anthropic, свой агентский сервис и т.д.) ставится за **одним** шлюзом, который принимает запросы в формате OpenAI, по полю `model` или конфигу решает, в какой бэкенд отправить вызов, и отдаёт ответ в том же формате. Примеры: LiteLLM proxy, наш `scripts/neural_gateway` в режиме `proxy` или свой сервис с маршрутизацией по `model`. Фабрике задаётся один URL этого роутера — для неё это «нейросервис», внутри может быть несколько фирм.

3. **Разные фирмы для разных ролей (планировщик vs кодоген)**  
   Нужно явно: планировщик — один провайдер, кодоген — другой. **Предпочтительно:** один роутер (п.2), который по `model` выбирает бэкенд; в фабрике один клиент, в теле запроса передаётся разный `model`. **Альтернатива:** завести два URL в конфиге (например `NEURAL_SERVICE_URL_PLANNER` и `NEURAL_SERVICE_URL_CODEGEN`) и передавать в планировщик и кодоген разные экземпляры клиента — сейчас в коде один `NEURAL_SERVICE_URL`, при необходимости это расширяется.

Итого: «по API нейронку или агента других фирм» = либо провайдер уже совместим с OpenAI-форматом, либо перед ним ставится один слой (роутер/адаптер) с единым URL для фабрики. Смена фирмы/модели — без прав в коде, только конфиг и при необходимости настройка роутера.

## Использование в фабрике

- Планировщик и кодоген (будущие LLM-реализации) получают `NeuralServiceClient` с базовым URL из конфига и делают запросы к `/v1/chat/completions`. Подмена бэкенда — только конфиг (`NEURAL_SERVICE_URL` и при необходимости ключ).
