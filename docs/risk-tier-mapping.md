# Risk Tier Mapping (Canonical)

Единый каталог risk tiers для Product Factory (Alpha): `low`, `medium`, `high`.

Документ фиксирует:
- Канонические уровни риска для `risk_profile.yaml`.
- Мэппинг на approvals.
- Мэппинг на budgets (tokens / tool calls / wall-clock).
- Совместимость со словарём tool registry (`read_only`, `write_limited`, `privileged`).

## 1. Каталог risk tiers

| Risk tier | Назначение | Approval по умолчанию | Бюджет (tokens / tool calls / wall-clock) |
|---|---|---|---|
| `low` | Анализ/чтение без side-effects | Не требуется | `200000 / 100 / 3600s` |
| `medium` | Изменения в repo/CI внутри контролируемого контура | В solo MVP не требуется; включается при multi-operator или prod | `100000 / 50 / 3600s` |
| `high` | Side-effects вне repo: deploy, secrets, policy/ACL changes | Обязателен human approval | `50000 / 20 / 1800s` |

## 2. Мэппинг на tool risk tiers (legacy naming)

| Canonical (`risk_profile`) | Tool registry (`contracts/tools.schema.json`) |
|---|---|
| `low` | `read_only` |
| `medium` | `write_limited` |
| `high` | `privileged` |

Примечание: в новых документах использовать `low/medium/high` как основной словарь; `read_only/write_limited/privileged` сохраняется как совместимый слой для tool-контрактов.

## 3. Явный policy-конфиг (reference)

```yaml
risk_tier_mapping:
  low:
    tool_risk_tier: read_only
    requires_human_approval: false
    budgets:
      max_tokens_per_run: 200000
      max_tool_calls_per_run: 100
      max_wall_clock_seconds: 3600

  medium:
    tool_risk_tier: write_limited
    requires_human_approval: false # solo MVP
    approvals:
      enable_when:
        - multi_operator_mode
        - target_environment in [prod]
    budgets:
      max_tokens_per_run: 100000
      max_tool_calls_per_run: 50
      max_wall_clock_seconds: 3600

  high:
    tool_risk_tier: privileged
    requires_human_approval: true
    budgets:
      max_tokens_per_run: 50000
      max_tool_calls_per_run: 20
      max_wall_clock_seconds: 1800
```

## 4. Где используется

- Входной контракт: `contracts/schemas/risk_profile.schema.json` (`riskTier: low|medium|high`).
- Политика approvals: `docs/approval-policy.md`.
- Риск-реестр: `docs/risk-register.md`.
