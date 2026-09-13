package productfactory.rag

import java.nio.file.Path

class RagIngestionService(
    private val config: RagIndexConfig,
    private val embeddingClient: RagEmbeddingClient,
    private val repository: PgVectorRagRepository = PgVectorRagRepository(config),
    private val sourceLoader: RagSourceLoader = RagSourceLoader(Path.of(".")),
) {
    fun ingestArchetypesAndDocs(): RagIngestionResult {
        val documents = sourceLoader.load(config.sourceDirectories)
        require(documents.isNotEmpty()) { "No documents found in ${config.sourceDirectories.joinToString(",")}" }

        val chunks = documents.flatMap { document ->
            RagTextProcessing.chunk(
                text = document.content,
                chunkSize = config.chunkSize,
                chunkOverlap = config.chunkOverlap,
            ).mapIndexed { index, text ->
                RagChunk(
                    sourceType = document.sourceType,
                    sourcePath = document.path,
                    chunkIndex = index,
                    content = text,
                    contentHash = RagTextProcessing.sha256Hex(text),
                )
            }
        }

        require(chunks.isNotEmpty()) { "No text chunks produced" }
        val sourceFingerprint = RagTextProcessing.sourceFingerprint(documents)
        return repository.ingest(
            chunks = chunks,
            sourceFingerprint = sourceFingerprint,
            embed = { text -> embeddingClient.embed(text) },
        )
    }
}
