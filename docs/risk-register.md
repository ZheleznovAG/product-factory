# Risk Register: Product Factory

Фаза 1. Реестр рисков с митигацией и планом отката. Расширенный реестр с порогами и триггерами для MVP: [risk-register-v1.md](risk-register-v1.md). Формат: ID, описание, вероятность, влияние, митигация, контингенси, метрика. Обновлять при изменении границ фабрики и по результатам инцидентов. Дополнено по [system-audit-corrective-plan.md](system-audit-corrective-plan.md).

Канонический каталог risk tiers и мэппинг approvals/budgets: [risk-tier-mapping.md](risk-tier-mapping.md).

Процессная связь: цикл governance/risk-assessment/monitoring задан в [ai-risk-contour-plan.md](ai-risk-contour-plan.md), короткий операционный чеклист — [ai-risk-checklist.md](ai-risk-checklist.md); source threat scenarios — [threat_model.md](threat_model.md).

| ID | Риск | Вероятность | Влияние | Митигация | Контингенси | Метрика |
|----|------|-------------|---------|-----------|-------------|---------|
| R-001 | Unbounded consumption (токены/итерации/время) | Высокая | Высокое | Бюджеты (токены, tool calls, wall-clock), лимиты на gateway, allowlist в policy | Авто-kill workflow по бюджетам; режим «agents read-only»; разбор траекторий | cost/run, toolCalls/run |
| R-002 | Prompt injection через RAG/внешние источники | Средняя | Высокое | Фильтрация источников, context packer с цитированием, политики tool-call + approvals | Отключение внешних источников, ротация ключей, инцидент-процедура | denied tool attempts |
| R-003 | Excessive agency / размытая граница слоёв (AI начинает «управлять») | Высокая | Высокое | Жёсткий input contract, strict schemas; approvals на write/deploy; PDP/PEP с audit | Отключить privileged tools; read-only режим агента; расследование по trace logs | policy denies |
| R-004 | Неидемпотентные действия при ретраях workflow | Средняя | Высокое | Идемпотентность tools/activities, idempotency keys, аудит, тесты ретраев | Cleanup workflow; восстановление из desired state (GitOps); пост-мортем | retries, side-effect incidents |
| R-005 | Утечки секретов (логи, промпты, tool output) | Средняя | Высокое | Секреты через централизованное хранилище; redaction; запрет логирования чувствительных полей | Ротация секретов, отзыв токенов, аудит | — |
| R-006 | Supply-chain компрометация (зависимости, образы) | Средняя | Высокое | SBOM + scan + подпись + verify gates; provenance/attestation политика | Блок релиза; откат на последний подписанный digest; патч | scan findings |
| R-007 | Срыв сроков (solo, переключение контекста) | Высокая | Среднее | WIP-лимит 1–2 темы; ежедневный артефакт; резка scope | Freeze функций; фокус на стабилизации; перенос в backlog | — |
| R-008 | Недостаточная наблюдаемость | Средняя | Высокое | OTel как стандарт; обязательный audit log для tool calls | Остановка rollout; добавление instrumentation в приоритет | — |
| R-009 | Слом rollback из‑за GitOps (autosync) | Средняя | Среднее/Высокое | Учитывать ограничения Argo CD autosync (rollback невозможен при enabled autosync); фиксировать процедуру в runbook | Временно отключить autosync, revert, включить обратно | — |
| R-010 | Неполное внедрение AI governance / NIST AI RMF (в т.ч. регуляторные требования, EU AI Act и др.) | Средняя | Среднее | План внедрения AI-risk contour по [ai-risk-contour-plan.md](ai-risk-contour-plan.md): роли, контрольные точки, артефакты governance; watch-процесс; decision points | Переприоритизация roadmap; ограничение high-risk функций | статус выполнения плана AI-risk contour |
| R-011 | Масштабирование инфраструктуры (Kubernetes «слишком рано») | Средняя | Среднее | Начать с простого runtime; переходить к K8s по триггерам | Миграция на managed K8s после стабилизации core | — |
| R-012 | Нарушение SLO и деградация стоимости (latency/success/cost drift) | Средняя | Высокое | Единый SLO+Cost контур: success rate/p99/token budget cap; pre-run budget enforcement (soft/hard cap), Prometheus alert rules, CI `slo-gate`, canary + smoke перед promotion | Safe-mode, ограничение concurrency/tool budget, rollback на стабильный digest, временная блокировка high-cost tenant workflows | success rate (30d), p99 latency (30d), llm_input_tokens/cap (30d), budget breaches |
| R-013 | AI-risk исполнения: jailbreak/prompt-injection/галлюцинации в proposals с попыткой unsafe tool path | Средняя | Высокое | Tool allowlist + policy deny по аргументам секретов, HITL approvals для privileged действий, trace/eval regression gates, governance/monitoring по [ai-risk-contour-plan.md](ai-risk-contour-plan.md) | Отключить privileged tools, перевести в read-only агентный режим, ротация ключей и инцидент-разбор по trace/audit | policy denies, approval rate, eval gate pass rate, ai-risk incidents |
| R-014 | Нарушение multi-tenant изоляции (пересечение run/audit/artifacts между tenantId) | Низкая/Средняя | Высокое | Строгая валидация `tenantId`, обязательный tenant scoping для API/approval/registry/audit ключей (ADR 0014), tenant-scoped budget policies, тесты на cross-tenant доступ | Немедленная блокировка затронутого tenant path, forensic аудит, уведомление владельцев tenant, hotfix + регрессионные тесты | cross-tenant access incidents, tenant-scoped auth/policy violations |

**Владение:** оператор фабрики (solo).  
**Пересмотр:** при добавлении новых tools, изменении политик, после инцидентов.

## Операционные фокусы (SLO/cost, AI-risk, multi-tenancy)

### SLO/cost

- Источники контроля: `/metrics`, `deploy/prometheus-rules.yml`, CI `slo-gate`.
- Enforced в runtime: `COST_BUDGET_SOFT_CAP_EXCEEDED` (`429`) и `COST_BUDGET_HARD_CAP_EXCEEDED` (`403`).
- Обязательное действие при sustained breach: rollback desired state на стабильный digest + post-incident review.

### AI-risk

- Минимальный baseline: policy deny + approvals + audit + eval gates.
- Все инциденты AI-risk должны иметь связь с threat scenarios в [threat_model.md](threat_model.md).
- После инцидента обязательны: корректировка policy/approvals, обновление risk score и проверка регрессии через eval.

### Multi-tenancy

- Tenant isolation контур: API/approval/audit/registry строго tenant-scoped (ADR 0014).
- Для production/staging `tenantId` должен быть явным; `default` использовать только для совместимости/локальных прогонов.
- Triage и budget governance ведутся отдельно по tenant (owner/on-call, лимиты, алерты).

## Sync Gate с Threat Model

- Любой новый или эскалированный `R-xxx` должен ссылаться на соответствующий threat scenario из [threat_model.md](threat_model.md) или иметь временную пометку `threat scenario: pending` с дедлайном.
- Изменение threat scenarios в [threat_model.md](threat_model.md) должно приводить к обновлению соответствующего `R-xxx` не позднее 48 часов.
- Операционный порядок проверок выполняется по [ai-risk-checklist.md](ai-risk-checklist.md).

## Дополнительные источники

- Расширенная таблица рисков, приоритезированная roadmap и варианты митигации (AuthN/AuthZ, per-run budgets, OPA fail-closed, staging/GitOps): [deep-research-report-9.md](deep-research-report-9.md).
