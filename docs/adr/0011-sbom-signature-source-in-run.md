# ADR 0011: Источник `sbomVersion` и `signatureVersion` в run record

- Status: accepted
- Date: 2026-02-27
- Related: ADR 0002, ADR 0013

## Контекст

Run record в artifact registry должен содержать `sbomVersion` и `signatureVersion`, но в MVP использовались только placeholder-значения при отсутствии фактических данных. Для supply-chain трассируемости нужно фиксировать детерминированный источник этих полей в самом workflow run, без нарушения границ слоёв.

## Решение

Вводится единый резолвер источника (`SupplyChainVersionResolver`) и фиксированный приоритет:

1. Явные значения из результата tool step (`ToolStepResult.sbomVersion`, `ToolStepResult.signatureVersion`).
2. Значения из CI env (`FACTORY_SBOM_VERSION` / `CI_SBOM_VERSION` / `SBOM_VERSION`; `FACTORY_SIGNATURE_VERSION` / `CI_SIGNATURE_VERSION` / `SIGNATURE_VERSION`).
3. Post-step артефакты в workspace репозитория (SBOM/signature файлы) с вычислением `sha256`.

При `DONE`:
- если источник найден, в run record пишутся реальные версии;
- если источник не найден, сохраняется контролируемый fallback (`sbom:cyclonedx:placeholder-v1`, `signature:cosign:placeholder-v1`) для backward compatibility контракта.

При состояниях до `DONE` поля остаются `null`.

## Инварианты

- При наличии фактического источника placeholder не должен записываться.
- Источник определяется детерминированно по приоритету tool -> CI env -> workspace.
- Agent не пишет SBOM/signature напрямую; запись делает workflow через Tool Executor/registry path.

## Альтернативы

### A. Только CI env как источник

- Плюсы: простая интеграция с CI.
- Минусы: не покрывает локальные/ручные прогоны и tool-level metadata.
- Отклонено: неполное покрытие run-mode сценариев.

### B. Генерировать SBOM/signature исключительно в фабрике

- Плюсы: единый пайплайн генерации.
- Минусы: увеличивает scope и ответственность runtime фабрики.
- Отклонено: для MVP и текущего контракта достаточно source resolution.

## Последствия

- Плюсы: run record отражает реальный источник supply-chain метаданных; повышается воспроизводимость и auditability.
- Минусы: fallback placeholder всё ещё возможен в окружениях без SBOM/signature артефактов.
- Смягчение: release gate (ADR 0013) блокирует релизы без обязательных SBOM/signature evidence.

## Статус реализации

Решение внедрено:

- Приоритетный резолв источников: `src/main/kotlin/productfactory/workflow/SupplyChainVersionResolver.kt`.
- Передача resolved версий в `ToolStepResult`: `src/main/kotlin/productfactory/workflow/FactoryWorkflowExecution.kt`.
- Запись в artifact registry и fallback в `DONE`: `src/main/kotlin/productfactory/workflow/ArtifactRegistry.kt`.
- Тесты резолвера и registry fallback/real values:
  - `src/test/kotlin/productfactory/workflow/SupplyChainVersionResolverTest.kt`
  - `src/test/kotlin/productfactory/workflow/ArtifactRegistryTest.kt`

## Триггеры пересмотра

- Переход на обязательный fail-closed режим без placeholder в `DONE`.
- Добавление provenance/SLSA attestation как обязательного поля run record.
- Поддержка нескольких форматов подписи кроме Cosign.
