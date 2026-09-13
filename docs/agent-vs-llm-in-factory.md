# Агент и LLM в фабрике: кто есть кто и как связано

Краткий разбор, чтобы не путать «агент» и «LLM» и понимать, как это устроено у нас.

---

## 1. В двух словах

| Термин | Что это у нас | Где в коде |
|--------|----------------|------------|
| **LLM** | Сервис, который по запросу (prompt/messages) возвращает текст. Не знает про фабрику, контракты, tools — просто «вопрос → ответ». | Доступ к нему: **NeuralServiceClient** (HTTP к `NEURAL_SERVICE_URL/v1/chat/completions`). За ним может быть OpenAI, Codex CLI через обёртку, Ollama и т.д. |
| **Агент** | Компонент фабрики с ролью «сгенерировать артефакт по контракту». Знает про goal, constraints, схемы, список tools. Может быть реализован **с LLM** или **без** (stub). | **AgentPlanner**, **AgentCodegen** — интерфейсы. Реализации: **StubAgentPlanner** / **LlmAgentPlanner**, **StubAgentCodegen** / **LlmAgentCodegen** (при NEURAL_SERVICE_URL подключаются LLM-реализации). |

**Итог:** LLM — это «движок генерации текста». Агент — это роль в фабрике («планировщик», «кодоген»); агент может использовать LLM внутри себя, а может не использовать (stub).

---

## 2. Схема связи

```
                    Фабрика (Control Plane)
    ┌─────────────────────────────────────────────────────────────┐
    │  WorkflowRunner                                             │
    │       │                                                     │
    │       │  «нужен план по goal/constraints»                   │
    │       ▼                                                     │
    │  ┌─────────────────┐                                        │
    │  │ AgentPlanner    │  ← это и есть «агент-планировщик»     │
    │  │ (интерфейс)     │                                        │
    │  └────────┬────────┘                                        │
    │           │                                                  │
    │     реализация?                                              │
    │           │                                                  │
    │  ┌────────┴────────┐                                        │
    │  │                 │                                        │
    │  ▼                 ▼                                        │
    │ StubAgentPlanner   LlmAgentPlanner                           │
    │ (без внешних       (внутри дергает LLM)                      │
    │  вызовов)                 │                                 │
    │                            │                                 │
    └────────────────────────────┼────────────────────────────────┘
                                 │
                                 │  HTTP: messages → /v1/chat/completions
                                 ▼
    ┌─────────────────────────────────────────────────────────────┐
    │  LLM / нейросервис (вне фабрики)                             │
    │  NeuralServiceClient → один URL (обёртка Codex, OpenAI, …)  │
    │  «Чёрный ящик»: на вход — JSON с messages, на выход — text  │
    └─────────────────────────────────────────────────────────────┘
```

- **WorkflowRunner** не знает, есть ли за агентом LLM или stub. Он просто вызывает `agentPlanner.generate(input)`.
- **LlmAgentPlanner** сам решает: отправить запрос в NeuralServiceClient (LLM), распарсить ответ, проверить по схеме; при ошибке — отдать результат **StubAgentPlanner** (fallback).
- **NeuralServiceClient** — единственная связь фабрики с «миром LLM». Кто за URL (Codex-обёртка, OpenAI, Ollama) — для фабрики не важно, важен только контракт запроса/ответа.

---

## 3. Как это организовано в коде

1. **Выбор «агент с LLM или без»**  
   В `Application.kt`: если задан `NEURAL_SERVICE_URL`, в WorkflowRunner передаются `LlmAgentPlanner(neuralClient)` и `LlmAgentCodegen(neuralClient)`, иначе оба stub. Один раз при старте.

2. **Откуда агент берёт LLM**  
   `LlmAgentPlanner` в конструкторе получает `NeuralServiceClient` (у нас это `HttpNeuralServiceClient` с `baseUrl = NEURAL_SERVICE_URL`). Все вызовы «в облако/Codex» идут только через этот клиент.

3. **Где живёт обёртка Codex**  
   Не внутри фабрики. Отдельный процесс (например `scripts/neural_gateway`): слушает порт, принимает запросы в формате OpenAI, под капотом вызывает Codex CLI и отдаёт ответ. Фабрика просто указывает на этот сервис как на `NEURAL_SERVICE_URL`.

4. **Codegen**  
   **LlmAgentCodegen** реализован: при `NEURAL_SERVICE_URL` вызывает `NeuralServiceClient`, при ошибке/невалидном JSON — fallback на `StubAgentCodegen`; в audit — `agent_codegen_call`.

---

## 4. Сводная таблица

