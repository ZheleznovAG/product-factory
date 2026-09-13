# SLO CI Gate

- **Status:** Active in CI (`.github/workflows/ci.yml`)
- **Date:** 2026-02-26
- **Scope:** блокировка merge по SLO-сигналам из артефактов run/eval

Связанные документы: [slo-cost-draft.md](slo-cost-draft.md), [scale-step4-trace-eval-gate.md](scale-step4-trace-eval-gate.md), [runbook.md](runbook.md).

## 1. Как работает gate в CI

В CI после `live-run` запускается отдельный job `slo-gate`, который:

1. скачивает артефакт `live-run-summary-${sha}` (`ci-artifacts/live_run_summary.json`);
2. вызывает:
   ```bash
   python3 ci/slo_gate.py \
     --input ci-artifacts/live_run_summary.json \
     --mode live \
     --thresholds-config ci/slo-thresholds.json
   ```
3. при нарушении порогов возвращает `exit 1` и job падает.

Дополнительно доступен runtime-совместимый gate через CLI фабрики (пороги берутся из `quality_profile`):

```bash
docker run --rm -v "$(pwd):/w" -w /w product-factory \
  /app/bin/product-factory slo-gate \
  --quality-profile contracts-example/quality_profile.yaml \
  --run-id <runId> \
  --audit-log audit.log \
  --generation-time-seconds 480
```

Этот вариант можно использовать как post-step в CI/CD после выполнения run.

Итог:

- `exit 0` -> merge можно продолжать;
- `exit 1` -> merge блокируется (при настроенном branch protection).

Источники артефактов:

- live-run summary (из job `live-run`);
- offline eval report (локально/в отдельном pipeline при необходимости).

## 2. Базовые пороги

Для runtime SLO gate пороги задаются в `quality_profile.yaml` секцией `sloGate`:

```yaml
sloGate:
  enabled: true
  maxGenerationTimeSeconds: 900
  maxPolicyDenySharePercent: 5
  minSuccessRatePercent: 95
  lookbackRuns: 20
```

### 2.1 Live-run SLO (надёжность/латентность)

- `run_success_rate_percent >= 95.0`
- `run_latency_p50_seconds <= 900`
- `run_latency_p99_seconds <= 7200`

### 2.2 Eval SLO (качество сценариев)

- `pass_rate >= 1.0`
- `average_score >= 0.95`

## 3. Что блокирует merge

Merge блокируется, если выполняется хотя бы одно условие:

- метрика отсутствует для выбранного режима gate
- `run_success_rate_percent < 95.0`
- `run_latency_p50_seconds > 900`
- `run_latency_p99_seconds > 7200`
- `pass_rate < 1.0`
- `average_score < 0.95`

Допустимый переходный режим (только на время bootstrap): запускать gate в `auto`-режиме и проверять только те метрики, которые реально есть в артефакте. В steady-state рекомендуется явный режим `live` или `eval`.

## 4. Формат входа (JSON)

Gate-скрипт поддерживает несколько форм:

- live-run summary:
  - top-level: `run_success_rate_percent`, `run_latency_p50_seconds`, `run_latency_p99_seconds`
  - или те же поля внутри `metrics`
- eval report:
  - top-level: `pass_rate`, `average_score`
  - или `metrics.pass_rate`, `metrics.average_score`

Пример live-run:

```json
{
  "metrics": {
    "run_success_rate_percent": 97.2,
    "run_latency_p50_seconds": 420,
    "run_latency_p99_seconds": 1530
  }
}
```

Пример eval:

```json
{
  "metrics": {
    "pass_rate": 1.0,
    "average_score": 0.97
  }
}
```

## 5. Локальный запуск

```bash
# Live gate
python3 ci/slo_gate.py \
  --input artifacts/live_run_summary.json \
  --mode live

# Eval gate
python3 ci/slo_gate.py \
  --input artifacts/eval_report.json \
  --mode eval

# Auto (проверяет все найденные совместимые метрики)
python3 ci/slo_gate.py \
  --input artifacts/report.json \
  --mode auto

# С порогами из конфигурации репозитория
python3 ci/slo_gate.py \
  --input artifacts/live_run_summary.json \
  --mode live \
  --thresholds-config ci/slo-thresholds.json
```

Переопределение порогов для конкретного pipeline:

```bash
python3 ci/slo_gate.py \
  --input artifacts/live_run_summary.json \
  --mode live \
  --min-success-rate 96.0 \
  --max-p99-seconds 3600
```

## 6. Конфиг порогов

Пороги лежат в `ci/slo-thresholds.json`:

```json
{
  "live": {
    "min_success_rate": 95.0,
    "max_p50_seconds": 900.0,
    "max_p99_seconds": 7200.0
  },
  "eval": {
    "min_pass_rate": 1.0,
    "min_average_score": 0.95
  }
}
```

При необходимости pipeline может переопределить пороги через CLI-флаги `ci/slo_gate.py`, но базовый источник правды для CI — файл `ci/slo-thresholds.json`.
