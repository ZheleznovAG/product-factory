# SLSA Attestation Coverage in CI

Этот документ фиксирует фактическое покрытие SLSA/provenance в CI Product Factory и закрывает gap «где еще не покрыто».

## Цель

- Единая матрица по CI workflow с аттестациями.
- Явный статус: что уже покрыто provenance, что является release gate.
- Минимум SLSA Level 2: provenance генерируется и проверяется в доверенном CI.

## Матрица покрытия

| Workflow | Job | Subject | Provenance | Verify | Статус |
|---|---|---|---|---|---|
| `.github/workflows/ci.yml` | `supply-chain-factory` | OCI image digest `ghcr.io/<owner>/<repo>@sha256:...` | `actions/attest-build-provenance@v2` (`subject-name` + `subject-digest`) | `gh attestation verify ... --predicate-type https://slsa.dev/provenance/v1` | Covered |
| `.github/workflows/release.yml` | `release-images` | OCI image digest для `product-factory`, `catalog-service`, `web-app` | `actions/attest-build-provenance@v2` (`push-to-registry: true`) | `gh attestation verify ... --predicate-type https://slsa.dev/provenance/v1` | Covered |
| `.github/workflows/ci-archetype-catalog.yml` | `supply-chain-gate` | OCI image digest `catalog-service` | `actions/attest-build-provenance@v2` | `gh attestation verify ... --predicate-type https://slsa.dev/provenance/v1` | Covered |
| `.github/workflows/ci-archetype-web-app.yml` | `supply-chain-gate` | OCI image digest `web-app` | `actions/attest-build-provenance@v2` | `gh attestation verify ... --predicate-type https://slsa.dev/provenance/v1` | Covered |
| `.github/workflows/contracts-validation.yml` | `validate-contracts` | `reports/contract-drift-report.json` | `actions/attest-build-provenance@v2` (`subject-path`) | `gh attestation verify reports/contract-drift-report.json --repo <repo>` | Covered |

## Что добавлено сейчас

Ранее `contracts-validation` публиковал артефакт `contract-drift-report.json` без provenance.

Добавлено:
- `permissions`: `attestations: write`, `id-token: write`, `contents: read`;
- генерация provenance для `reports/contract-drift-report.json`;
- verify шаг (fail-closed): job падает при невалидной/отсутствующей attestation.

## Политика fail-closed

Для job с аттестацией правило единое:
- если `attest-build-provenance` или `gh attestation verify` завершились ошибкой, workflow считается failed;
- downstream шаги release/promotion не должны обходить этот результат.

## Связанные документы

- [SLSA Attestation Plan](../slsa-attestation-plan.md)
- [Runbook: Supply-chain gate](../runbook.md)
- [Approval policy](../approval-policy.md)
