# Planning Pipeline: продумывание и детализация плана технологий и задач

**Назначение:** на основе архитектуры, roadmap и существующих документов сгенерировать **полный и детальный** документ «Технологии и план реализации» ([technology-and-implementation-plan.md](technology-and-implementation-plan.md)), чтобы осталось только реализовывать.

**Источники для пайплайна:** [architecture-and-path.md](architecture-and-path.md), [solution_design.md](solution_design.md), [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md).

---

## Что делает пайплайн

| Шаг | Название | Результат |
|-----|----------|-----------|
| 1 | Технологический стек и обоснование | Выбор **взрослого open-source** стека по слоям; для каждого компонента — основная технология и 1–2 альтернативы (переключение без жёсткой зависимости). Файл: `docs/technology-and-implementation-plan-part1.md`. |
| 2 | Архитектура и компоненты | Детализация слоёв, потоков данных, интерфейсов, контрактов; диаграмма Mermaid. Файл: `docs/technology-and-implementation-plan-part2.md`. |
| 3 | Детальный план задач | Задачи с ID, зависимостями, критериями приёмки, оценкой сложности (на базе pipeline-tasks и roadmap). Файл: `docs/technology-and-implementation-plan-part3.md`. |
| 4 | Интеграция и эксплуатация | Сборка, деплой, CI/CD, runbook, мониторинг, безопасность. Файл: `docs/technology-and-implementation-plan-part4.md`. |
| 5 | Сборка итогового документа | Объединение частей в [technology-and-implementation-plan.md](technology-and-implementation-plan.md) с оглавлением, введением и разделом «Пока не определено» (технологии без зрелых open-source решений). Удаление временных part1–part4. |

---

## Запуск

**Важно:** это **planning** (5 шагов → technology-and-implementation-plan.md), а не completion (16 шагов → оставшиеся задачи). Если запускаете обёртку с возобновлением при лимите — обязательно указывайте `--pipeline planning`.

```bash
# Из корня репозитория

# Планирование (стек + архитектура + задачи) — напрямую
python3 scripts/run_planning_pipeline.py --dry-run
python3 scripts/run_planning_pipeline.py --allow-docker

# Планирование с автовозобновлением при лимите Codex (обёртка)
python3 scripts/run_pipeline_with_resume.py --pipeline planning --allow-docker

# Один шаг (например 1 — стек)
python3 scripts/run_planning_pipeline.py --step 1

# Продолжить после лимита Codex
python3 scripts/run_planning_pipeline.py --resume
# или
python3 scripts/run_pipeline_with_resume.py --pipeline planning --resume
```

Без `--pipeline planning` обёртка `run_pipeline_with_resume.py` по умолчанию запускает **completion**-пайплайн (16 шагов).

Состояние: `scripts/.planning_pipeline_state.json`.

---

## Принципы выбора технологий (заданы в промптах)

- **Только зрелый open-source** — нужное и пригодное для продакшена.
- **Без жёсткой привязки** — для каждого компонента 1–2 альтернативы, чтобы можно было переключаться.
- **По слоям**: Perception, Intent & Cognitive Modeling, Strategy Synthesis, Control Plane, Capability & Tool Graph, Outcome Intelligence, Self-Evolution Loop; Memory, Observability, Experimentation, Governance.
- **Пока не определено** — экспериментальные модальности без зрелых open-source решений и устойчивых стандартов consent/privacy не включаются в план; выносятся в раздел «Пока не определено» в конце документа.

---

## Связь с другими документами

| Документ | Роль |
|----------|------|
| [architecture-and-path.md](architecture-and-path.md) | Принципы, этапы, источник структуры и инвариантов. |
| [roadmap-workstreams-and-variants.md](roadmap-workstreams-and-variants.md) | Workstreams и фазы; что входит в ближайший горизонт. |
| [solution_design.md](solution_design.md) | Архитектура, слои, компоненты. |
| [technology-and-implementation-plan.md](technology-and-implementation-plan.md) | Итоговый документ после прогона пайплайна. |
