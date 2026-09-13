#!/usr/bin/env bash
# E2E: intent estimate -> experience candidates -> candidate selection -> POST /factory/run -> verify state.
set -euo pipefail

FACTORY_URL="${FACTORY_URL:-http://localhost:9080}"
TENANT_ID="${TENANT_ID:-default}"
USER_QUERY="${USER_QUERY:-Нужен onboarding для B2B SaaS, чтобы сократить time-to-first-value}"
RISK_TIER="${RISK_TIER:-medium}"
MAX_CLARIFYING_QUESTIONS="${MAX_CLARIFYING_QUESTIONS:-2}"
VARIANTS="${VARIANTS:-3}"
CANDIDATE_INDEX="${CANDIDATE_INDEX:-1}" # 1-based
TARGET_STACK="${TARGET_STACK:-web-app}"
FACTORY_RUN_TIMEOUT="${FACTORY_RUN_TIMEOUT:-180}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-5}"

if ! command -v curl >/dev/null 2>&1; then
  echo "Ошибка: curl не найден."
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "Ошибка: jq не найден. Установите jq для запуска scripts/run_intent_to_run.sh."
  exit 1
fi

if ! [[ "$VARIANTS" =~ ^[0-9]+$ ]] || [ "$VARIANTS" -lt 3 ] || [ "$VARIANTS" -gt 7 ]; then
  echo "Ошибка: VARIANTS должен быть целым в диапазоне 3..7."
  exit 1
fi
if ! [[ "$CANDIDATE_INDEX" =~ ^[0-9]+$ ]] || [ "$CANDIDATE_INDEX" -lt 1 ]; then
  echo "Ошибка: CANDIDATE_INDEX должен быть целым >= 1."
  exit 1
fi

echo "=== Intent -> Candidate -> Factory Run @ $FACTORY_URL ==="
if ! curl -sf "$FACTORY_URL/health" >/dev/null; then
  echo "Фабрика недоступна: $FACTORY_URL/health"
  exit 1
fi

ts="$(date +%s)"
REQUEST_ID_INTENT="req-intent-e2e-$ts"
REQUEST_ID_EXP="req-exp-e2e-$ts"
SESSION_ID="sess-e2e-$ts"

intent_payload="$(jq -n \
  --arg apiVersion "productfactory.io/v1" \
  --arg tenantId "$TENANT_ID" \
  --arg requestId "$REQUEST_ID_INTENT" \
  --arg sessionId "$SESSION_ID" \
  --arg query "$USER_QUERY" \
  --arg riskTier "$RISK_TIER" \
  --argjson maxClarifyingQuestions "$MAX_CLARIFYING_QUESTIONS" \
  '{
    apiVersion: $apiVersion,
    tenantId: $tenantId,
    requestId: $requestId,
    session: { sessionId: $sessionId },
    input: { query: $query, language: "ru" },
    constraints: {
      riskTier: $riskTier,
      maxClarifyingQuestions: $maxClarifyingQuestions
    }
  }')"

intent_response="$(curl -sS -w "\n%{http_code}" -X POST "$FACTORY_URL/intent/estimate" \
  -H "Content-Type: application/json" \
  -d "$intent_payload")"
intent_code="$(echo "$intent_response" | tail -n1)"
intent_body="$(echo "$intent_response" | sed '$d')"
if [ "$intent_code" != "200" ]; then
  echo "POST /intent/estimate вернул HTTP $intent_code"
  echo "$intent_body"
  exit 1
fi

outcome="$(echo "$intent_body" | jq -r '.intent.outcome // empty')"
experience="$(echo "$intent_body" | jq -r '.intent.experience // empty')"
constraints_json="$(echo "$intent_body" | jq -c '.intent.constraints // []')"
reference_ids_json="$(echo "$intent_body" | jq -c '.intent.reference_ids // []')"
clarifying_count="$(echo "$intent_body" | jq '.clarifyingQuestions | length')"

if [ -z "$outcome" ] || [ -z "$experience" ]; then
  echo "В ответе /intent/estimate отсутствует outcome или experience:"
  echo "$intent_body"
  exit 1
fi

echo "intent: outcome получен, clarifyingQuestions=$clarifying_count"

