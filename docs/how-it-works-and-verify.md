# Как устроена фабрика и как убедиться, что она работает

Кратко — для быстрого понимания и проверки «на глаз». Обзор режимов работы, сценариев использования и того, что вы получаете на выходе: [usage-overview.md](usage-overview.md).

---

## Как это устроено (упрощённо)

1. **Запрос:** вы шлёте `POST /factory/run` с `goal` и `constraints`.
2. **Планировщик:** фабрика строит план (pipeline_plan, adr_draft, test_plan). Если задан `NEURAL_SERVICE_URL` — вызывает LLM/Codex; иначе — встроенный StubAgentPlanner.
3. **Политика:** OPA (если настроен) решает: разрешить run, потребовать approval или запретить.
4. **Кодоген:** генерируются предложения по коду (patch sets). При `NEURAL_SERVICE_URL` — LlmAgentCodegen (LLM/Codex); иначе — StubAgentCodegen.
5. **Инструменты:** по плану вызываются tools (например `create_repo_from_archetype`). Все side-effects только через Tool Executor, в sandbox.
6. **Состояние:** шаги переходят по state machine: NEW → PLANNED → … → STAGED → DONE (или FAILED). Перед `DONE` выполняется `slo_gate_evaluated` (пороги из `quality_profile.sloGate`), при нарушении run переводится в `FAILED` и релиз блокируется.
7. **Аудит:** каждый шаг и вызов tool пишется в audit log с `runId`, `eventType`, `payload`.

Подробная архитектура: [solution_design.md](solution_design.md). Запуск и операции: [runbook.md](runbook.md).

---

## Как увидеть, что система действительно работает

### 1. Фабрика жива (health)

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/health/ready
```

Ожидается: `200` и JSON вроде `{"status":"ok"}` / `{"status":"ready"}`.

Если используете нейросервис (Codex/gateway):

```bash
curl -s http://localhost:8080/health/neural
```

Ожидается: `{"neural_service":"ok",...}` — фабрика достучалась до gateway.

---

### 2. Один полный прогон «под ключ»

Скрипт сам поднимает gateway и фабрику, освобождает порты, делает один run и выводит выдержки из audit:

```bash
./scripts/run_factory_with_neural.sh
```

**На что смотреть в выводе:**

| Что в выводе | Что это значит |
|--------------|----------------|
| `Gateway готов.` | Neural Gateway на 8090 отвечает. |
| `Нейросервис (из фабрики): {"neural_service":"ok",...}` | Фабрика видит gateway. |
| `"status":"accepted"` в ответе POST | Запрос принят в работу. |
| `agent_planner_call` с `"success":true,"fallback_used":false` | Планировщик получил ответ от LLM/Codex. |
| `agent_codegen_call` с `"success":true,"fallback_used":false` | Кодоген получил ответ от LLM/Codex. |
| `agent_planner_call` / `agent_codegen_call` с `"fallback_used":true` | LLM недоступен/ошибка — использован stub (run всё равно завершится). |
| События по `runId`: `state_changed` → `tool_call_executed` → `slo_gate_evaluated` → `state_changed` (DONE) | Workflow прошёл до конца, tools вызваны, SLO gate пройден. |
| `artifact_registry_updated` с `"state":"STAGED"` | Артефакт зафиксирован в реестре. |
| `artifact_manifest_written` с `manifest.checksum` и `manifest.provenance` | Записан versioned manifest; для одинакового набора YAML checksum должен совпадать между повторными run. |

Если всё это есть — фабрика отработала от запроса до артефакта.

---

### 3. Ручной запрос и просмотр audit по runId

Запустите фабрику (например через скрипт выше или вручную Docker), затем:

```bash
# Запрос
RESPONSE=$(curl -s -X POST http://localhost:8080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal": "Create catalog API from archetype", "constraints": ["use catalog-service"]}')
echo "$RESPONSE"

# Вытащить runId (подставьте свой из ответа)
RUN_ID=$(echo "$RESPONSE" | sed -n 's/.*"runId":"\([^"]*\)".*/\1/p')
echo "runId: $RUN_ID"
```

Просмотр audit по этому run (если audit на хосте, например в volume `/app/data` или в контейнере):

```bash
# Из контейнера
docker exec product-factory-run cat /app/audit.log | grep "$RUN_ID"

# Или если audit.log смонтирован в ./data
grep "$RUN_ID" data/audit.log
```

Вы должны увидеть цепочку: `request_received` → `policy_check` → `state_changed` (PLANNED) → `pipeline_plan`, `adr_draft`, `test_plan` → … → `tool_call_executed` (create_repo_from_archetype и др.) → `state_changed` (DONE).

---

### 4. Минимальная проверка без нейросервиса (только stub)

Фабрика без `NEURAL_SERVICE_URL` тоже работает: планировщик — StubAgentPlanner.

```bash
docker run --rm -p 8080:8080 -v "$(pwd)/archetypes:/app/archetypes" product-factory
# В другом терминале:
curl -s http://localhost:8080/health
curl -s -X POST http://localhost:8080/factory/run \
  -H "Content-Type: application/json" \
  -d '{"goal": "API for X", "constraints": ["use catalog-service"]}'
```

Ожидается: ответ с `runId`, `status: accepted` (или подобный), в audit — те же типы событий, но `agent_planner_call` и `agent_codegen_call` с `fallback_used: true`.

---

### 5. Проверка release-policy gate (SBOM + подпись + manual high-risk approval)

Проверка policy-инвариантов release workflow:

```bash
python3 ci/release_policy_gate.py --workflow .github/workflows/release.yml
```

Ожидается: `Release policy gate passed`.

Этот же чек выполняется в CI job `release-policy-gate` в `.github/workflows/ci.yml`.

---

## Итог: «доказательство работы»

- **Фабрика жива:** `GET /health` и при необходимости `GET /health/neural` возвращают ожидаемый JSON.
- **Один run от начала до конца:** скрипт `run_factory_with_neural.sh` завершается без ошибок, в выводе есть принятый запрос, события по `runId` и переход в DONE/STAGED.
- **Аудит:** по `runId` в audit.log видна полная цепочка событий (request → plan → policy → tools → state_changed → DONE).

Если нужны детали по шагам, формату audit или отказу — см. [runbook.md](runbook.md) и [solution_design.md](solution_design.md).
