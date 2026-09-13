package productfactory.rag

import productfactory.agent.AgentPlannerInput

interface PlannerContextProvider {
    fun contextFor(input: AgentPlannerInput): String?
}

class PgVectorPlannerContextProvider(
    private val config: RagIndexConfig,
    private val embeddingClient: RagEmbeddingClient,
    private val repository: PgVectorRagRepository = PgVectorRagRepository(config),
) : PlannerContextProvider {

    override fun contextFor(input: AgentPlannerInput): String? {
        val query = buildString {
            append(input.goal)
            if (input.constraints.isNotEmpty()) {
                append("\nConstraints: ")
                append(input.constraints.joinToString("; "))
            }
            input.productSpec?.takeIf { it.isNotBlank() }?.let {
                append("\nProductSpec: ")
                append(it)
            }
        }.trim()
        if (query.isBlank()) return null

        val embedding = embeddingClient.embed(query) ?: return null
        val hits = repository.retrieve(embedding, config.topK)
        if (hits.isEmpty()) return null

        val lines = hits.mapIndexed { index, (sourcePath, content) ->
            val compact = content.replace(Regex("\\s+"), " ").trim().take(260)
            "${index + 1}. [$sourcePath] $compact"
        }
        return lines.joinToString(separator = "\n")
    }
}
