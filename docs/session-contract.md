# Session Contract (Minimal, Intent Layer)

Этот документ описывает минимальный контракт `session` для Intent Layer без изменения существующих runtime-контрактов factory run (`product`, `constraints`, `quality_profile`, `risk_profile`, `target_stack`).

## Поля

- `sessionId` (string, required): уникальный идентификатор сессии.
- `createdAt` (string, required): дата/время создания в формате ISO-8601 (`date-time`, UTC рекомендуется).
- `intent` (object|string, required): либо встроенный intent-объект, либо reference на intent (например `intentRef`/ID).
- `preferences` (object, optional): пользовательские предпочтения сессии.
- `consentFlags` (object, optional): флаги согласий (например на обработку данных, сохранение памяти, аналитику).

## Минимальный пример

```yaml
sessionId: "sess-2026-02-26-001"
createdAt: "2026-02-26T12:00:00Z"
intent:
  intentRef: "intent-abc-123"
preferences:
  language: "ru"
consentFlags:
  dataProcessing: true
```
