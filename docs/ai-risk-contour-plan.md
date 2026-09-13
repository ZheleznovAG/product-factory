# AI Risk Contour Plan (NIST AI RMF + ISO/IEC 42001)

Цель: внедрить минимально достаточный процессный контур управления AI-рисками для Product Factory без изменения runtime-архитектуры и без расширения полномочий агентного слоя.

Статус: operational baseline (процессные элементы внедрены на уровне документации и обязательного review-цикла).

## 1) Рамка и принципы

### NIST AI RMF (для фабрики)

- `Govern`: роли владельца риска, policy применения AI, accountability, периодический review.
- `Map`: контекст использования (scope, trust boundaries, источники данных/промптов, misuse-сценарии).
- `Measure`: метрики риска и качества (policy deny rate, security findings, incident count, traceability).
- `Manage`: обработка риска через controls, approvals, escalation, rollback и обновление risk appetite.

### ISO/IEC 42001 (для фабрики)

- AIMS: формализованные policy, роли, процедуры и evidence.
- Risk and impact assessment: повторяемая оценка AI-рисков и их влияния на операции/безопасность.
- Operational controls: change management, incident response, monitoring, corrective actions.
- Continuous improvement: регулярные review, CAPA-подход и обновление документации.

## 2) Процессные элементы (операционный цикл)

### 2.1 Governance

- Владелец процесса: solo operator Product Factory.
- Cadence: раз в 2 недели governance review + внепланово при инцидентах/критических изменениях.
- Обязательные артефакты:
  - `docs/approval-policy.md` (policy и пороги approval).
  - `docs/prohibited-agent-actions.md` (запреты для агентного слоя).
  - `docs/adr/0002-layer-boundaries.md` (границы слоёв и side-effects).
  - `docs/risk-register.md` (актуальный риск-профиль).
- Decision log формат (минимум): дата, решение, owner, rationale, срок пересмотра, затронутые риски `R-xxx`.

Выход Governance:
- единые правила принятия решений по AI-рискам;
- трассируемость управленческих решений и waiver-исключений.

### 2.2 Risk Assessment

Cadence:
- плановый цикл: раз в 2 недели (в рамках governance review);
- trigger-based reassessment: в течение 48 часов после события-триггера.

Триггеры пересмотра:
- добавлен новый tool или archetype;
- изменены trust boundaries (API, Tool Executor, neural service, policy boundary);
- инцидент или near-miss;
- изменение regulatory assumptions.

Процедура (обязательно):
- идентификация сценариев (misuse, AML, operational failures) по `docs/threat_model.md`;
- оценка likelihood/impact;
- назначение owner + mitigation + contingency + metric;
- присвоение/обновление `R-xxx` в `docs/risk-register.md`.

Выход Risk Assessment:
- повторяемый цикл оценки рисков;
- явная связь threat scenario -> risk ID -> control/metric -> trigger.

### 2.3 Monitoring и Improvement Loop

Cadence:
- еженедельный сбор метрик;
- ежемесячный AI-risk review с CAPA.

Базовые KPI/KRI:
- `policy denies`;
- `approval latency`;
- `incidents/near-misses`;
- `critical findings` (security/supply chain);
- доля run с полным audit trail;
- статус CAPA (open/overdue/closed).

Операционные действия:
- сравнение факта с порогами и risk appetite;
- запуск CAPA с owner и дедлайном;
- обновление policy/порогов/контролей при повторяющихся отклонениях.

Выход Monitoring:
- операционный цикл мониторинга и улучшений;
- управляемое изменение порогов на основе фактических сигналов.

## 3) Связь с threat_model и risk-register

### 3.1 Роли документов

- [`docs/threat_model.md`](threat_model.md): источник threat scenarios, trust boundaries и первичных mitigation hypotheses.
- [`docs/risk-register.md`](risk-register.md): source of truth по риск-единицам (`R-xxx`), приоритетам, owner, mitigation, contingency, метрикам.

### 3.2 Правила синхронизации

- Любое изменение угрозы или trust boundary в `threat_model.md` обязано приводить к проверке/обновлению соответствующего `R-xxx` в `risk-register.md`.
- Любой новый или эскалированный риск в `risk-register.md` обязан иметь ссылку на релевантный threat scenario или явную пометку `threat scenario: pending`.
- Закрытие CAPA по риску требует подтверждения, что контроль отражён и в threat model, и в risk register.

### 3.3 Трассировочная матрица (минимум)

| Шаг | Источник | Действие | Результат |
|-----|----------|----------|-----------|
| 1 | `threat_model.md` | Обнаружен новый/изменённый сценарий | Создать или обновить `R-xxx` |
| 2 | `risk-register.md` | Оценить likelihood/impact/priority | Обновить mitigation, contingency, metric |
| 3 | Governance review | Решение по порогам/approvals/waiver | Запись в decision log |
| 4 | Monitoring review | Проверить KPI/KRI и CAPA | Обновить risk status и дату next review |

## 4) Чеклист внедрения и исполнения

Операционный short form для регулярного исполнения: [ai-risk-checklist.md](ai-risk-checklist.md).

### 4.1 Governance checklist

- [ ] Назначен owner AI-risk контура и зафиксирован cadence review (раз в 2 недели).
- [ ] Утверждён текущий risk appetite и критерии human approval.
- [ ] Ведётся decision log (дата, решение, owner, rationale, срок пересмотра, `R-xxx`).
- [ ] Policy синхронизирована с `approval-policy`, `prohibited-agent-actions`, `0002-layer-boundaries`.

### 4.2 Risk Assessment checklist

- [ ] На каждом review проверены trigger-события.
- [ ] Для новых угроз выполнена оценка likelihood/impact.
- [ ] Для каждого активного `R-xxx` указаны owner, mitigation, contingency, metric.
- [ ] `threat_model.md` и `risk-register.md` синхронизированы в рамках одного review-окна.

### 4.3 Monitoring checklist

- [ ] Метрики `policy denies`, `approval latency`, `incidents/near-misses`, `critical findings` собраны.
- [ ] Проведён monthly AI-risk review и зафиксированы CAPA-решения.
- [ ] Для отклонений выше порога назначены owner и дедлайн remediation.
- [ ] Изменения policy/порогов отражены в decision log и risk-register.

## 5) SLA для пересмотра документов

- Изменение в `threat_model.md` -> обновление `risk-register.md`: не позднее 48 часов.
- Критический инцидент (`High`/`Critical`) -> внеплановый review governance: не позднее 24 часов.
- Monthly AI-risk review -> закрытие/перепланирование CAPA: не позднее 5 рабочих дней после review.

Критерии готовности контура (минимум):
- governance policy и decision log ведутся регулярно;
- risk assessment выполняется по расписанию и по триггерам;
- monitoring-метрики публикуются в review-ритме;
- `threat_model.md` и `risk-register.md` синхронизируются без ручных пропусков.
