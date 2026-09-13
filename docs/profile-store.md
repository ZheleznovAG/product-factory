# Хранилище профиля предпочтений (embedding + rules)

Реализация добавляет персистентный профиль предпочтений по `tenantId + profileId`:
- embedding-вектор (например, из калибровки/нейросервиса),
- явные правила (`LIKE`, `DISLIKE`, `REQUIRE`, `AVOID`),
- флаг `incognito` (отключение персонализации и авто-обучения),
- блок consent.

## Конфиг

- `PROFILE_STORE_ENABLED` — включает API профиля (`false` по умолчанию).
- `PROFILE_STORE_DIR` — директория file-store (`profiles` по умолчанию).
- `PROFILE_REQUIRE_CONSENT` — требовать `consent.profileStorage=true` при записи (`true` по умолчанию).
- `PROFILE_EMBEDDING_DIM` — ожидаемая длина embedding-вектора (по умолчанию `1536`).
- `PROFILE_MAX_RULES` — максимум правил в одном профиле (по умолчанию `64`).

## API

### PUT `/factory/profiles/{profileId}`

Создать/обновить профиль (увеличивается `revision`).

Пример:

```json
{
  "tenantId": "acme",
  "embedding": [0.1, 0.2, 0.3],
  "embeddingModel": "text-embedding-3-small",
  "rules": [
    {"type": "LIKE", "value": "clean-layout"},
    {"type": "AVOID", "value": "autoplay", "weight": 0.8}
  ],
  "consent": {
    "profileStorage": true,
    "cloudBackup": false,
    "source": "manual"
  }
}
```

Валидация:
- при включённом `PROFILE_REQUIRE_CONSENT` запись без `consent.profileStorage=true` отклоняется с `403 CONSENT_REQUIRED`;
- если `embedding` передан, его длина должна совпадать с `PROFILE_EMBEDDING_DIM`;
- число правил ограничено `PROFILE_MAX_RULES`.

### GET `/factory/profiles/{profileId}?tenantId=...`

Читает профиль для конкретного tenant.

### DELETE `/factory/profiles/{profileId}`

Удаляет профиль (revocation consent). Тело опционально:

```json
{
  "tenantId": "acme",
  "reason": "withdrawn"
}
```

### POST `/factory/profiles/{profileId}/reset`

Сбросить learned-данные профиля (очищает `embedding`, `embeddingModel`, `rules`, увеличивает `revision`; consent сохраняется).

```json
{
  "tenantId": "acme",
  "reason": "experiment_reset"
}
```

### POST `/factory/profiles/{profileId}/incognito`

Включить/выключить инкогнито для профиля.

```json
{
  "tenantId": "acme",
  "enabled": true,
  "reason": "privacy_session"
}
```

## Использование при ранжировании и выборе кандидата

- `POST /experience/generate` принимает `profileId` и `incognito`.
- Если передан `profileId`, profile-store включён и инкогнито выключен, варианты ранжируются с учётом `rules`.
- Если `incognito=true` (в запросе или в самом профиле), профиль в ранжировании не используется.
- При выборе кандидата на шаге `select_intent_candidate` (`POST /factory/runs/{runId}/answer`) система автоматически добавляет в профиль сигнал `LIKE` (best-effort), если нет инкогнито и разрешено consent.

## Примечания по безопасности

- Tenant isolation соблюдается: ключ профиля = `tenantId + profileId`.
- Профиль не используется для direct side-effects; это только данные для дальнейшей персонализации planner/codegen.
- События `profile_upserted` и `profile_revoked` пишутся в audit log.
