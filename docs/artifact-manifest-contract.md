# Artifact Manifest Contract (v1)

Контракт `artifact_manifest.json` фиксирует минимальный набор данных для воспроизводимого артефакта:

- `manifest`: что именно было произведено и где доказательства (audit/registry).
- `manifest.version`: версия выходного артефакта manifest.
- `manifest.checksum`: детерминированный SHA-256 от канонического содержимого входов (`goal`, `constraints`, `targetStack`, `contracts`).
- `manifest.provenance`: происхождение артефакта (`gitCommit`, `timestamp`).
- `seed`: детерминирующий seed для повторного запуска.
- `inputs`: входы запуска (goal/constraints/targetStack/ссылки на контракты).

Схема: [contracts/schemas/artifact_manifest.schema.json](../contracts/schemas/artifact_manifest.schema.json)  
Пример: [contracts-example/artifact_manifest.json](../contracts-example/artifact_manifest.json)

## Поля верхнего уровня

- `apiVersion` (required): `productfactory.io/v1`
- `kind` (required): `ArtifactManifest`
- `manifest` (required): метаданные артефакта и evidence.
- `seed` (required): значение и источник seed.
- `inputs` (required): входы для воспроизведения.

## Минимальный сценарий воспроизведения

1. Взять `inputs` (`goal`, `constraints`, `targetStack`, контракты).
2. Запустить фабрику с тем же набором входов и policy.
3. Зафиксировать тот же `seed.value` (или режим `seed.source=replay`).
4. Сверить результат с `manifest.checksum` и evidence (`auditLogPath`, `artifactRegistryRecord`).
5. Проверить provenance (`manifest.provenance.gitCommit`, `manifest.provenance.timestamp`) для трассировки источника сборки.

Практически:

```bash
./scripts/replay_from_manifest.sh artifact-registry/<runId>.manifest.done.json http://localhost:9080
```

Скрипт берёт `inputs` из manifest и делает `POST /factory/run` для повторного прогона.
Если набор YAML-контрактов не менялся, `manifest.checksum` должен совпасть.

## Runtime запись manifest

- На шагах `stage` и `finish` фабрика пишет manifest в артефакт-реестр:
  - `<runId>.manifest.staged.json`
  - `<runId>.manifest.done.json`
- В audit пишется событие `artifact_manifest_written` со state и payload manifest.

## Примечания

- Контракт не меняет текущие runtime-контракты пяти YAML; это дополнительный выходной артефакт для reproducibility.
- Поле `inputs.runtime` опционально и нужно для фиксации версий/моделей исполнения.
