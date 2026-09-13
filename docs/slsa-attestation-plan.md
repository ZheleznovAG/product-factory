# SLSA Attestation Plan (Level 2)

## 1) Что такое SLSA Level 2 и что это означает для Product Factory

SLSA Level 2 для артефактов сборки требует:
- использование version control для исходников;
- использование хостируемого/доверенного build service (а не локальной «ручной» сборки);
- публикацию signed provenance (аттестации происхождения сборки), связанной с конкретным digest артефакта.

Для Product Factory это распространяется на контуры:
- образ фабрики (`product-factory`);
- образы архетипов (например, `catalog-service`, `web-app`).
- CI-артефакты проверки контрактов (например, `contract-drift-report.json`).

Минимальный практический результат для Level 2: для каждого release-образа есть проверяемая provenance-аттестация из CI build service, привязанная к commit SHA и image digest.

## 2) Текущее состояние и соответствие SLSA

В CI уже реализованы supply-chain controls:
- SBOM (CycloneDX) генерация;
- Trivy vulnerability scan (gate по severity);
- Cosign sign/verify образов.

Это закрывает важную часть целостности и уязвимостей, но само по себе не даёт полного соответствия SLSA Level 2:
- SBOM отвечает на вопрос «что внутри артефакта», но не «как и кем он был собран»;
- Trivy даёт security verdict по CVE, но не provenance build-процесса;
- Cosign подпись образа подтверждает целостность подписи, но без build provenance не фиксирует полный путь происхождения.

Итого: SLSA-покрытие достигается только при включённом provenance + verify в CI job для соответствующего артефакта.

## 3) Реализация SLSA Level 2 в CI (provenance + attestation)

Реализовано в GitHub Actions workflows:
- [.github/workflows/ci.yml](../.github/workflows/ci.yml), job `supply-chain-factory`;
- [.github/workflows/ci-archetype-catalog.yml](../.github/workflows/ci-archetype-catalog.yml), job `supply-chain-gate`;
- [.github/workflows/ci-archetype-web-app.yml](../.github/workflows/ci-archetype-web-app.yml), job `supply-chain-gate`;
- [.github/workflows/release.yml](../.github/workflows/release.yml), job `release-images`;
- [.github/workflows/contracts-validation.yml](../.github/workflows/contracts-validation.yml), job `validate-contracts` (attestation для `contract-drift-report.json` через `subject-path`).

Что добавлено:
- `permissions.attestations: write` для job, который публикует attestation;
- резолв digest после push (`IMAGE_REPO@sha256:...`) как subject для provenance;
- генерация build provenance через `actions/attest-build-provenance@v2` (`push-to-registry: true`);
- обязательный verify gate через `gh attestation verify ... --predicate-type https://slsa.dev/provenance/v1`;
- fail-closed поведение: при ошибке генерации/проверки provenance job завершается с `exit != 0`, релизный gate не проходит.

Итог: для release-образов фабрики и архетипов в CI теперь формируется и проверяется SLSA provenance attestation, привязанная к digest и commit.
Для CI-артефакта contract drift report также формируется и проверяется provenance attestation.

## 4) Оставшиеся шаги (вне Level 2 minimum)

Для дальнейшего усиления supply-chain (выше минимума Level 2):
- Build service hardening:
  - формализовать список доверенных CI workflows/builders;
  - исключить «вне CI» путь публикации release-образов;
  - зафиксировать pinning версий action/инструментов и policy обновления.
- Retention and auditability:
  - определить где и как хранятся attestations (связка с digest, срок хранения, доступность для аудита);
  - добавить ссылку на provenance в артефакт-реестр run.
- Policy and governance:
  - обновить policy-документы: какие артефакты обязательны для release;
  - зафиксировать единый release policy для factory/archetypes (SBOM + Trivy + Cosign + provenance).
1. Audit readiness: проверить воспроизводимость аудита по digest -> provenance -> commit -> workflow run.
2. Enforcement на стороне promotion/deploy: допустить прод только при успешной verify-проверке provenance.

## 5) Связанные документы

- Risk register: [docs/risk-register.md](risk-register.md)
- Supply-chain контур и угрозы: [docs/threat_model.md](threat_model.md)
- Текущее CI и операционные шаги supply-chain: [docs/runbook.md](runbook.md)
- Матрица покрытия CI-аттестаций: [docs/supply-chain/slsa-attestation-coverage.md](supply-chain/slsa-attestation-coverage.md)
- Фоновый план/roadmap по SLSA: [docs/system-audit-corrective-plan.md](system-audit-corrective-plan.md), [docs/whats-next.md](whats-next.md)
