# ADR-0002: Граница ответственности слоёв и запрет side-effects из agent layer

**Дата:** 2026-02-19  
**Статус:** accepted

## Контекст

Строится Product Factory в соло-режиме. Цель — воспроизводимое создание deployable артефактов. Риск — "prompt-chaos": агентные решения начинают напрямую менять состояние систем.

Связанные документы: [docs/prohibited-agent-actions.md](../prohibited-agent-actions.md), [docs/solution_design.md](../solution_design.md).

## Решение

1. Любые side-effects исполняются только через Tool Executor (sandbox).
2. Агентный слой генерирует только proposals (diff/ADR/tests) в структурированном формате.
3. Policy PDP принимает решения allow/deny на основании структурного input и контекста запуска.

## Инварианты

- Любой tool call имеет JSON schema и strict-валидацию аргументов.
- Любой tool call имеет audit event в event log.
- Любой retry не должен порождать дубли side-effects (идемпотентность).

## Триггеры пересмотра

- Инцидент дублирования ресурсов/деплоев из-за ретраев.
- Рост cost per run > 2x за месяц.
- Появление новой категории данных (PII/secret) в контексте запуска.
