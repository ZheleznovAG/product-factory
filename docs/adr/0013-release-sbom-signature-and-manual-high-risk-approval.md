# ADR 0013: Release gate with SBOM/signature and manual high-risk approval

**Дата:** 2026-02-26  
**Статус:** accepted  
**Контекст:** требуется гарантировать, что каждый release публикуется только с SBOM и проверяемой подписью, а high-risk действия идут через явный ручной approval.

## Контекст

В CI уже есть supply-chain проверки для main/push, но не было выделенного release workflow с обязательным ручным подтверждением перед публикацией релизных образов. Для solo-режима и high-risk операций нужно фиксировать человек-в-контуре до publish.

## Решение

- Добавить release workflow `.github/workflows/release.yml` (теги `v*` и `workflow_dispatch`).
- Ввести обязательный job `high-risk-manual-approval` через GitHub Environment `high-risk-release`.
- Публиковать release-образы только после manual approval.
- Для каждого релизного образа (`product-factory`, `catalog-service`, `web-app`) сделать обязательными:
  - SBOM (Syft, CycloneDX);
  - Cosign keyless sign + verify;
  - SLSA provenance attestation + verify.
- В runtime approval для high-risk tools фиксировать явно (`risk_tier:high` в approval record).

### Инварианты

- Release без SBOM и подписи считается невалидным.
- Release без ручного подтверждения high-risk gate не допускается.
- Side-effects остаются в границах Tool Executor и policy (ADR 0002).

## Альтернативы

- Оставить только общий CI без отдельного release gate: отклонено, нет явного manual approval перед релизной публикацией.
- Делать approval вне GitHub Actions (вручную по runbook): отклонено, нет детерминированной блокировки в pipeline.

## Последствия

- **Плюсы:** прозрачный и воспроизводимый release-контур; явный human-in-the-loop для high-risk; трассируемые SBOM/signature/provenance.
- **Минусы:** релиз дольше из-за ручного шага и дополнительных проверок.
- **Смягчение:** manual gate только в release workflow; обычный CI остаётся быстрым.

## Триггеры пересмотра

- Переход на другой registry или модель подписания.
- Изменение требований комплаенса (например, иной формат attestation/SBOM).
- Появление multi-operator модели с иными правилами approval.
