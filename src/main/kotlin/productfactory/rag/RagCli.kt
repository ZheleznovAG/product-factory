package productfactory.rag

object RagCli {
    private val usage = """
Usage:
  product-factory rag ingest
  product-factory rag status
  product-factory rag activate <versionId>

Required env for ingest/status/activate:
  RAG_PGVECTOR_JDBC_URL=jdbc:postgresql://<host>:5432/<db>
Optional env:
  RAG_PGVECTOR_DB_USER, RAG_PGVECTOR_DB_PASSWORD, RAG_INDEX_NAMESPACE,
  RAG_INDEX_SOURCE_DIRS, RAG_EMBEDDING_MODEL, RAG_EMBEDDING_DIM,
  RAG_CHUNK_SIZE, RAG_CHUNK_OVERLAP
For ingest also require:
  NEURAL_SERVICE_URL (+ optional NEURAL_SERVICE_API_KEY)
""".trimIndent()

    fun run(args: Array<String>): Int {
        val command = args.getOrNull(2)
        if (command == null) {
            System.err.println(usage)
            return 2
        }
        return when (command) {
            "ingest" -> ingest()
            "status" -> status()
            "activate" -> activate(args.getOrNull(3))
            else -> {
                System.err.println(usage)
                2
            }
        }
    }

    private fun ingest(): Int {
        val config = RagIndexConfig.fromEnv()
        if (config == null) {
            System.err.println("RAG_PGVECTOR_JDBC_URL is required")
            return 2
        }
        val baseUrl = System.getenv("NEURAL_SERVICE_URL")?.trim()?.takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            System.err.println("NEURAL_SERVICE_URL is required for embedding ingestion")
            return 2
        }
        val apiKey = System.getenv("NEURAL_SERVICE_API_KEY")?.trim()?.takeIf { it.isNotBlank() }
        val embeddingClient = NeuralRagEmbeddingClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = config.embeddingModel,
            expectedDim = config.embeddingDim,
        )
        return try {
            val result = RagIngestionService(config, embeddingClient).ingestArchetypesAndDocs()
            println(
                "RAG ingestion completed: namespace=${result.namespace} version=${result.versionId} " +
                    "sources=${result.sourceCount} chunks=${result.chunkCount} fingerprint=${result.sourceFingerprint}",
            )
            0
        } catch (e: Exception) {
            System.err.println("RAG ingestion failed: ${e.message}")
            1
        }
    }

    private fun status(): Int {
        val config = RagIndexConfig.fromEnv()
        if (config == null) {
            System.err.println("RAG_PGVECTOR_JDBC_URL is required")
            return 2
        }
        return try {
            val versions = PgVectorRagRepository(config).listVersions(limit = 20)
            if (versions.isEmpty()) {
                println("No RAG index versions in namespace=${config.namespace}")
                0
            } else {
                versions.forEach { version ->
                    println(
                        "version=${version.versionId} status=${version.status} active=${version.isActive} " +
                            "created_at=${version.createdAt} fingerprint=${version.sourceFingerprint.take(12)}",
                    )
                }
                0
            }
        } catch (e: Exception) {
            System.err.println("RAG status failed: ${e.message}")
            1
        }
    }

    private fun activate(versionId: String?): Int {
        if (versionId.isNullOrBlank()) {
            System.err.println(usage)
            return 2
        }
        val config = RagIndexConfig.fromEnv()
        if (config == null) {
            System.err.println("RAG_PGVECTOR_JDBC_URL is required")
            return 2
        }
        return try {
            val ok = PgVectorRagRepository(config).activate(versionId)
            if (ok) {
                println("Activated RAG index version=$versionId namespace=${config.namespace}")
                0
            } else {
                System.err.println("Version not found: $versionId")
                1
            }
        } catch (e: Exception) {
            System.err.println("RAG activate failed: ${e.message}")
            1
        }
    }
}
