# RAG + pgvector: ingestion и версии индекса

Реализация закрывает задачу фазы 4: загрузка `archetypes/` и `docs/` в `pgvector` с явным versioning индекса.

## Что реализовано

- CLI-команды:
  - `product-factory rag ingest`
  - `product-factory rag status`
  - `product-factory rag activate <versionId>`
- Хранение версий индекса в таблице `rag_index_versions`.
- Хранение чанков и эмбеддингов в `rag_chunks` (`vector(N)` из `pgvector`).
- Атомарное переключение active-версии при успешном ingestion.
- Опциональная точка в планировщике: добавление RAG-контекста в prompt (`LlmAgentPlanner`) при включённом флаге.

## Переменные окружения

Обязательные для RAG-хранилища:

- `RAG_PGVECTOR_JDBC_URL` (например `jdbc:postgresql://localhost:5432/product_factory`)

Опциональные:

- `RAG_PGVECTOR_DB_USER`
- `RAG_PGVECTOR_DB_PASSWORD`
- `RAG_INDEX_NAMESPACE` (по умолчанию `factory-main`)
- `RAG_INDEX_SOURCE_DIRS` (по умолчанию `archetypes,docs`; можно указать только `archetypes` или только `docs`)
- `RAG_EMBEDDING_MODEL` (по умолчанию `text-embedding-3-small`)
- `RAG_EMBEDDING_DIM` (по умолчанию `1536`)
- `RAG_CHUNK_SIZE` (по умолчанию `1200`)
- `RAG_CHUNK_OVERLAP` (по умолчанию `150`)

Для `ingest` обязательны также:

- `NEURAL_SERVICE_URL` (OpenAI-совместимый endpoint)
- `NEURAL_SERVICE_API_KEY` (если endpoint требует ключ)

Для опционального использования в планировщике:

- `RAG_PLANNER_CONTEXT_ENABLED=true`
- `RAG_PLANNER_TOP_K` (по умолчанию `6`)

`RAG_PLANNER_CONTEXT_ENABLED` по умолчанию `false` и это зафиксировано в [decision-points-v1.md](decision-points-v1.md) (раздел 3).

## Операции

Ингест архетипов и документации:

```bash
product-factory rag ingest
```

Просмотр версий в namespace:

```bash
product-factory rag status
```

Ручной rollback/promote на нужную версию:

```bash
product-factory rag activate idx-20260226153000-abcdef1234
```

## Как это влияет на планировщик

По умолчанию не влияет.

Если включить `RAG_PLANNER_CONTEXT_ENABLED=true` и задан доступ к pgvector, `LlmAgentPlanner` перед генерацией плана получает top-k релевантных чанков из active-версии индекса и добавляет их в user prompt как дополнительный контекст. При ошибках RAG-контур тихо деградирует (planner продолжает работать без контекста).

## Безопасность и границы

- Side-effects ограничены CLI-операцией ingestion в БД индекса.
- В runtime-планировщике используется только read-path к индексу.
- Поведение без RAG полностью совместимо с предыдущей версией.
