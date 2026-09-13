package productfactory.rag

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.random.Random

class PgVectorRagRepository(private val config: RagIndexConfig) {

    fun ingest(
        chunks: List<RagChunk>,
        sourceFingerprint: String,
        embed: (String) -> FloatArray?,
    ): RagIngestionResult {
        require(chunks.isNotEmpty()) { "No chunks to ingest" }
        connection().use { connection ->
            connection.autoCommit = false
            ensureSchema(connection)
            val versionId = createVersionId(sourceFingerprint)
            upsertVersion(connection, versionId, sourceFingerprint)
            insertChunks(connection, versionId, chunks, embed)
            promoteVersion(connection, versionId)
            connection.commit()
            return RagIngestionResult(
                namespace = config.namespace,
                versionId = versionId,
                chunkCount = chunks.size,
                sourceCount = chunks.map { it.sourcePath }.distinct().size,
                sourceFingerprint = sourceFingerprint,
            )
        }
    }

    fun listVersions(limit: Int = 20): List<RagIndexVersion> {
        connection().use { connection ->
            ensureSchema(connection)
            connection.prepareStatement(
                """
                SELECT version_id, namespace, source_fingerprint, embedding_model, embedding_dim, chunk_size, chunk_overlap,
                       created_at, is_active, status
                FROM rag_index_versions
                WHERE namespace = ?
                ORDER BY created_at DESC
                LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, config.namespace)
                st.setInt(2, limit)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<RagIndexVersion>()
                    while (rs.next()) {
                        out += RagIndexVersion(
                            versionId = rs.getString("version_id"),
                            namespace = rs.getString("namespace"),
                            sourceFingerprint = rs.getString("source_fingerprint"),
                            embeddingModel = rs.getString("embedding_model"),
                            embeddingDim = rs.getInt("embedding_dim"),
                            chunkSize = rs.getInt("chunk_size"),
                            chunkOverlap = rs.getInt("chunk_overlap"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            isActive = rs.getBoolean("is_active"),
                            status = rs.getString("status"),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun activate(versionId: String): Boolean {
        connection().use { connection ->
            connection.autoCommit = false
            ensureSchema(connection)
            val exists = connection.prepareStatement(
                "SELECT 1 FROM rag_index_versions WHERE namespace = ? AND version_id = ?",
            ).use { st ->
                st.setString(1, config.namespace)
                st.setString(2, versionId)
                st.executeQuery().next()
            }
            if (!exists) {
                connection.rollback()
                return false
            }
            connection.prepareStatement("UPDATE rag_index_versions SET is_active = false WHERE namespace = ?").use { st ->
                st.setString(1, config.namespace)
                st.executeUpdate()
            }
            connection.prepareStatement(
                "UPDATE rag_index_versions SET is_active = true, status = 'active' WHERE namespace = ? AND version_id = ?",
            ).use { st ->
                st.setString(1, config.namespace)
                st.setString(2, versionId)
                st.executeUpdate()
            }
            connection.commit()
            return true
        }
    }

    fun retrieve(queryEmbedding: FloatArray, limit: Int = config.topK): List<Pair<String, String>> {
        connection().use { connection ->
            ensureSchema(connection)
            val vector = RagTextProcessing.vectorLiteral(queryEmbedding)
            connection.prepareStatement(
                """
                SELECT c.source_path, c.content
                FROM rag_chunks c
                JOIN rag_index_versions v ON v.version_id = c.version_id AND v.namespace = c.namespace
                WHERE c.namespace = ?
                  AND v.is_active = true
                ORDER BY c.embedding <-> ?::vector
                LIMIT ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, config.namespace)
                st.setString(2, vector)
                st.setInt(3, limit)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<Pair<String, String>>()
                    while (rs.next()) {
                        out += rs.getString("source_path") to rs.getString("content")
                    }
                    return out
                }
            }
        }
    }

    private fun insertChunks(
        connection: Connection,
        versionId: String,
        chunks: List<RagChunk>,
        embed: (String) -> FloatArray?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO rag_chunks(
                namespace, version_id, source_type, source_path, chunk_index,
                content, content_hash, embedding, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::vector, ?::jsonb)
            """.trimIndent(),
        ).use { st ->
            chunks.forEach { chunk ->
                val embedding = embed(chunk.content)
                if (embedding == null || embedding.size != config.embeddingDim) {
                    throw IllegalStateException("Embedding is missing or invalid dim for ${chunk.sourcePath}#${chunk.chunkIndex}")
                }
                st.setString(1, config.namespace)
                st.setString(2, versionId)
                st.setString(3, chunk.sourceType)
                st.setString(4, chunk.sourcePath)
                st.setInt(5, chunk.chunkIndex)
                st.setString(6, chunk.content)
                st.setString(7, chunk.contentHash)
                st.setString(8, RagTextProcessing.vectorLiteral(embedding))
                st.setString(9, "{}")
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private fun upsertVersion(connection: Connection, versionId: String, sourceFingerprint: String) {
        connection.prepareStatement(
            """
            INSERT INTO rag_index_versions(
                version_id, namespace, source_fingerprint, embedding_model, embedding_dim,
                chunk_size, chunk_overlap, status, is_active
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'building', false)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, versionId)
            st.setString(2, config.namespace)
            st.setString(3, sourceFingerprint)
            st.setString(4, config.embeddingModel)
            st.setInt(5, config.embeddingDim)
            st.setInt(6, config.chunkSize)
            st.setInt(7, config.chunkOverlap)
            st.executeUpdate()
        }
    }

    private fun promoteVersion(connection: Connection, versionId: String) {
        connection.prepareStatement("UPDATE rag_index_versions SET is_active = false WHERE namespace = ?").use { st ->
            st.setString(1, config.namespace)
            st.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE rag_index_versions SET is_active = true, status = 'active' WHERE namespace = ? AND version_id = ?",
        ).use { st ->
            st.setString(1, config.namespace)
            st.setString(2, versionId)
            st.executeUpdate()
        }
    }

    private fun ensureSchema(connection: Connection) {
        connection.createStatement().use { st ->
            st.execute("CREATE EXTENSION IF NOT EXISTS vector")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS rag_index_versions (
                    version_id TEXT PRIMARY KEY,
                    namespace TEXT NOT NULL,
                    source_fingerprint TEXT NOT NULL,
                    embedding_model TEXT NOT NULL,
                    embedding_dim INT NOT NULL,
                    chunk_size INT NOT NULL,
                    chunk_overlap INT NOT NULL,
                    status TEXT NOT NULL,
                    is_active BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """.trimIndent(),
            )
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS rag_chunks (
                    id BIGSERIAL PRIMARY KEY,
                    namespace TEXT NOT NULL,
                    version_id TEXT NOT NULL REFERENCES rag_index_versions(version_id) ON DELETE CASCADE,
                    source_type TEXT NOT NULL,
                    source_path TEXT NOT NULL,
                    chunk_index INT NOT NULL,
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    embedding vector(${config.embeddingDim}) NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    UNIQUE(namespace, version_id, source_path, chunk_index)
                )
                """.trimIndent(),
            )
            st.execute("CREATE INDEX IF NOT EXISTS idx_rag_versions_namespace_active ON rag_index_versions(namespace, is_active)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunks_namespace_version ON rag_chunks(namespace, version_id)")
        }
    }

    private fun connection(): Connection {
        return if (config.dbUser != null) {
            DriverManager.getConnection(config.jdbcUrl, config.dbUser, config.dbPassword)
        } else {
            DriverManager.getConnection(config.jdbcUrl)
        }
    }

    private fun createVersionId(sourceFingerprint: String): String {
        val ts = Instant.now().toString().replace(Regex("[-:.TZ]"), "").take(17)
        val suffix = sourceFingerprint.take(10)
        val entropy = Random.nextInt(0, 36 * 36).toString(36).padStart(2, '0')
        return "idx-${ts}-${suffix}${entropy}"
    }
}
