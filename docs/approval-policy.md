# Политика approvals (когда требуется человек)

Фаза 2. Правила принятия решений: при каких действиях фабрика обязана запросить ручной approval перед выполнением.

## 1. По risk tier инструмента

Канонический каталог tiers и budgets: [risk-tier-mapping.md](risk-tier-mapping.md).

| Canonical tier | Tool tier (compatibility) | Human approval | Бюджет (tokens / tool calls / wall-clock) |
|---|---|---|---|
| `low` | `read_only` | не требуется | `200000 / 100 / 3600s` |
| `medium` | `write_limited` | в solo MVP не требуется; включается при multi-operator/prod | `100000 / 50 / 3600s` |
| `high` | `privileged` | требуется (`requires_human_approval: true`) | `50000 / 20 / 1800s` |

## 2. По типу запроса (product request)

- **High-risk запрос:** если Policy Engine помечает запрос как high-risk (по метаданным, объёму, целевой среде), перед стартом workflow запрашивается approval оператора.
- **Первый запуск нового архетипа:** опционально требовать approval до первого использования нового архетипа в проде (после MVP).

## 3. По бюджетам

- При достижении лимита (токены, tool calls, wall-clock) workflow останавливается; продолжение только по явному решению оператора (например, повышение лимита на один run).
- Unbounded consumption: авто-kill без approval; постфактум разбор и при необходимости одобрение исключений.

## 4. По результатам quality gates

- **Security gate (Trivy и т.д.):** при критических уязвимостях — блок merge/release; разблокировка только по решению человека (triage, исключение, патч).
- **Eval gate:** при падении ниже порога — блок; разблокировка после разбора и (при необходимости) approval на временное понижение порога.

## 5. Реализация

- В контракте каждого tool поле `requires_human_approval` задаёт требование на уровне инструмента.
- Policy Engine (OPA) проверяет: если tool в allowlist и `requires_human_approval: true`, Workflow перед вызовом Tool Executor запрашивает approval (очередь задач, уведомление, API «approve/reject»).
- Audit Log фиксирует: запрос approval, кто и когда одобрил/отклонил.
- Для мэппинга `low/medium/high` ↔ `read_only/write_limited/privileged` использовать [risk-tier-mapping.md](risk-tier-mapping.md) как source of truth.

## 6. Явный manual approval для high-risk release

- High-risk actions в runtime (например `create_github_repo`, `push_repo_to_github`) всегда идут через `require_human_approval` и фиксируются как `risk_tier:high` в approval record.
- Для release pipeline используется отдельный job `high-risk-manual-approval` в workflow `.github/workflows/release.yml` c GitHub Environment `high-risk-release`.
- Требование к репозиторию: для environment `high-risk-release` включить **Required reviewers**. Без ручного подтверждения release job не продолжится.
- Только после manual approval запускаются release-операции: publish образов, SBOM (Syft), подпись/verify (Cosign), provenance attestation.
- В CI обязателен статический gate `release-policy-gate` (workflow `.github/workflows/ci.yml`), который проверяет, что `release.yml` сохраняет инварианты: manual approval, SBOM, sign/verify, attestation.

Детали контракта: [contracts/tools.schema.json](../contracts/tools.schema.json). Запрещённые действия: [prohibited-agent-actions.md](prohibited-agent-actions.md).
