# RAG: ingestion and retrieval

Implemented in `productfactory.rag`:

- ingest `archetypes/` + `docs/` into pgvector;
- index versioning (`rag_index_versions`, `rag_chunks`);
- optional planner context retrieval from active index version.

CLI:

- `product-factory rag ingest`
- `product-factory rag status`
- `product-factory rag activate <versionId>`

Ops docs: `docs/rag-pgvector-operations.md`.
