# ADR 0014: tenantId в API и изоляция run/audit/registry

**Дата:** 2026-02-26  
**Статус:** accepted  
**Контекст:** нужен multi-tenant режим без ввода нового runtime и без нарушения layer boundaries (agent only proposals; side-effects через Tool Executor).

## Контекст

Фабрика уже имела `runId`, audit log и artifact registry, но не имела tenant-границы на API. Это создаёт риск коллизий и неявного доступа к чужим run-записям при масштабировании.

## Решение

Введён `tenantId` в API-контракты и маршрутизацию:

- Источник `tenantId` (приоритет): body `tenantId` -> `X-Tenant-Id` -> query `tenantId` -> `default`.
- Валидация `tenantId`: `^[a-z0-9][a-z0-9._-]{0,62}$`.
- Для `tenantId != default` внутренний ключ run формируется как `tenantId::runId`.
- API продолжает возвращать публичный `runId` без tenant-префикса.
- Все run-scoped операции в API (`run/retry/get`, approvals, answer, promotion, audit events для intent/experience) используют tenant-scoped ключ.

### Инварианты

- Один и тот же публичный `runId` в разных tenant-ах не пересекается в registry/audit.
- Для `tenantId=default` сохраняется backward compatibility с прежними ключами `runId`.
- Границы слоёв не меняются: side-effects по-прежнему через Tool Executor.

## Альтернативы

- Полный рефактор `AuditLog/ArtifactRegistry` c обязательным `tenantId` на всех слоях: точнее, но слишком большой скоуп для текущего изменения.
- Отдельные endpoint prefixes `/t/{tenantId}/...`: потребовал бы ломающее изменение API.

## Последствия

- **Плюсы:** tenant-изоляция registry/audit, совместимость со старыми клиентами (`default`), минимальные изменения core workflow.
- **Минусы:** tenant-scope реализован через внутренний ключ `tenantId::runId`, а не отдельные backend-хранилища per tenant.
- **Смягчение:** при переходе на dedicated storage можно сохранить API-контракт и заменить только mapping-слой.

## Статус реализации

Решение внедрено в API/CLI и покрыто тестами:

- API-резолв tenant (`body -> header -> query -> default`) и scoped/unscoped run id: `src/main/kotlin/productfactory/api/FactoryApi.kt`.
- Поддержка tenant в CLI-запросах (`X-Tenant-Id`, `--tenant`): `src/main/kotlin/productfactory/cli/FactoryConsumerCli.kt`.
- Проверка изоляции run и audit по tenant: `src/test/kotlin/productfactory/TenantIsolationApiTest.kt`.
- Профили пользователя также хранятся tenant-scoped: `src/main/kotlin/productfactory/profile/ProfileStore.kt`.

## Триггеры пересмотра

- Переход к внешнему audit backend (Loki/OTel collector с tenant labels).
- Требование строгой физической изоляции tenant-данных на уровне storage/policy.
