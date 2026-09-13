# Как запустить полный автоматизированный спринт (Factory 2.0)

Описание спринта, шаги, порядок выполнения и требования. Бэклог: [sprint-full-backlog.md](sprint-full-backlog.md). Скрипт: [scripts/run_factory2_full_sprint.py](../scripts/run_factory2_full_sprint.py).

---

## 1. Что делает спринт

Спринт закрывает приоритетные задачи (P0 и часть P1) из полного бэклога:

- Полировка: health endpoint фабрики, валидация контрактов в CI, FactoryRunTest, runbook с живыми прогонами.
- Factory 2.0: реализация LlmAgentPlanner, внедрение по NEURAL_SERVICE_URL, тесты, наблюдаемость.
- Документация: синхронизация phases-task-list и implementation-assessment; опционально SLO/cost черновик, digest в audit, threat model.

Каждый шаг выполняется через Codex CLI (`codex exec`): агент получает детальный промпт и выполняет изменения в репозитории. После каждого шага (опционально) делается git commit.

---

## 2. Требования

- **Codex CLI** установлен и доступен в PATH (`codex exec`). Установка: `brew install --cask codex` (или по инструкции Codex).
- **Репозиторий** в состоянии, пригодном для коммитов (ветка, при необходимости — отдельная для спринта).
- **Docker** (рекомендуется): для сборки образов и тестов используется `--allow-docker` (sandbox danger-full-access). Без Docker — только `workspace-write` (сборка через локальный Gradle при наличии JDK).
- **Опционально:** `NEURAL_SERVICE_URL` и при необходимости `NEURAL_SERVICE_API_KEY` для проверки LlmAgentPlanner с реальным LLM после спринта.

---

## 3. Запуск

Из корня репозитория:

```bash
# Просмотр шагов без выполнения
python3 scripts/run_factory2_full_sprint.py --dry-run

# Полный спринт (Docker разрешён, коммиты после каждого шага)
python3 scripts/run_factory2_full_sprint.py --allow-docker

# Без коммитов (только изменения в рабочей копии)
python3 scripts/run_factory2_full_sprint.py --allow-docker --no-commit

# Только один шаг (например шаг 5 — тесты LlmAgentPlanner)
python3 scripts/run_factory2_full_sprint.py --allow-docker --step 5

# С указанием модели Codex
python3 scripts/run_factory2_full_sprint.py --allow-docker --model gpt-5.3-codex
```

Рекомендуется первый раз запустить с `--dry-run`, затем с `--allow-docker` и без `--no-commit` только если нужны коммиты.

---

## 4. Список шагов спринта

| Шаг | ID бэклога | Название |
|-----|------------|----------|
| 1 | B01 | Health endpoint для образа фабрики |
| 2 | B02 | Валидация контрактов в CI |
| 3 | B03 | FactoryRunTest: стабилизировать или задокументировать |
| 4 | F01, F02 | LlmAgentPlanner: реализация + промпт и tool surface |
| 5 | F05 | Тесты LlmAgentPlanner |
| 6 | F03, F04 | Внедрение Planner в контур + наблюдаемость в audit |
| 7 | B04 | Runbook: живые прогоны «запрос → артефакт» |
| 8 | S01 | Черновик SLO и метрик cost (документ) |
| 9 | A01 | Обязательные digest в audit |
| 10 | SC03 | Threat model: доработка (NIST AML, MITRE ATLAS) |
| 11 | API01 | API: опциональные поля target_stack, budget в FactoryRunRequest |
| 12 | D01, D02 | Синхронизация phases-task-list и implementation-assessment |
| 13 | 44, 45 | WS3 v1: минимальный web-UI решений + sprint-points + runbook |

Шаги 1–3 — быстрые полировки; 4–6 — ядро Factory 2.0; 7–13 — документация и усиление decision/операционного контура.

---

## 5. После спринта

- Запустить сборку и тесты: `docker build -t product-factory .` (по AGENTS.md).
- При наличии NEURAL_SERVICE_URL: проверить прогон с реальным LLM (runbook, curl POST /factory/run).
- Открыть оставшиеся задачи из [sprint-full-backlog.md](sprint-full-backlog.md) (P1–P2) по желанию.

---

## 6. Связанные документы

- [strategic-fork-factory-2.md](strategic-fork-factory-2.md) — развилка Factory 1.0/2.0
- [factory-2-planner-integration.md](factory-2-planner-integration.md) — план подключения LLM к Planner
- [neural-service-api.md](neural-service-api.md) — контракт нейросервиса
- [AGENTS.md](../AGENTS.md) — общие правила и команды
