# Scale, шаг 4: Trace grading и eval runs (regression gate)

Спецификация того, что уже есть и как этим пользоваться.

---

## 1. Формат trace одного factory run

**Схема:** `eval/trace.schema.json`  
**Версия:** `traceVersion: "productfactory.io/trace/v1"`

Обязательные поля:

| Поле | Описание |
|------|----------|
| `runId` | Уникальный id запуска |
| `steps` | Массив этапов: `stepId`, `name`, `status` (и опционально timestamps) |
| `tool_calls` | Вызовы tools: `name`, `status`, `idempotencyKey`, `attempt`, `policyDecisionId` |
| `policy_decisions` | Решения policy: `allow`, `require_human_approval`, `reasons`, `input_summary` |
| `outcome` | Итог: `status` (passed/rejected), `reason`, опционально `score` (0–1) |

Пример минимального trace — в `eval/traces/trace_run_pass.json` и `trace_run_requires_approval.json`.

---

## 2. Dataset сценариев

**Формат:** JSON с полем `scenarios` (массив) или JSONL (одна строка = один сценарий).

Каждый сценарий:

- `id` — идентификатор
- `description` — описание
- `trace` — путь к файлу trace (относительно файла датасета или абсолютный)
- `expected` — ожидаемые проверки:
  - `outcome_status` — "passed" | "rejected"
  - `policy_allow` — true/false
  - `require_human_approval` — true/false
  - `max_tool_calls` — не больше N вызовов
  - `must_include_tools` — список имён tools, которые должны быть вызваны
  - `forbidden_tools` — список запрещённых
  - `min_score` — минимальный score в outcome

**Текущий датасет:** `eval/datasets/agent_trace_minimal.json` — два сценария (allowed run, rejected with approval).

---

## 3. Прогон eval (локально)

```bash
# Прямой вызов runner
python3 eval/runners/run_offline_evals.py \
  --mode agent \
  --dataset eval/datasets/agent_trace_minimal.json \
  --min-pass-rate 1.0 \
  --min-average-score 0.95

# Через quality gate (обёртка + exit code при регрессии)
python3 ci/quality_gates.py \
  --mode agent \
  --dataset eval/datasets/agent_trace_minimal.json
```

При провале любого сценария или при pass_rate < min_pass_rate / average_score < min_average_score — **exit code 1** (регрессия).

---

## 4. CI: когда запускается gate

В `.github/workflows/ci.yml` job **eval-regression-gate**:

- Запускается при изменении:
  - `prompts/**`
  - `policies/opa/**`
  - `contracts/tools.schema.json`, `contracts/tools.registry.example.json`
  - `docs/tools-list-mvp.md`
  - `eval/**`
- Выполняет: `python3 ci/quality_gates.py --mode agent --dataset eval/datasets/agent_trace_minimal.json --min-pass-rate 1.0 --min-average-score 0.95`
- При exit code 1 — падает job, merge блокируется (если настроены branch protection).

Добавить триггер по другим путям можно в `paths-filter` (filters.eval_gate).

---

## 5. Что делать дальше (расширение)

- Добавить сценарии в `eval/datasets/` (новый JSON или дописать в agent_trace_minimal).
- Генерировать trace из реального run: при наличии экспорта из WorkflowRunner в формат trace — писать файл в `eval/traces/` и подставлять в датасет.
- Ужесточить пороги: `--min-pass-rate 1.0`, `--min-average-score 0.95` (уже стоят в CI).

См. также: `eval/README.md`, `docs/system-audit-corrective-plan.md` (критерий успеха Scale).
