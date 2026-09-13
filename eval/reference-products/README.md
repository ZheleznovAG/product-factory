# Reference Products for Planner Regression

Каталог содержит эталонные кейсы для регрессионной проверки стратегий Planner.

Каждый кейс включает:

- `inputs/` — канонические 5 YAML (`product`, `constraints`, `quality_profile`, `risk_profile`, `target_stack`).
- `request.json` — параметры запуска и маппинг YAML -> `contracts` в `POST /factory/run`.
- `expected/` — эталонные planner-артефакты из audit:
  - `pipeline_plan.json`
  - `adr_draft.json`
  - `test_plan.json`
  - `planner_explanation.json`

Скрипт сравнения:

```bash
python3 eval/runners/run_planner_reference_regression.py \
  --reference-root eval/reference-products \
  --factory-url http://localhost:9080 \
  --audit-log audit.log
```

Если хотя бы один кейс не совпадает с эталоном, скрипт завершится с `exit code 1`.

