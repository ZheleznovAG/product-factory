# Development Pipeline: автоматизированная реализация по плану

**Назначение:** выполнить задачи из раздела «3. Детальный план задач» документа [technology-and-implementation-plan.md](technology-and-implementation-plan.md) через Codex: каждая строка таблицы задач превращается в один шаг пайплайна (реализация кода, конфигов, тестов, документов).

**Источник задач:** раздел 3 плана (Фаза A–D: H1-Auth-*, H1-Budget-*, H1-Obs-*, H1-Env-*, H1-RAG-*, H1-Gov-*, H1-Del-*, H1-Docs-*, H2-*).

---

## Запуск

```bash
# Из корня репозитория

# Пересобрать шаги из плана (после изменения technology-and-implementation-plan.md)
python3 scripts/run_development_pipeline.py --reload-plan

# Просмотр шагов без выполнения
python3 scripts/run_development_pipeline.py --dry-run

# Полный пайплайн (рекомендуется --allow-docker для сборки/тестов)
python3 scripts/run_development_pipeline.py --allow-docker

# С автовозобновлением при лимите Codex
python3 scripts/run_pipeline_with_resume.py --pipeline development --allow-docker

# Один шаг или диапазон
python3 scripts/run_development_pipeline.py --allow-docker --step 1
python3 scripts/run_development_pipeline.py --allow-docker --from-step 1 --to-step 5

# Продолжить после остановки
python3 scripts/run_development_pipeline.py --resume --allow-docker
```

Состояние: `scripts/.development_pipeline_state.json`.  
Кэш шагов (при необходимости ручной правки): `scripts/.development_pipeline_steps.json`.

---

## Связь с другими пайплайнами

| Пайплайн | Что делает |
|----------|------------|
| **Planning** | Генерирует план (technology-and-implementation-plan.md) из видения и roadmap. |
| **Development** | Реализует задачи из раздела 3 плана (текущий документ). |
| **Completion** | Закрывает оставшиеся 16 пунктов из grand/completion бэклога (наблюдаемость, RAG, GitOps и т.д.). |

Сначала запускают **Planning**, затем **Development** (или Completion по необходимости).

---

## Формат шага

Каждый шаг пайплайна содержит:
- **ID** — порядковый номер (1, 2, …) для `--step`, `--from-step`, `--to-step`, `--resume`.
- **Название** — исходный ID задачи из плана (например H1-Auth-1.1) и краткое описание.
- **Промпт** — контекст репо, формулировка задачи, зависимости, критерии приёмки и указание проверить сборку/тесты по AGENTS.md.

Зависимости между задачами в плане не выполняются автоматически (порядок шагов соответствует порядку в документе: Фаза A → B → C → D). При необходимости пропустить или выполнить в другом порядке можно использовать `--from-step` / `--to-step` или отредактировать `.development_pipeline_steps.json` и запустить без `--reload-plan`.
