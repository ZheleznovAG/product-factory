# API Intent/Experience (Фазы A/B)

Статус: draft

Документ фиксирует B2B API-обёртку над Intent Layer, продолжающую контракт `intent.schema.json` (см. [intent-clarification-protocol.md](intent-clarification-protocol.md), [intent-protocol-90s.md](intent-protocol-90s.md)), на двух этапах:

- Фаза A: оценка намерения пользователя (`intent`) из текстового запроса
- Фаза B: генерация вариантов experience по рассчитанному `intent`

## 1. Общие принципы

- Base path: `/`
- Формат: `application/json`
- Версия контракта: `apiVersion: "productfactory.io/v1"`
- Multi-tenant: `tenantId` в body (или `X-Tenant-Id` / query `tenantId`, fallback: `default`)
- Трассировка: рекомендуется заголовок `X-Request-Id`
- Session: поле `session` (sessionId, createdAt, references) передаётся между `/intent/estimate` и `/experience/generate`
- Референсы: сценарий `6 -> выбрать 2`; выбранные `reference_ids` передаются в `intent` и пишутся в audit

## 2. POST /intent/estimate

Назначение: оценить и структурировать `intent` из пользовательского запроса и контекста.

### Request

```json
{
  "apiVersion": "productfactory.io/v1",
  "tenantId": "team-a",
  "requestId": "req-123",
  "session": {
    "sessionId": "sess-2026-02-26-001",
    "references": {
      "selected_ids": ["ref-1", "ref-4"]
    }
  },
  "input": {
    "query": "Сделай onboarding-опыт для нового пользователя SaaS",
    "language": "ru",
    "reference_ids": ["ref-1", "ref-4"]
  },
  "constraints": {
    "riskTier": "medium",
    "latencyBudgetMs": 5000,
    "maxClarifyingQuestions": 3
  }
}
```

### Response 200

```json
{
  "apiVersion": "productfactory.io/v1",
  "tenantId": "team-a",
  "requestId": "req-123",
  "session": {
    "sessionId": "sess-2026-02-26-001",
    "references": {
      "options": [
        {"id":"ref-1","title":"Reference card 1","summary":"..."},
        {"id":"ref-2","title":"Reference card 2","summary":"..."},
        {"id":"ref-3","title":"Reference card 3","summary":"..."},
        {"id":"ref-4","title":"Reference card 4","summary":"..."},
        {"id":"ref-5","title":"Reference card 5","summary":"..."},
        {"id":"ref-6","title":"Reference card 6","summary":"..."}
      ],
      "selected_ids": ["ref-1", "ref-4"]
    }
  },
  "intent": {
    "outcome": "Ускорить time-to-first-value для новых пользователей",
    "experience": "Короткий guided flow с прогрессом и обратной связью",
    "constraints": [
      "Не менять pricing",
      "Соблюдать tone-of-voice бренда"
    ],
    "confidence": 0.81,
    "reference_ids": ["ref-1", "ref-4"]
  },
  "clarifyingQuestions": [
    "Какой целевой сегмент пользователей приоритетен?"
  ],
  "evidence": {
    "usedReferences": 2,
    "model": "planner"
  }
}
```

### Коды ответов

- `200 OK` — intent успешно оценён
- `400 Bad Request` — невалидный JSON/обязательные поля отсутствуют
- `422 Unprocessable Entity` — запрос формально валиден, но intent не может быть выделен
- `429 Too Many Requests` — превышены лимиты
- `500 Internal Server Error` — внутренняя ошибка
- `503 Service Unavailable` — недоступен neural backend

## 3. POST /experience/generate

Назначение: сгенерировать 3–7 вариантов experience на основе `intent`.

### Request

```json
{
  "apiVersion": "productfactory.io/v1",
  "tenantId": "team-a",
  "requestId": "req-124",
  "session": {
    "sessionId": "sess-2026-02-26-001"
  },
  "intent": {
    "outcome": "Ускорить time-to-first-value для новых пользователей",
    "experience": "Короткий guided flow",
    "constraints": [
      "Не менять pricing",
      "Соблюдать tone-of-voice бренда"
    ],
    "reference_ids": ["ref-1", "ref-4"]
  },
  "generation": {
    "variants": 6,
    "includeRationale": true
  }
}
```

### Response 200

```json
{
  "apiVersion": "productfactory.io/v1",
  "tenantId": "team-a",
  "requestId": "req-124",
  "variants": [
    {
      "id": "exp-1",
      "title": "Guided checklist",
      "summary": "Пошаговый запуск с прогресс-баром",
      "rationale": "Минимизирует когнитивную нагрузку",
      "score": 0.79
    },
    {
      "id": "exp-2",
      "title": "Template-first",
      "summary": "Готовые пресеты с быстрым стартом",
      "rationale": "Сокращает время до первого результата",
      "score": 0.74
    },
    {
      "id": "exp-3",
      "title": "Interactive assistant",
      "summary": "Контекстные подсказки в момент действия",
      "rationale": "Снижает ошибки на раннем этапе",
      "score": 0.71
    }
  ],
  "audit": {
    "event": "experience_generated",
    "tenantId": "team-a",
    "runId": "run-xyz",
    "sessionId": "sess-2026-02-26-001",
    "reference_ids": ["ref-1", "ref-4"]
  }
}
```

### Коды ответов

- `200 OK` — варианты успешно сгенерированы
- `400 Bad Request` — невалидный JSON/обязательные поля отсутствуют
- `422 Unprocessable Entity` — intent недостаточен для генерации вариантов
- `429 Too Many Requests` — превышены лимиты
- `500 Internal Server Error` — внутренняя ошибка
- `503 Service Unavailable` — недоступен neural backend

## 4. Ошибки (единый формат)

```json
{
  "apiVersion": "productfactory.io/v1",
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Field input.query is required",
    "details": {
      "field": "input.query"
    }
  }
}
```

## 5. Опционально: Ktor-заглушка эндпоинтов

Если нужно быстро зарезервировать API до полной реализации, можно добавить route-заглушки (возвращают `501` или пустой JSON):

```kotlin
routing {
    post("/intent/estimate") {
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "not_implemented"))
        // альтернатива:
        // call.respond(HttpStatusCode.OK, emptyMap<String, Any>())
    }

    post("/experience/generate") {
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "not_implemented"))
        // альтернатива:
        // call.respond(HttpStatusCode.OK, emptyMap<String, Any>())
    }
}
```

Примечание: side-effects и privileged operations остаются в границах Tool Executor/policy, как и в остальной архитектуре фабрики.
