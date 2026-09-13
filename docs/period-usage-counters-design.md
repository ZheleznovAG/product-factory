# Периодические счётчики usage (день/месяц)

## Цель

Добавить персистентные агрегаты usage для runtime cost-control:
- `runs`
- `tokens` (`llm_input_tokens + llm_output_tokens`)
- `tool_calls`

Агрегация ведётся по двум периодам:
- день (`YYYY-MM-DD`, UTC)
- месяц (`YYYY-MM`, UTC)

## Область реализации

- Новый store: `src/main/kotlin/productfactory/workflow/PeriodUsageStore.kt`
  - `PeriodUsageStore` (interface)
  - `InMemoryPeriodUsageStore`
  - `FilePeriodUsageStore`
  - `PeriodUsageSnapshot` (`daily`, `monthly`, `updated_at`)
- Интеграция в API:
  - `POST /factory/run`
  - `POST /factory/runs/{runId}/retry`
- Wiring в `Application.module` через `periodUsageStore` (по умолчанию `FilePeriodUsageStore`).

## Формат хранения

Путь по умолчанию: `data/period-usage-counters.json`  
Переменная окружения: `PERIOD_USAGE_STORE_PATH`

Пример:

```json
{
  "daily": {
    "2026-02-26": { "runs": 12, "tokens": 154320, "tool_calls": 87 }
  },
  "monthly": {
    "2026-02": { "runs": 231, "tokens": 2987340, "tool_calls": 1709 }
  },
  "updated_at": "2026-02-26T17:02:11.201Z"
}
```

## Алгоритм обновления

Для синхронного run:
1. Снять `FactoryMetrics.snapshot()` перед запуском workflow.
2. Выполнить run.
3. Записать `FactoryMetrics.recordRun(...)`.
4. Снять `snapshot()` после run.
5. Посчитать дельты:
   - `tokens = (after.input+after.output) - (before.input+before.output)`
   - `tool_calls = after.toolCalls - before.toolCalls`
   - `runs = 1`
6. Увеличить соответствующие записи `daily[utcDay]` и `monthly[utcMonth]`.

Для `Temporal`-режима (`POST /factory/run` возвращает `started`):
- фиксируется `runs += 1`, `tokens += 0`, `tool_calls += 0` в момент старта.

## Конкурентность и отказоустойчивость

- `FilePeriodUsageStore` использует `synchronized(lock)` для serial update внутри процесса.
- Запись файла атомарная: сначала `*.tmp`, затем `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
- При повреждённом JSON store безопасно возвращает пустой snapshot и продолжает работать.

## Ограничения текущей версии

- Подсчёт по дельте `FactoryMetrics` корректен для in-process run, но не даёт фактические токены/tool_calls в асинхронном Temporal-пути (там учитывается только факт старта run).
- Ротация/retention period-данных пока не реализована.
- Используется локальный файл (single-instance storage), без распределённой синхронизации.

## Дальше (следующий шаг P6)

- Добавить pre-run enforcement лимитов по `runs/tokens/tool_calls` с ответом `429/403` при превышении.
- Вынести лимиты в конфиг (`cost-budgets`), добавить API/метрики для наблюдения period usage.
