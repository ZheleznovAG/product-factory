# Пайплайн завершения (Completion Pipeline)

**Назначение:** актуальный пайплайн только из оставшихся задач — то, что ещё не реализовано после проверки по коду (2026-02-27). Предыдущий «Grand Pipeline» на 38 шагов выполнен примерно на 60%; здесь — только незакрытые пункты.

**Скрипт:** [scripts/run_completion_pipeline.py](../scripts/run_completion_pipeline.py).

**Запуск:** `python3 scripts/run_completion_pipeline.py [--dry-run] [--allow-docker] [--step N] ...`

---

## Откуда взяты задачи

- Оставшиеся пункты из [grand-pipeline-tasks.md](grand-pipeline-tasks.md) (без [x]).
- Дополнительно: A01, API01, SC02, SC04, SC05 из [sprint-full-backlog.md](sprint-full-backlog.md) и [what-to-do-full.md](what-to-do-full.md).

Что уже сделано — см. сводку в [what-to-do-full.md](what-to-do-full.md).

---

## Шаги пайплайна завершения (16 шагов)

### Наблюдаемость и SLO

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 1 | Документация уточнения и аудио-слой | Раздел про аудио (ASR → текст, TTS для вопросов) в intent-clarification-protocol. |
| 2 | Prometheus rules и Grafana SLO/cost | deploy/prometheus-rules.yml, подключение в prometheus.yml; дашборд SLO+cost; runbook про алерты. |
| 3 | Онлайн-сигналы SLO/cost для promotion | Использование /metrics и артефакт-реестра для решения о promotion; slo-promotion-signals.md. |

### Окружения

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 4 | ADR local-docker и remote-ssh | ADR 0012: решение по local-docker (конфиг vs запуск в compose) и remote-ssh (реализовать или out of scope). environments.md обновлён. |
| 5 | Local-docker и/или remote-ssh | По ADR: либо LocalDockerEnvironmentProvider/SSH-runner, либо явная пометка в коде и docs. |

### RAG и качество

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 6 | RAG ingestion + pgvector | Загрузка архетипов/доков в pgvector, версионирование индекса; опционально точка в планировщике. Документ. |
| 7 | Offline eval RAGAs и датасеты | Сценарий RAGAs (faithfulness, relevance); eval-ragas.md; при необходимости расширение eval gate. |

### Комплаенс и supply chain

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 8 | Контур AI-risk | Процессные элементы (governance, risk assessment, мониторинг) по ai-risk-contour-plan; связь с threat_model и risk-register. |
| 9 | Release с SBOM/подписью и high-risk approval | Политика: каждый release с SBOM и подписью; high-risk — ручной approval. Проверка в CI/gates, документ. |
| 10 | SLSA аттестации по политике | Документ и настройка по slsa-attestation-plan (если ещё не покрыто в supply-chain-factory). |

### Доставка и UX

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 11 | GitOps CD до prod | Реализация или детальная процедура по gitops-cd-prod-plan; обновить deploy и runbook. |
| 12 | Canary и откат | Схема canary и процедура отката за минуты в runbook. |
| 13 | Role registry и шаблоны по ролям | Реестр ролей и шаблоны промптов на роль; использование в LlmAgentPlanner/Codegen. Документ. |
| 14 | Веб-форма решений (опционально) | Минимальный UI: план, риски, варианты; сбор решений; вызов answer/approvals. Или явное «out of scope» в runbook. |
| 15 | Portal/CLI self-serve | Уточнить в runbook: потребитель получает сервис без копипаста (CLI уже есть; портал — по желанию). |

### Документация и аудит

| # | Задача | Критерий приёмки |
|---|--------|------------------|
| 16 | ADR 0011/0012, implementation-assessment, runbook, risk-register | ADR 0011 (источник SBOM/signature), ADR 0012 (окружения). implementation-assessment без устаревших placeholder. Runbook и risk-register актуализированы (SLO/cost, AI-risk, multi-tenancy). |

---

## Опциональные пункты (вне основного пайплайна)

- **A01** — обязательные digest в audit: проверить и задокументировать формат/дефолты.
- **API01** — расширение FactoryRunRequest (target_stack, output_artifacts, risk_profile, approval_mode, budget).
- **Расширение FactoryRunRequest** уже частично есть (target_stack в API); при необходимости добавить остальные поля.

---

## Запуск

```bash
# Из корня репозитория

# Просмотр шагов
python3 scripts/run_completion_pipeline.py --dry-run

# Полный пайплайн
python3 scripts/run_completion_pipeline.py --allow-docker

# С автоматическим возобновлением при лимите Codex (ожидание и повтор с --resume)
# По умолчанию запускается именно completion (16 шагов). Для planning используйте --pipeline planning.
python3 scripts/run_pipeline_with_resume.py --allow-docker

# Один шаг (например 2 — Prometheus/Grafana)
python3 scripts/run_completion_pipeline.py --allow-docker --step 2

# Диапазон
python3 scripts/run_completion_pipeline.py --allow-docker --from-step 1 --to-step 5
```

**Возобновление при лимите Codex:** скрипт [scripts/run_pipeline_with_resume.py](../scripts/run_pipeline_with_resume.py) запускает пайплайн и при ошибке из-за rate limit/квоты ждёт время из state (`codex_retry_after_ts`), затем перезапускает с `--resume`. Опции: `--wait-min N`, `--max-resume N`, `--no-wait` (выйти с подсказкой).

Состояние: `scripts/.completion_pipeline_state.json`.

---

## Связь с другими документами

| Документ | Содержание |
|----------|------------|
| [what-to-do-full.md](what-to-do-full.md) | Полный перечень задач и статусов, сводка «что реализовано». |
| [grand-pipeline-tasks.md](grand-pipeline-tasks.md) | Исходный чеклист на 38 шагов (многие уже [x]). |
| [grand-pipeline-plan.md](grand-pipeline-plan.md) | Исходный план Grand Pipeline. |
| [sprint-full-backlog.md](sprint-full-backlog.md) | Бэклог по приоритетам P0–P2. |

---

*Пайплайн завершения — актуальный список оставшейся работы; обновлять по мере закрытия шагов.*
