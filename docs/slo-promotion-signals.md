# SLO Promotion Signals

- **Status:** Implemented (Factory 2.0)
- **Date:** 2026-02-27
- **Scope:** сигналы и пороги для решения о promotion (`auto` / `semi_auto`) на базе `/metrics` и artifact registry.

Связанные документы: [slo-cost-draft.md](slo-cost-draft.md), [observability-and-logs.md](observability-and-logs.md), [slo-ci-gate.md](slo-ci-gate.md).

## 1. Источники сигналов

Решение о promotion принимает endpoint:

- `POST /factory/promotion/{runId}/evaluate`

Он объединяет два источника:

1. `/metrics` (агрегаты reliability/latency/cost).
2. Artifact registry (`{runId}.json` + `*.manifest.done.json`) для валидации готовности конкретного run.

## 2. Режимы решения

В `PromotionSignalsRequest.decisionMode` доступны:

- `auto`: автоматический verdict (`allow` или `block`).
- `semi_auto`: при «мягких» отклонениях — `review` (нужен человек), при hard-нарушениях — `block`.

Ответ содержит:

- `decision`: `allow` | `review` | `block`.
- `allowPromotion`: true только для `allow`.
- `requiresHumanReview`: true для `review`.
- `hardFailures` и `warnings` для прозрачного explainable verdict.

## 3. Сигналы и пороги

### 3.1 Hard-сигналы (блокируют promotion)

- `success_rate < minSuccessRate` (по умолчанию `0.95`).
- `p99 > maxP99Seconds` (по умолчанию `7200`).
- `factory_llm_input_tokens_total > maxInputTokensTotal` (если лимит задан).
- `factory_llm_output_tokens_total > maxOutputTokensTotal` (если лимит задан).
- нет записи run в artifact registry.
- `workflowState != DONE` при `requireDoneState=true`.
- отсутствует `DONE manifest` при `requireDoneManifest=true`.
- отсутствуют `artifactLocation`/`repoUrl` при включённых требованиях.
- `artifact_age_hours > maxArtifactAgeHours` (если лимит задан).

### 3.2 Soft-сигналы (для `semi_auto` -> `review`)

- `classified_runs < minClassifiedRuns` (по умолчанию `20`).
- `run_duration_count < minRunDurationSamples` (по умолчанию `20`).

В `auto` эти же условия считаются hard и дают `block`.

## 4. Рекомендованные базовые thresholds

- `minSuccessRate=0.95`
- `maxP99Seconds=7200`
- `minClassifiedRuns=20`
- `minRunDurationSamples=20`
- `maxArtifactAgeHours=72`
- `requireDoneState=true`
- `requireDoneManifest=true`

## 5. Пример: semi-auto gate

```bash
curl -s -X POST "http://localhost:9080/factory/promotion/<runId>/evaluate" \
  -H "Content-Type: application/json" \
  -d '{
    "decisionMode": "semi_auto",
    "minSuccessRate": 0.95,
    "maxP99Seconds": 7200,
    "minClassifiedRuns": 20,
    "minRunDurationSamples": 20,
    "maxInputTokensTotal": 500000,
    "maxOutputTokensTotal": 500000,
    "maxArtifactAgeHours": 72,
    "requireDoneState": true,
    "requireDoneManifest": true,
    "requireArtifactLocation": true,
    "requireRepoUrl": false
  }'
```

## 6. Интерпретация verdict

- `decision=allow`: promotion можно запускать автоматически.
- `decision=review`: полуавто-путь, требуется human check (Grafana + audit + registry).
- `decision=block`: promotion запрещён до устранения `hardFailures`.

## 7. Операционное применение

1. Для low-risk flow использовать `decisionMode=auto`.
2. Для medium/high-risk flow использовать `decisionMode=semi_auto`.
3. Логировать verdict в release ticket: `decision`, `hardFailures`, `warnings`, `artifactSignals`.
4. Для troubleshooting сверять `artifactSignals` с [observability-and-logs.md](observability-and-logs.md).
