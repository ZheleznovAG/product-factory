# Neural Gateway

Единый OpenAI-совместимый API для фабрики и тестов. Бэкенд переключается переменными — по ответам не видно, идёт ли запрос в OpenAI, на self-hosted или на Codex на этом хосте.

## Установка

```bash
cd scripts/neural_gateway
pip install -r requirements.txt
```

## Переменные окружения

| Переменная | По умолчанию | Описание |
|------------|--------------|----------|
| `NEURAL_BACKEND` | `codex` | `codex` — вызов Codex CLI; `openai` — прокси в api.openai.com; `proxy` — прокси на PROXY_TARGET_URL |
| `CODEX_PATH` | `codex` | Команда Codex (путь или имя в PATH) |
| `OPENAI_API_KEY` | — | Нужен для режима `openai` |
| `PROXY_TARGET_URL` | — | Базовый URL для режима `proxy` (например self-hosted Ollama/LiteLLM) |
| `NEURAL_GATEWAY_PORT` | `8090` | Порт сервера |
| `NEURAL_GATEWAY_MAX_PROMPT_LENGTH` | `50000` | Макс. длина промпта в режиме codex (защита от DoS) |

## Запуск

```bash
# Хост как нейросервис (Codex)
export NEURAL_BACKEND=codex
uvicorn main:app --host 0.0.0.0 --port 8090

# Прокси в OpenAI
export NEURAL_BACKEND=openai
export OPENAI_API_KEY=sk-...
uvicorn main:app --host 0.0.0.0 --port 8090

# Прокси на свой URL (Docker/self-hosted)
export NEURAL_BACKEND=proxy
export PROXY_TARGET_URL=http://host.docker.internal:11434
uvicorn main:app --host 0.0.0.0 --port 8090
```

Проверка:

```bash
curl -s http://localhost:8090/health
curl -s -X POST http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Say hello in one word."}]}'
```

## Использование с фабрикой

В `.env` фабрики или окружении:

```bash
NEURAL_SERVICE_URL=http://localhost:8090
```

Фабрика будет вызывать `POST http://localhost:8090/v1/chat/completions`. Если gateway запущен с `NEURAL_BACKEND=codex`, запросы уйдут в Codex на этом хосте; при `openai` — в облако; при `proxy` — на указанный URL.

См. [docs/neural-service-api.md](../../docs/neural-service-api.md).
