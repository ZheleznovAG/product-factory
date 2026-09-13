# Аудит Product Factory (scheduled 2026-02-26)

## Срез
- Дата: **2026-02-26**.
- Фокус: состояние `grand-pipeline`, документация, backlog, ключевые риски.
- Основание: `docs/grand-pipeline-plan.md`, `docs/grand-pipeline-tasks.md`, `docs/roadmap-checklist.md`, `docs/sprint-full-backlog.md`, `docs/risk-register.md`.

## Что сделано (grand-pipeline и docs)
- Базовый контур фабрики закрыт по фазам **Alpha/Beta/MVP/Scale** (`roadmap-checklist.md`): контракты v1, deterministic workflow core, Tool Executor + policy/approvals, LLM Planner/Codegen (через `NEURAL_SERVICE_URL`), eval gate.
- По `grand-pipeline-tasks.md` закрыто **4/38** задач:
  - `#19` SLSA provenance/attestations.
  - `#29` Провижининг + headless browser.
  - `#31` Контракт `artifact_manifest.json`.
  - `#34` Стабильный `FactoryRunTest`.
- Операционные и продуктовые документы в актуальном базовом контуре есть: `usage-overview`, `how-it-works-and-verify`, `runbook`, `sprint-full-backlog`, `sprint-factory2-howto`.
- Разделение «текущая реализация vs целевое видение» зафиксировано в docs (architecture/vision/strategy), что снижает риск смешения roadmap и факта.

## Что в бэклоге
- По grand-pipeline открыто **34/38** задач. Крупные незакрытые блоки:
  - Intent layer v1: LLM clarification/generation/candidates, адаптивные вопросы, early stop, свободные ответы.
  - API `POST /intent/estimate` и `POST /experience/generate`, плюс рабочий сценарий `intent -> run`.
  - Runtime SLO/cost enforcement: pre-run budget checks, online promotion signals, alerting/gates.
  - Environments: `local-docker`/`remote-ssh` (реализация либо явный ADR out-of-scope).
  - RAG ingestion + pgvector, offline eval (RAGAs), расширение eval datasets.
  - GitOps до prod, canary/rollback, multi-tenancy и self-serve (portal/CLI).
  - Полное заполнение/воспроизведение `artifact_manifest`, session/reference flow, e2e `intent -> run`.
  - Финальная синхронизация ADR/runbook/implementation-assessment/risk-register.
- Приоритет по `sprint-full-backlog.md`: сначала P0/P1 (операционная полировка, audit integrity, SLO/cost, supply-chain/AI-risk), затем P2 (durable engine, RAG, продуктивизация).

## Основные риски
- **R-001 Unbounded consumption**: перерасход токенов/времени/tool calls до полного budget enforcement.
- **R-003 Excessive agency**: размывание границ слоёв и попытки обхода policy/tool executor.
- **R-004 Non-idempotent retries**: дубли side-effects при ретраях и расширении tool-сета.
- **R-006 Supply-chain compromise**: неполное единообразие SBOM/scan/sign/verify по всем release-путям.
- **R-010 AI governance gap**: неполная операционализация AI-risk contour (NIST AI RMF / ISO 42001).
- **R-012 SLO/cost drift**: деградация latency/success/cost без полного production-grade контура сигналов и gate.
- **R-014 Multi-tenant isolation breach**: риск пересечения данных между tenant при переходе к multi-tenancy.

## Вывод
- Платформа рабочая и документированная в текущем масштабе (Alpha-Beta-MVP-Scale).
- `grand-pipeline` реализован частично: закрыты отдельные шаги, но системные блоки ещё в активном backlog.
- Ближайший фокус: `intent API + runtime SLO/cost + environments + e2e intent->run + синхронизация docs/ADR/risk`.
