# Policy Simulation Dashboard

Сводка по срабатываниям policy/approvals/блокировок из `audit.log` для Tool Executor.

## Что показывает

- `approval_required` по действиям Tool Executor (`tool:<name>`) и `risk_tier`.
- `tool_call_denied` (блокировки) по `toolName` и `risk_tier`.
- `tool_call_executed` (фактические выполнения) по `toolName` и `risk_tier`.

Risk tier для tool берётся из `contracts/tools.registry.json` (или `FACTORY_TOOLS_REGISTRY_PATH`).

## API

`GET /factory/policy-stats`

Параметры:

- `tenantId` (опционально) — фильтр по тенанту (`default` = runId без префикса `tenant::`).
- `since` (опционально) — ISO-8601 instant, например `2026-02-20T00:00:00Z`.

Пример:

```bash
curl "http://localhost:8080/factory/policy-stats?tenantId=default&since=2026-02-20T00:00:00Z"
```

Ответ:

```json
{
  "generatedAt": "2026-02-27T01:00:00Z",
  "auditLogPath": "audit.log",
  "tenantId": "default",
  "since": "2026-02-20T00:00:00Z",
  "totals": {
    "processedEvents": 120,
    "approvalRequiredEvents": 7,
    "blockedEvents": 3,
    "executedToolCalls": 42,
    "uniqueRiskTiers": 3,
    "uniqueActionTypes": 5
  },
  "byRiskTier": [
    {
      "riskTier": "high",
      "approvalRequiredEvents": 5,
      "blockedEvents": 2,
      "executedToolCalls": 6
    }
  ],
  "byActionType": [
    {
      "actionType": "create_github_repo",
      "riskTier": "high",
      "approvalRequiredEvents": 4,
      "blockedEvents": 1,
      "executedToolCalls": 2
    }
  ]
}
```

## Интерпретация для dashboard

- `approvalRequiredEvents` растёт: политика часто переводит инструмент в human-in-the-loop.
- `blockedEvents` растёт: policy deny/guardrails реально блокируют действия.
- `executedToolCalls` растёт при низких `blockedEvents`: контур пропускает выполнение в допустимом профиле риска.

Рекомендуемый baseline для weekly review:

- high-risk tools: approvals = ожидаемо, blocks > 0 анализировать по причинам.
- low/medium-risk tools: blocks должны быть редкими; при росте проверять drift policy и контракты.