experience_payload="$(jq -n \
  --arg apiVersion "productfactory.io/v1" \
  --arg tenantId "$TENANT_ID" \
  --arg requestId "$REQUEST_ID_EXP" \
  --arg sessionId "$SESSION_ID" \
  --arg outcome "$outcome" \
  --arg experience "$experience" \
  --argjson constraints "$constraints_json" \
  --argjson reference_ids "$reference_ids_json" \
  --argjson variants "$VARIANTS" \
  '{
    apiVersion: $apiVersion,
    tenantId: $tenantId,
    requestId: $requestId,
    session: { sessionId: $sessionId },
    intent: {
      outcome: $outcome,
      experience: $experience,
      constraints: $constraints,
      reference_ids: $reference_ids
    },
    generation: { variants: $variants, includeRationale: true }
  }')"

experience_response="$(curl -sS -w "\n%{http_code}" -X POST "$FACTORY_URL/experience/generate" \
  -H "Content-Type: application/json" \
  -d "$experience_payload")"
experience_code="$(echo "$experience_response" | tail -n1)"
experience_body="$(echo "$experience_response" | sed '$d')"
if [ "$experience_code" != "200" ]; then
  echo "POST /experience/generate вернул HTTP $experience_code"
  echo "$experience_body"
  exit 1
fi

variants_count="$(echo "$experience_body" | jq '.variants | length')"
if [ "$variants_count" -lt "$CANDIDATE_INDEX" ]; then
  echo "Недостаточно вариантов: variants=$variants_count, CANDIDATE_INDEX=$CANDIDATE_INDEX"
  echo "$experience_body"
  exit 1
fi

candidate_idx=$((CANDIDATE_INDEX - 1))
candidate_id="$(echo "$experience_body" | jq -r ".variants[$candidate_idx].id // empty")"
candidate_title="$(echo "$experience_body" | jq -r ".variants[$candidate_idx].title // empty")"
candidate_summary="$(echo "$experience_body" | jq -r ".variants[$candidate_idx].summary // empty")"
if [ -z "$candidate_summary" ]; then
  echo "Не удалось извлечь summary выбранного кандидата."
  echo "$experience_body"
  exit 1
fi
echo "selected candidate: index=$CANDIDATE_INDEX id=$candidate_id title=$candidate_title"

run_goal="Реализовать выбранный candidate $candidate_id: $candidate_title"
run_constraints_json="$(echo "$constraints_json" | jq --arg selected "selected_experience:$candidate_summary" '. + [$selected]')"

run_payload="$(jq -n \
  --arg tenantId "$TENANT_ID" \
  --arg goal "$run_goal" \
  --arg targetStack "$TARGET_STACK" \
  --argjson constraints "$run_constraints_json" \
  '{
    tenantId: $tenantId,
    goal: $goal,
    constraints: $constraints,
    target_stack: $targetStack
  }')"

run_response="$(curl -sS -w "\n%{http_code}" -X POST "$FACTORY_URL/factory/run" \
  -H "Content-Type: application/json" \
  -d "$run_payload")"
run_code="$(echo "$run_response" | tail -n1)"
run_body="$(echo "$run_response" | sed '$d')"
if [ "$run_code" != "200" ] && [ "$run_code" != "202" ]; then
  echo "POST /factory/run вернул HTTP $run_code"
  echo "$run_body"
  exit 1
fi

run_id="$(echo "$run_body" | jq -r '.runId // empty')"
run_status="$(echo "$run_body" | jq -r '.status // empty')"
if [ -z "$run_id" ]; then
  echo "В ответе /factory/run отсутствует runId:"
  echo "$run_body"
  exit 1
fi
echo "runId=$run_id status=$run_status"

deadline=$(( $(date +%s) + FACTORY_RUN_TIMEOUT ))
while true; do
  run_info_response="$(curl -sS -w "\n%{http_code}" "$FACTORY_URL/factory/runs/$run_id?tenantId=$TENANT_ID" || true)"
  run_info_code="$(echo "$run_info_response" | tail -n1)"
  run_info_body="$(echo "$run_info_response" | sed '$d')"

  if [ "$run_info_code" = "200" ]; then
    workflow_state="$(echo "$run_info_body" | jq -r '.workflowState // empty')"
    echo "workflowState=${workflow_state:-<empty>}"
    if [ "$workflow_state" = "STAGED" ] || [ "$workflow_state" = "DONE" ]; then
      echo "=== Intent e2e OK: run reached $workflow_state ==="
      exit 0
    fi
    if [ "$workflow_state" = "FAILED" ]; then
      echo "Run завершился FAILED:"
      echo "$run_info_body"
      exit 1
    fi
  fi

  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "Таймаут: run $run_id не достиг STAGED/DONE за ${FACTORY_RUN_TIMEOUT} сек."
    [ -n "${run_info_body:-}" ] && echo "$run_info_body"
    exit 1
  fi

  sleep "$POLL_INTERVAL_SECONDS"
done

