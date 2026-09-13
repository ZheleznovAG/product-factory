# Threat model

Фаза 6. Угрозы для фабрики (OWASP LLM Top 10, NIST AML, supply chain) и контролы.

**Статус:** заполнено; пересматривать при добавлении новых tools, изменении границ, прохождении Gate D.

**Процессная связь:** governance/risk-assessment/monitoring цикл описан в [ai-risk-contour-plan.md](ai-risk-contour-plan.md), краткий исполняемый чеклист — [ai-risk-checklist.md](ai-risk-checklist.md); изменения threat scenarios должны синхронно отражаться в [risk-register.md](risk-register.md).

---

## Scope

- **В scope:** API фабрики (POST /factory/run, approvals), workflow (планировщик, кодоген, tool executor), артефакты (репо из архетипов, образы), audit log, политики (OPA), нейросервис (LLM gateway).
- **Вне scope (пока):** детальный threat model архетипов (catalog-service, web-app) как отдельных продуктов; инфраструктура деплоя (K8s/Argo) — см. runbook и risk register.

---

## Trust boundaries

| Граница | Внутри (доверяем) | Снаружи (не доверяем по умолчанию) |
|--------|--------------------|-------------------------------------|
| API → фабрика | Валидированный JSON, контракты | Цель/constraints от вызывающего; возможен prompt injection при подключении LLM |
| Фабрика → OPA | Запрос policy | Ответ OPA (конфиг политики — под контролем оператора) |
| Фабрика → нейросервис | Запрос к LLM | Ответы модели (галлюцинации, jailbreak) |
| Sandbox executor → ФС | Копирование архетипа в целевую директорию | Внешние репо, произвольный код не выполняем |
| CI → образы | Сборка из нашего кода, SBOM, подпись | Зависимости (Gradle/npm) — снижаем через scan и SBOM |

---

## Таксономии и ссылки

- **NIST AI 100-2 (Adversarial Machine Learning):** публикация NIST с терминологией и структурой AML-угроз для оценки рисков и выбора контролей. В документе описываются классы атак на данные, модели и выводы системы (например poisoning, evasion, privacy/information disclosure, misuse).
  - Актуальная публикация (e2025): https://csrc.nist.gov/pubs/ai/100/2/e2025/final
  - DOI: https://doi.org/10.6028/NIST.AI.100-2e2025
  - Назначение в Product Factory: единый словарь AI-рисков для risk-register, threat model и policy-driven контролей.
- **MITRE ATLAS:** knowledge base по поведению adversary в AI/ML-сценариях, построенная как ATT&CK-подобная матрица тактик/техник. Техники имеют идентификаторы вида `AML.Txxxx`.
  - Matrix: https://atlas.mitre.org/matrices/ATLAS
  - Techniques: https://atlas.mitre.org/techniques
  - STIX/данные (machine-readable): https://github.com/mitre-atlas/atlas-navigator-data
  - Назначение в Product Factory: трассируемая привязка threat-сценариев к конкретным adversarial techniques.

Примечание:
- NIST AI 100-2 не использует короткие ID техник, как ATLAS; поэтому в таблице указываются классы/категории угроз по NIST.
- Не каждая угроза фабрики является чисто AML-угрозой. Для классических AppSec/операционных рисков ставим `ATLAS: n/a`, а в NIST-части отмечаем, что это вне прямой AML-таксономии.

---

## Угрозы и митигации

Связь с [risk-register.md](risk-register.md): ID рисков указаны в скобках.

