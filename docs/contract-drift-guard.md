# Contract Drift Guard

Contract Drift Guard проверяет совместимость входных YAML-контрактов с эталонными схемами `contracts/schemas/*.schema.json` и формирует отчёт по несовместимостям.

## Что проверяется

- Наличие обязательных файлов:
  - `product.yaml`
  - `constraints.yaml`
  - `quality_profile.yaml`
  - `risk_profile.yaml`
  - `target_stack.yaml`
- Валидность YAML.
- Соответствие JSON Schema для каждого контракта.

Типы несовместимостей в отчёте:

- `MISSING_FILE` — отсутствует обязательный YAML.
- `INVALID_YAML` — файл не парсится как YAML.
- `EMPTY_DOCUMENT` — YAML пустой.
- `SCHEMA_VIOLATION` — структура или значения не соответствуют schema.

## CLI

```bash
product-factory contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>]
product-factory pf contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>]
```

Примеры:

```bash
# Текстовый отчёт в stdout, exit code 0/1
product-factory contract-drift-guard ./contracts-example

# JSON-отчёт в файл (удобно для CI артефактов)
product-factory contract-drift-guard ./contracts-example --format json --report ./reports/contract-drift-report.json
```

Коды возврата:

- `0` — drift не обнаружен (все контракты совместимы).
- `1` — найдены несовместимости.
- `2` — ошибка аргументов CLI.

## CI

Workflow [contracts-validation.yml](../.github/workflows/contracts-validation.yml) запускает Contract Drift Guard в Docker и публикует JSON-отчёт как artifact `contract-drift-report`.

Ключевой шаг:

```bash
contract-drift-guard "$CONTRACTS_DIR" --format json --report /w/reports/contract-drift-report.json
```

Такой отчёт можно использовать для:

- диагностики несовместимых изменений схем/примеров,
- контроля миграций `apiVersion` и обязательных полей,
- автоматического quality gate в PR.