| Вопрос | Ответ |
|--------|--------|
| LLM и агент — одно и то же? | Нет. LLM — сервис «вопрос→ответ». Агент — компонент фабрики с ролью (планировщик/кодоген); он может вызывать LLM. |
| Кто вызывает LLM? | Только агентские реализации, которые для этого предназначены (например LlmAgentPlanner). Через NeuralServiceClient. |
| Где «обёртка для Codex»? | Вне фабрики: отдельный сервис (наш gateway или ваш). Фабрика обращается к нему по NEURAL_SERVICE_URL как к любому OpenAI-совместимому API. |
| Можно без LLM? | Да. NEURAL_SERVICE_URL не задан → StubAgentPlanner и StubAgentCodegen, фабрика работает полностью детерминированно. |
| Один LLM на всё или разные? | Сейчас один URL на всё. При необходимости можно завести два (например для Planner и Codegen) через два разных NeuralServiceClient в конфиге. |

---

## 5. Codex CLI и агент Codex

**Да, вы правильно поняли.**

- **Codex** — это агент (или платформа агентов) на хосте: тот, кто реально выполняет задачу по промпту (генерирует текст, код и т.д.).
- **Codex CLI** — консольная утилита **управления** этими агентами: запуск задач, передача промпта, получение ответа. Команда вида `codex exec --cd ... --sandbox ... -- "промпт"`.

В нашей связке:
- **Нейросервис для фабрики** — это «что-то с HTTP API» по контракту OpenAI (`/v1/chat/completions`). Фабрика только дергает этот URL.
- **Обёртка (neural_gateway)** — превращает вызов фабрики в запуск **Codex CLI**: получил HTTP-запрос → взял текст из `messages` → выполнил `codex exec ... "текст"` → вернул stdout как ответ. То есть Codex CLI используется как способ **вызвать агента Codex** из нашего HTTP-сервиса.

Цепочка: **Фабрика → HTTP → gateway → Codex CLI → агент Codex → ответ.**

---

## 6. Варианты использования

| Вариант | Описание | Когда использовать |
|---------|----------|--------------------|
| **Gateway + Codex CLI на хосте** | Запускаем `scripts/neural_gateway` с `NEURAL_BACKEND=codex`. Gateway вызывает `codex exec` на этом же хосте. Фабрика (в Docker или на хосте) указывает `NEURAL_SERVICE_URL` на gateway (localhost или host.docker.internal:8090). | Нужен именно агент Codex для планировщика/кодогена; фабрика может быть в контейнере. |
| **Фабрика и gateway на одном хосте** | Фабрика и gateway рядом, `NEURAL_SERVICE_URL=http://localhost:8090`. Без Docker — оба процесса на одной машине. | Простая отладка, один хост. |
| **Фабрика в Docker, gateway на хосте** | Как в `run_factory_with_neural.sh`: gateway слушает 8090 на хосте, фабрика в контейнере с `NEURAL_SERVICE_URL=http://host.docker.internal:8090`. | Штатный сценарий: фабрика в Docker, Codex CLI/агент на хосте. |
| **Без Codex — облако (OpenAI)** | Gateway с `NEURAL_BACKEND=openai` и `OPENAI_API_KEY`. Фабрика по-прежнему указывает на gateway или напрямую на `https://api.openai.com`. | Нужна облачная модель вместо Codex. |
| **Без Codex — свой сервис / Ollama** | Gateway с `NEURAL_BACKEND=proxy`, `PROXY_TARGET_URL` на ваш сервис или Ollama. Либо фабрика сразу указывает `NEURAL_SERVICE_URL` на этот сервис, если он уже отдаёт `/v1/chat/completions`. | Self-hosted модель, свой API. |
| **Свой сервис вместо нашего gateway** | Вы поднимаете свой HTTP-сервис с эндпоинтом `POST /v1/chat/completions` (тело/ответ как у OpenAI). Внутри можете вызывать Codex CLI, свой агент, другой API. Фабрике задаёте только `NEURAL_SERVICE_URL` на этот сервис. | Полный контроль над логикой вызова Codex/агентов. |
| **Без нейросервиса (stub)** | `NEURAL_SERVICE_URL` не задан. Фабрика использует StubAgentPlanner и StubAgentCodegen, всё детерминировано. | Тесты, окружения без доступа к Codex/LLM. |

Итого: **Codex CLI — утилита управления агентами Codex; наш gateway — один из способов «обернуть» вызов Codex CLI в HTTP для фабрики. Варианты — от «всё на хосте с Codex» до «облако/свой API» или «вообще без LLM».**

---

## 7. Полезные ссылки

- [control-vs-intelligence-plane.md](control-vs-intelligence-plane.md) — граница между Control Plane и «интеллектом».
- [neural-service-api.md](neural-service-api.md) — контракт нейросервиса и обёртка Codex.
- [factory-2-planner-integration.md](factory-2-planner-integration.md) — как подключён LLM к планировщику.
- [scripts/neural_gateway/README.md](../scripts/neural_gateway/README.md) — запуск gateway и режимы (codex / openai / proxy).