| Угроза | Описание | Митигация | ATLAS / NIST AI 100-2 |
|--------|----------|-----------|------------|
| **Подмена артефактов / supply chain** (R-006) | Вредоносные зависимости или образы без верификации | SBOM (Syft), сканирование (Trivy HIGH/CRITICAL), подпись и verify (Cosign) в CI для образа фабрики и архетипов; артефакт-реестр с версиями | ATLAS: `AML.T0010` (AI Supply Chain Compromise); NIST: poisoning/supply-chain compromise (taxonomy class) |
| **Утечка секретов** (R-005) | Секреты в логах, промптах, ответах tools | Секреты через ENV/.env; запрет логирования чувствительных полей; redaction в audit при необходимости | ATLAS: `AML.T0025` (Exfiltration via Cyber Means), `AML.T0086` (Exfiltration via AI Agent Tool Invocation); NIST: privacy attacks / information disclosure |
| **Несанкционированный доступ к API** | Вызов /factory/run или approvals без прав | OPA policy (data.factory.allow); при необходимости — auth на API Gateway; audit log всех запросов | ATLAS: n/a (classic API/AppSec threat); NIST: outside direct AML taxonomy (governance/access control risk) |
| **Excessive agency** (R-003) | Агент выполняет неразрешённые действия | Жёсткие контракты tools (JSON Schema), allowlist в policy; human approval для чувствительных шагов (approvals API) | ATLAS: partial overlap with agent/tool abuse scenarios; NIST: misuse/abuse class (GenAI operational risk) |
| **Prompt injection / jailbreak** (R-002) | Вход от пользователя влияет на поведение LLM | При подключении LLM — фильтрация, контекст с цитированием, политики на tool-call; approvals на write/deploy | ATLAS: `AML.T0051` (LLM Prompt Injection), `AML.T0054` (LLM Jailbreak); NIST: GenAI misuse/evasion classes |
| **Неидемпотентные действия при ретраях** (R-004) | Дублирование side-effects при повторах | Idempotency keys в tools (create_repo_from_archetype); аудит; тесты ретраев | ATLAS: n/a (reliability/control-plane risk); NIST: outside direct AML taxonomy |
| **Недостаточная наблюдаемость** (R-008) | Невозможность разбора инцидентов | OTel traces, обязательный audit log (tool_call_executed, state_changed); runbook и процедуры отката | ATLAS: n/a (detection/forensics control gap); NIST: outside direct AML taxonomy |

---

## Контроли (summary)

- **Supply chain:** SBOM + Trivy + Cosign для образа фабрики (ci.yml) и архетипов (ci-archetype-*.yml).
- **Policy:** OPA для allow/deny и require_human_approval; audit решений.
- **Approvals:** Human-in-the-loop для операций, требующих подтверждения; API и CLI в runbook.
- **Audit log:** Все значимые события (run, tool calls, state, approvals) в JSONL; inputDigest/outputDigest для целостности.
- **Контракты:** Валидация входов и tool-вызовов по схемам; CLI validate.

Пересмотр: при добавлении новых tools, изменении политик, после инцидентов. Детальный реестр рисков — [risk-register.md](risk-register.md).

---

## Чеклист пересмотра threat model

- [ ] Добавлен новый tool → проверить allowlist в [contracts/tools.registry.json](../contracts/tools.registry.json) и [policies/opa/data.json](../policies/opa/data.json); при необходимости — новая строка в таблице угроз.
- [ ] Изменены границы (Tool Executor, approval flow) → обновить trust boundaries и митигации.
- [ ] Инцидент по supply chain / секретам / agency → зафиксировать в [risk-register.md](risk-register.md), при необходимости добавить митигацию в таблицу.
- [ ] Любое изменение threat scenario синхронизировано с `R-xxx` в [risk-register.md](risk-register.md) (SLA: до 48 часов, см. [ai-risk-checklist.md](ai-risk-checklist.md)).
- [ ] Прохождение Gate D (audit) → зафиксировать дату пересмотра в заголовке документа.

## Где реализованы контроли

| Контроль | Где |
|----------|-----|
| Supply chain (SBOM, Trivy, Cosign) | [.github/workflows/ci.yml](../.github/workflows/ci.yml) (supply-chain-factory), [ci-archetype-catalog.yml](../.github/workflows/ci-archetype-catalog.yml), [ci-archetype-web-app.yml](../.github/workflows/ci-archetype-web-app.yml) |
| OPA policy | [policies/opa/rego/factory.rego](../policies/opa/rego/factory.rego), [policies/opa/data.json](../policies/opa/data.json) |
| Approvals API | [FactoryApi.kt](../src/main/kotlin/productfactory/api/FactoryApi.kt) (approvals, answer); [runbook.md](runbook.md) § Human-in-the-loop |
| Audit log, digest | [WorkflowRunner](../src/main/kotlin/productfactory/workflow/WorkflowRunner.kt), формат JSONL в [runbook.md](runbook.md) § Audit log |
| Контракты tools, validate | [contracts/schemas/](../contracts/schemas/), CLI `validate` в [runbook.md](runbook.md) |
