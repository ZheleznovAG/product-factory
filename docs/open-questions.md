# Не решённые вопросы

Вопросы и решения, которые явно отложены или помечены TBD. По итогам экспертного разбора (2026-02-20) часть пунктов **закрыта** через ADR и decision-points-v1. Обновлять по мере закрытия.

---

## Закрыто решениями (ADR и decision-points-v1)

| Вопрос | Решение | Где зафиксировано |
|--------|---------|-------------------|
| Провайдер (cloud vs on-prem) | K8s + S3-совместимое (MinIO), без привязки к облаку | [ADR-0003](adr/0003-deployment-substrate-and-cloud-strategy.md) |
| Версии инструментов (Temporal, OPA, Argo CD) | Пин через versions.lock, обновление только через ADR + CI | [ADR-0004](adr/0004-dependency-version-pinning.md), [DEPENDENCY_POLICY.md](DEPENDENCY_POLICY.md) |
| Retention логов и audit | Audit: 90 дней online, 1 год cold; логи/трейсы: 30 дней | [ADR-0005](adr/0005-log-audit-trace-retention.md) |
| Хранение секретов для MVP | K8s Secrets + env (dev); интерфейс SecretsProvider; Vault — post-MVP | [ADR-0006](adr/0006-secrets-management-mvp.md) |
| Один URL или два для LLM | Один URL + роутинг по role (PLANNER \| CODEGEN \| EVAL \| EMBED) | [ADR-0007](adr/0007-llm-gateway-api-shape.md) |
| Целевой бюджет cost per run | Лимиты: max_llm_calls 20, max_llm_tokens_total 150k, max_tool_calls 30, max_wall_clock 3600s; мягкая цель <$0.50 S-run, <$2 M-run | [decision-points-v1.md](decision-points-v1.md) §1 |
| SLA латентности LLM (p99) | Planner p99 <20s, Codegen p99 <60s; timeouts 30s / 120s | [decision-points-v1.md](decision-points-v1.md) §2 |
| Размер индекса RAG / SLA retrieval | docs_count ≤200k, storage ≤10GB, p99 retrieval ≤500ms; tripwire для миграции на vector DB | [decision-points-v1.md](decision-points-v1.md) §3 |
| Cycle time «запрос → staging» | Классы S (<30 мин), M (<2 ч), L (<8 ч); цель 80% S-run <30 мин | [decision-points-v1.md](decision-points-v1.md) §2.2 |

---

## Всё ещё открыто

| Вопрос | Где | Примечание |
|--------|-----|------------|
| **Конкретный облачный провайдер** при выходе за MVP | ADR-0001, ADR-0003 | В MVP — не требуется; при масштабировании — пересмотр по триггерам ADR-0003. |
| **FactoryRunTest с @Ignore:** оставить или починить | Код, implementation-assessment | Рекомендация эксперта: стабилизировать testApplication, убрать @Ignore; интеграционный smoke — через Docker/CI. |
| **Data-map:** точные сроки retention для отдельных типов данных | [data-map.md](data-map.md) | Общие сроки заданы в ADR-0005; при необходимости уточнить по типам в data-map. |

---

## Опциональные направления (решение «делать или нет»)

- RAG + pgvector для MVP — **не в MVP**, интерфейс заложить (decision-points-v1: RAG off by default).
- Третий архетип (data pipeline) — по необходимости.
- Multi-tenancy + portal/CLI — отложено; триггер в decision-points / risk-register-v1.
- Валидация контрактов в CI — **есть** (job validate-contracts в ci.yml).
- Health endpoint для образа фабрики — **есть** (GET /health, /health/ready, /health/neural; см. runbook).
- Подключение LLM в контуре — по умолчанию out of band, с возможностью in-cluster позже.

---

**Пересмотр:** при закрытии решения обновлять этот файл и при необходимости соответствующий ADR или policy.
