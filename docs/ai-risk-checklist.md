# AI Risk Checklist (Operational Short Form)

Краткий исполняемый чеклист для цикла AI-risk contour: governance, risk assessment, monitoring.

Базовый процесс и SLA: [ai-risk-contour-plan.md](ai-risk-contour-plan.md).
Источник threat scenarios: [threat_model.md](threat_model.md).
Source of truth по рискам: [risk-register.md](risk-register.md).

## Cadence

- Еженедельно: сбор KRI/KPI и проверка отклонений.
- Раз в 2 недели: governance + risk assessment review.
- Ежемесячно: AI-risk review и CAPA-статусы.
- Внепланово: `High/Critical` инцидент (до 24 часов), изменение trust boundary или tool-set (до 48 часов).

## 1) Governance (2-week review)

- [ ] Проверен и подтверждён текущий risk appetite.
- [ ] Проверены пороги approvals и privileged actions.
- [ ] Решения внесены в decision log (дата, owner, rationale, срок пересмотра, `R-xxx`).
- [ ] Проверена согласованность с `approval-policy.md`, `prohibited-agent-actions.md`, `adr/0002-layer-boundaries.md`.

## 2) Risk Assessment (2-week + trigger-based)

- [ ] Проверены триггеры пересмотра (новые tools, изменение границ, инциденты, regulatory updates).
- [ ] Новые/изменённые threat scenarios зафиксированы в `threat_model.md`.
- [ ] Для каждого нового сценария создан/обновлён `R-xxx` в `risk-register.md`.
- [ ] Для каждого активного `R-xxx` заданы owner, mitigation, contingency, metric.

## 3) Monitoring (weekly + monthly)

- [ ] Собраны: `policy denies`, `approval latency`, `incidents/near-misses`, `critical findings`.
- [ ] Проверена доля run с полным audit trail и traceability.
- [ ] Для отклонений выше порога создан CAPA с owner и дедлайном.
- [ ] CAPA-статусы синхронизированы с `risk-register.md` (open/overdue/closed).

## 4) Sync Gates (threat model <-> risk register)

- [ ] Любое изменение в `threat_model.md` отражено в `risk-register.md` не позднее 48 часов.
- [ ] Любой новый/эскалированный `R-xxx` имеет ссылку на threat scenario (или `pending` с дедлайном).
- [ ] Закрытие CAPA подтверждено обновлением обеих сторон: threat scenario и `R-xxx`.

## Artifacts per Review

- Обновлённые: `threat_model.md`, `risk-register.md`, decision log.
- При изменении порогов/контролей: `approval-policy.md` и/или ADR.
