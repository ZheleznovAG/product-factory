# SLO & Cost Draft (MVP)

- **Status:** Draft
- **Date:** 2026-02-26
- **Scope:** run-level SLO и promotion controls

Связанные риски: [docs/risk-register.md](risk-register.md) (латентность, надёжность, cost-overrun).

## 1. SLO для `run`

- `run_latency_p50_seconds <= 900`
- `run_latency_p99_seconds <= 7200`
- `run_success_rate_percent >= 95.0` (rolling 30 days)

Формула success rate: `successful_runs / started_runs * 100`, без operator-cancelled.

**Привязка к метрикам фабрики:** эндпоинт `/metrics` (Prometheus text format), см. [runbook.md](runbook.md) и [observability-and-logs.md](observability-and-logs.md).

| SLO | Метрика / формула |
|-----|-------------------|
| run_latency | `histogram_quantile(0.50, sum(rate(factory_run_duration_seconds_bucket[30d])) by (le))` и `histogram_quantile(0.99, sum(rate(factory_run_duration_seconds_bucket[14d])) by (le))` |
| success_rate | `factory_runs_total{status="accepted"} / (factory_runs_total{status="accepted"} + factory_runs_total{status="rejected"})` за окно 30d |

**Примечание:** p50/p99 теперь доступны через гистограмму `factory_run_duration_seconds_*` на `/metrics` (бакеты: 1, 5, 30, 60, 300, 900, 7200 секунд + `+Inf`).

## 2. Cost-метрики для `run`

- `llm_input_tokens_total` → **factory_llm_input_tokens_total**
- `llm_output_tokens_total` → **factory_llm_output_tokens_total**
- `tool_calls_total` → **factory_tool_calls_total**
- `run_wall_clock_seconds` → из **factory_run_duration_ms_sum** (в секундах: sum/1000) или по артефакт-реестру/audit

Минимальные агрегаты: daily/monthly totals, p50/p99 по `run_wall_clock_seconds`. Сбор: Prometheus scrape `/metrics` (см. [deploy/prometheus.yml](../deploy/prometheus.yml) при профиле `observability`).

## 3. Cost Budgets Policy (run + period caps)

### 3.1 Run budget (per `POST /factory/run`)

API уже поддерживает поля:

- `budget.token_budget`
- `budget.tool_calls_budget`
- `budget.wall_clock_seconds`

Рекомендуемый порядок источников (приоритет сверху вниз):

1. Явно заданный `budget` в запросе run.
2. Env defaults фабрики.
3. Fallback defaults из policy-конфига (`policies/budgets.yaml` / runtime policy).

Рекомендуемые env-переменные (конвенция):

- `PF_RUN_DEFAULT_TOKEN_BUDGET` (например `100000`)
- `PF_RUN_DEFAULT_TOOL_CALLS_BUDGET` (например `50`)
- `PF_RUN_DEFAULT_WALL_CLOCK_SECONDS` (например `3600`)

Важно: если `budget` в запросе отсутствует, применяется default профиль; если задан частично, недостающие поля берутся из default.

Пример policy-конфига: [cost-budgets.example.yaml](cost-budgets.example.yaml).

### 3.2 Weekly / Monthly caps

Caps считаются по агрегатам Prometheus за окно периода:

- `weekly_llm_tokens_total` = `increase(factory_llm_input_tokens_total[7d]) + increase(factory_llm_output_tokens_total[7d])`
- `monthly_llm_tokens_total` = `increase(factory_llm_input_tokens_total[30d]) + increase(factory_llm_output_tokens_total[30d])`
- `weekly_tool_calls_total` = `increase(factory_tool_calls_total[7d])`
- `monthly_tool_calls_total` = `increase(factory_tool_calls_total[30d])`
- `weekly_wall_clock_seconds_total` = `increase(factory_run_duration_ms_sum[7d]) / 1000`
- `monthly_wall_clock_seconds_total` = `increase(factory_run_duration_ms_sum[30d]) / 1000`

Пороговая политика:

- `soft cap = 80%` от лимита периода -> alert + обязательный review новых non-critical run.
- `hard cap = 100%` -> deny новых non-critical run, только critical/manual-override по approval policy.

Рекомендуется держать caps отдельно для `tokens`, `tool_calls`, `wall_clock_seconds`.

### 3.2.1 Pre-run enforcement (реализовано)

Перед `POST /factory/run` и `POST /factory/runs/{runId}/retry` фабрика проверяет периодические счётчики (`PeriodUsageStore`) против `cost-budgets`:

- `429 Too Many Requests` + `COST_BUDGET_SOFT_CAP_EXCEEDED` при достижении soft cap.
- `403 Forbidden` + `COST_BUDGET_HARD_CAP_EXCEEDED` при достижении/превышении hard cap.

Источник конфига:

- `COST_BUDGETS_PATH` (по умолчанию `policies/cost-budgets.yaml`).
- Формат: `periodCaps.weekly|monthly` с лимитами `llm_tokens_total`, `tool_calls_total`, `runs_total` и ratios `soft_threshold_ratio`, `hard_threshold_ratio`.

### 3.3 Пример PromQL для cap-check

- Soft monthly tokens: `sum(increase(factory_llm_input_tokens_total[30d]) + increase(factory_llm_output_tokens_total[30d])) > (MONTHLY_TOKEN_CAP * 0.8)`
- Hard monthly tokens: `sum(increase(factory_llm_input_tokens_total[30d]) + increase(factory_llm_output_tokens_total[30d])) >= MONTHLY_TOKEN_CAP`

Аналогично для `factory_tool_calls_total` и `factory_run_duration_ms_sum`.

## 4. Использование для promotion

### 4.1 Ручной порог (MVP default)

Promotion проходит review, если:

- SLO выполнены (`p99`, `success_rate`)
- нет необъяснимого роста cost-метрик (tokens/tool calls)
- нет открытого high-risk инцидента из [docs/risk-register.md](risk-register.md)

### 4.2 Auto-gate (целевое состояние)

Promotion блокируется автоматически, если:

- `run_success_rate_percent < 95.0` (30 days)
- `run_latency_p99_seconds > 7200` (14 days)
- превышен месячный budget cap

### 4.3 Бюджет

- soft cap: `80%` месячного лимита -> alert + review
- hard cap: `100%` -> блок non-critical runs / degraded mode

## 5. Границы документа

Только policy draft. Реализация telemetry/dashboard/alerts/gate integration выносится в отдельные задачи.

## 6. Следующие шаги (реализация)

- [x] Гистограмма `factory_run_duration_seconds_*` для p50/p99 в [FactoryMetrics.kt](../src/main/kotlin/productfactory/observability/FactoryMetrics.kt); экспортируется через `/metrics`.
- [x] Дашборд Grafana: SLO (success_rate, latency), cost (tokens, tool_calls) по [observability-and-logs.md](observability-and-logs.md) — [deploy/grafana/provisioning/dashboards/factory-slo-cost.json](../deploy/grafana/provisioning/dashboards/factory-slo-cost.json).
- [x] Alerting: run_success_rate < 95%, p99 > 7200s, приближение к budget cap — [deploy/prometheus-rules.yml](../deploy/prometheus-rules.yml), подключение через [deploy/prometheus.yml](../deploy/prometheus.yml).
- [x] Pre-run enforcement period caps: `429/403` по `cost-budgets` (см. `COST_BUDGETS_PATH`, `policies/cost-budgets.yaml`).
- [ ] CI/опциональный gate: проверка SLO по артефактам прогона (eval или post-run check).
