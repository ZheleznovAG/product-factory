# RAGAs Eval Scenario (faithfulness + relevance)

Документ фиксирует offline-сценарий оценки в стиле RAGAs для Product Factory и его связь с CI gate.

## Цель

- Проверять, что ответы Planner/Codegen:
- `faithfulness`: опираются на переданный контекст (без неподтвержденных утверждений).
- `relevance`: остаются в пределах цели задачи (`goal`) и не уводят в посторонний scope.

## Датасеты

- `eval/datasets/ragas_minimal.json` — основной multi-scenario датасет для regression gate.
- `eval/datasets/ragas_single_example.json` — быстрый локальный smoke-check для одной записи.

В `ragas_minimal.json` покрыты сценарии:

1. Faithfulness плана к run/policy-контексту.
2. Relevance codegen-ответа к requested scope.
3. Соблюдение policy scope (proposal-only, Tool Executor, без direct deploy).
4. Relevance к high-risk требованиям (human approval + audit trail).

## Локальный запуск

```bash
python3 eval/runners/run_offline_evals.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json
```

Через quality gate:

```bash
python3 ci/quality_gates.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json \
  --min-pass-rate 1.0 \
  --min-average-score 1.0
```

Single-example:

```bash
python3 eval/runners/run_ragas_single_example.py \
  --input eval/datasets/ragas_single_example.json
```

## CI gate

RAGAs проверка выполняется в `.github/workflows/ci.yml` job `eval-regression-gate`:

```bash
python3 ci/quality_gates.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json \
  --min-pass-rate 1.0 \
  --min-average-score 1.0
```

Если любой сценарий не проходит или итоговые пороги не достигнуты, job падает (`exit code 1`).
