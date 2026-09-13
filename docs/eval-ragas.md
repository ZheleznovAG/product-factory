# Offline eval в стиле RAGAs для Product Factory

Этот документ описывает оффлайн eval-подход в стиле RAGAs для фабрики и его использование в regression gate.

## Что такое RAGAs (кратко)

RAGAs — это подход к оценке quality для retrieval/generation пайплайнов через метрики, которые можно считать оффлайн по заранее подготовленным примерам.

Для нашего минимального профиля важны две метрики:

- `faithfulness` — насколько ответ/план опирается на данные контекста и не привносит неподтверждённые утверждения.
- `relevance` — насколько ответ релевантен целевой задаче (`goal`) и покрывает нужные требования.

## Как адаптировать к Product Factory

В фабрике это можно применять на двух уровнях.

1. `faithfulness` плана к goal и контрактам:
- Вход: `goal` (из product/intent), опорный контекст (ограничения, quality/risk profile), план Planner.
- Проверка: шаги плана должны быть объяснимо связаны с goal и не противоречить constraints/policy.

2. `relevance` ответа codegen к задаче:
- Вход: цель шага и ожидаемый артефакт.
- Проверка: сгенерированный ответ должен закрывать именно запрошенный scope (без ухода в нерелевантные части).

## Сценарии и датасеты

Базовые сценарии:

- `eval/datasets/ragas_minimal.json` — multi-scenario датасет для gate (faithfulness/relevance).
- `eval/datasets/ragas_single_example.json` — один пример для быстрой локальной калибровки.

Раннеры:

- `eval/runners/run_ragas_single_example.py`
- `eval/runners/run_offline_evals.py --mode ragas`

Метрики:

- `faithfulness`: доля утверждений ответа, которые подтверждаются контекстом (statement-level, через строковое вхождение).
- `relevance`: overlap ключевых токенов между `goal` и `answer`.

Это не «полная RAGAs-реализация», а lightweight baseline для локальной калибровки.

## Запуск

```bash
python3 eval/runners/run_offline_evals.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json
```

Запуск через quality gate:

```bash
python3 ci/quality_gates.py \
  --mode ragas \
  --dataset eval/datasets/ragas_minimal.json \
  --min-pass-rate 1.0 \
  --min-average-score 1.0
```

Быстрый single-example режим:

```bash
python3 eval/runners/run_ragas_single_example.py \
  --input eval/datasets/ragas_single_example.json \
  --min-faithfulness 0.7 \
  --min-relevance 0.6
```

Если метрики ниже порогов, раннер/gate завершается с `exit code 1`.

## Связь с текущим trace eval gate

- Trace gate (`--mode agent`) проверяет поведение workflow/policy на уровне run trace.
- RAGAs gate (`--mode ragas`) проверяет контентный quality (faithfulness/relevance) на уровне сценариев.

Оба подхода сочетаются: trace gate для регрессии поведения, RAGAs gate для регрессии смыслового качества.

См. также: `eval/README.md`, `docs/scale-step4-trace-eval-gate.md`.
