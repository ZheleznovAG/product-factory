# Offline Evals: trace grading и regression gate

Минимальный eval suite для Scale-фазы: набор сценариев + trace grading + блокирующий CI gate. Полная спецификация шага 4: [docs/scale-step4-trace-eval-gate.md](../docs/scale-step4-trace-eval-gate.md).

## Формат trace одного factory run

Файл JSON (`productfactory.io/trace/v1`) содержит:

- `runId`: уникальный id запуска.
- `steps`: этапы run (`stepId`, `name`, `status`, timestamps).
- `tool_calls`: вызовы tools (`name`, `idempotencyKey`, `status`, `attempt`, `policyDecisionId`).
- `policy_decisions`: решения policy (`allow`, `require_human_approval`, `reasons`, `input_summary`).
- `outcome`: итог (`status`, `reason`, `score`).

Формальная схема: `eval/trace.schema.json`.

Минимальная структура:

```json
{
  "traceVersion": "productfactory.io/trace/v1",
  "runId": "run-2026-02-20-0001",
  "steps": [],
  "tool_calls": [],
  "policy_decisions": [],
  "outcome": {"status": "passed", "reason": "", "score": 1.0}
}
```

## Dataset сценариев

`eval/datasets/agent_trace_minimal.json` включает 3 baseline-сценария:

1. `agent_non_privileged_tool_allowed` — обычный tool разрешен, run `passed`.
2. `agent_privileged_tool_requires_approval` — privileged tool требует approval, run `rejected`.
3. `agent_privileged_tool_rejected_without_unrelated_tools` — при approval-required run остается `rejected` и не вызывает нерелевантные tools.

Каждый сценарий содержит `expected` проверки (`outcome_status`, `policy_allow`, `require_human_approval`, `must_include_tools`, `forbidden_tools`, `min_score`).

## Локальный запуск

```bash
python3 eval/runners/run_offline_evals.py \
  --mode agent \
  --dataset eval/datasets/agent_trace_minimal.json
```

Качество через обёртку gate:

```bash
python3 ci/quality_gates.py \
  --mode agent \
  --dataset eval/datasets/agent_trace_minimal.json
```

Если любой сценарий провален или пороги не достигнуты, runner/gate завершаются с `exit code 1`.

## RAGAs-style eval (offline and gate)

Для оценки контентного качества добавлены датасеты:

- `eval/datasets/ragas_minimal.json` (4 сценария для gate)
- `eval/datasets/ragas_single_example.json`

Подробное описание сценариев: `eval-ragas.md`.

Gate-режим (faithfulness + relevance):

```bash
python3 eval/runners/run_offline_evals.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json
```

Через quality gate:

```bash
python3 ci/quality_gates.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json
```

Single-example runner для быстрой ручной проверки:

- `eval/runners/run_ragas_single_example.py`

Метрики:

- `faithfulness`: доля утверждений ответа, подтверждаемых контекстом.
- `relevance`: overlap токенов цели (`goal`) и ответа (`answer`).

Запуск:

```bash
python3 eval/runners/run_ragas_single_example.py \
  --input eval/datasets/ragas_single_example.json
```

## Planner reference regression (5 YAML -> planner outputs)

Для регрессии стратегий Planner добавлен каталог эталонных продуктов:

- `eval/reference-products/`

Каждый кейс содержит:

- 5 входных YAML (`inputs/*.yaml`)
- `request.json` (goal/constraints/target_stack + маппинг контрактов)
- ожидаемые planner outputs в `expected/*.json`

Запуск сравнения текущего вывода с эталонным:

```bash
python3 eval/runners/run_planner_reference_regression.py \
  --reference-root eval/reference-products \
  --factory-url http://localhost:9080 \
  --audit-log audit.log
```

Скрипт делает `POST /factory/run` для каждого reference-case, читает из `audit.log` события `pipeline_plan`, `adr_draft`, `test_plan`, `planner_explanation` и сравнивает их с эталоном. При расхождении возвращает `exit code 1`.
