package productfactory.rag

import java.time.Instant

data class RagIndexConfig(
    val jdbcUrl: String,
    val dbUser: String?,
    val dbPassword: String?,
    val namespace: String,
    val sourceDirectories: List<String>,
    val embeddingModel: String,
    val embeddingDim: Int,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val topK: Int,
) {
    companion object {
        fun fromEnv(): RagIndexConfig? {
            return fromEnv(System.getenv())
        }

        internal fun fromEnv(env: Map<String, String>): RagIndexConfig? {
            val jdbcUrl = env["RAG_PGVECTOR_JDBC_URL"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val dbUser = env["RAG_PGVECTOR_DB_USER"]?.trim()?.takeIf { it.isNotBlank() }
            val dbPassword = env["RAG_PGVECTOR_DB_PASSWORD"]?.trim()?.takeIf { it.isNotBlank() }
            val namespace = env["RAG_INDEX_NAMESPACE"]?.trim()?.takeIf { it.isNotBlank() } ?: "factory-main"
            val sourceDirectoriesRaw = env["RAG_INDEX_SOURCE_DIRS"]?.trim()?.takeIf { it.isNotBlank() } ?: "archetypes,docs"
            val sourceDirectories = sourceDirectoriesRaw.split(',').map { it.trim() }.filter { it.isNotBlank() }
            val embeddingModel = env["RAG_EMBEDDING_MODEL"]?.trim()?.takeIf { it.isNotBlank() } ?: "text-embedding-3-small"
            val embeddingDim = env["RAG_EMBEDDING_DIM"]?.toIntOrNull()?.coerceAtLeast(8) ?: 1536
            val chunkSize = env["RAG_CHUNK_SIZE"]?.toIntOrNull()?.coerceAtLeast(200) ?: 1200
            val chunkOverlap = env["RAG_CHUNK_OVERLAP"]?.toIntOrNull()?.coerceAtLeast(0) ?: 150
            val topK = env["RAG_PLANNER_TOP_K"]?.toIntOrNull()?.coerceAtLeast(1) ?: 6
            return RagIndexConfig(
                jdbcUrl = jdbcUrl,
                dbUser = dbUser,
                dbPassword = dbPassword,
                namespace = namespace,
                sourceDirectories = sourceDirectories,
                embeddingModel = embeddingModel,
                embeddingDim = embeddingDim,
                chunkSize = chunkSize,
                chunkOverlap = chunkOverlap,
                topK = topK,
            )
        }
    }
}

data class RagSourceDocument(
    val sourceType: String,
    val path: String,
    val content: String,
)

data class RagChunk(
    val sourceType: String,
    val sourcePath: String,
    val chunkIndex: Int,
    val content: String,
    val contentHash: String,
)

data class RagIngestionResult(
    val namespace: String,
    val versionId: String,
    val chunkCount: Int,
    val sourceCount: Int,
    val sourceFingerprint: String,
)

data class RagIndexVersion(
    val versionId: String,
    val namespace: String,
    val sourceFingerprint: String,
    val embeddingModel: String,
    val embeddingDim: Int,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val createdAt: Instant,
    val isActive: Boolean,
    val status: String,
)

object RagFlags {
    fun plannerContextEnabled(): Boolean =
        plannerContextEnabled(System.getenv())

    internal fun plannerContextEnabled(env: Map<String, String>): Boolean =
        env["RAG_PLANNER_CONTEXT_ENABLED"]?.trim()?.equals("true", ignoreCase = true) == true
}
