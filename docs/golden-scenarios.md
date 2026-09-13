# Golden Scenarios

Golden scenarios for key archetype flows are stored in `eval/golden/` and executed by Kotlin test `GoldenScenariosTest`.

## Scenario files

- `eval/golden/catalog-service-valid.json` - valid run for `catalog-service`.
- `eval/golden/web-app-valid.json` - valid run for `web-app`.
- `eval/golden/policy-deny.json` - policy rejection flow.
- `eval/golden/rollback.json` - rollback flow (patch failure + rollback event).

Each scenario is JSON with two top-level sections:

- `input`: run parameters and simulation knobs (`goal`, `target_stack`, policy decision, optional rollback injection flags).
- `expected`: expected run output (`status`, `state_sequence`, required/forbidden events, expected tool calls, optional artifact registry state).

## How to run

Run only golden suite:

```bash
./gradlew test --tests "productfactory.eval.GoldenScenariosTest"
```

Or run all tests:

```bash
./gradlew test
```

## What is validated

- final run status (`accepted` / `rejected`);
- message fragment (`message_contains`);
- exact workflow state sequence;
- required and forbidden audit event types;
- expected tool calls and archetype id mapping;
- final artifact registry state for successful runs.
