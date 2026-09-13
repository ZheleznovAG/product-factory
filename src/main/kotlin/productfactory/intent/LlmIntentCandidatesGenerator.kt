package productfactory.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration

class LlmIntentCandidatesGenerator(
    private val neuralClient: NeuralServiceClient,
    private val fallbackGenerator: IntentCandidatesGenerator = IntentCandidatesGenerator(),
    private val neuralServiceUrl: String? = System.getenv("NEURAL_SERVICE_URL")?.trim()?.takeIf { it.isNotBlank() },
) : IntentCandidatesGenerator() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(intent: String): List<String> {
        val normalizedIntent = intent.trim().ifBlank { "unspecified intent" }
        if (neuralServiceUrl == null) return fallbackGenerator.generate(normalizedIntent)

        val timeoutSeconds = System.getenv("INTENT_CANDIDATES_LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 30L
        val result = try {
            neuralClient.chatCompletion(
                messages = listOf(
                    ChatMessage(role = "system", content = buildSystemPrompt()),
                    ChatMessage(role = "user", content = buildUserPrompt(normalizedIntent)),
                ),
                temperature = 0.25,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        } catch (_: Exception) {
            return fallbackGenerator.generate(normalizedIntent)
        }

        val content = result.content?.trim() ?: return fallbackGenerator.generate(normalizedIntent)
        return try {
            val parsed = parseCandidates(content)
            if (parsed.size in 3..7) parsed else fallbackGenerator.generate(normalizedIntent)
        } catch (_: Exception) {
            fallbackGenerator.generate(normalizedIntent)
        }
    }

    private fun buildSystemPrompt(): String = """
You are Product Factory Intent Candidates Generator.
Return strictly JSON only without markdown.
Allowed output formats:
1) {"candidates":["...", "...", "..."]}
2) ["...", "...", "..."]
Rules:
- Return 3 to 7 concise candidate descriptions.
- Keep each candidate unique and non-empty.
""".trimIndent()

    private fun buildUserPrompt(intent: String): String = """
intent: $intent
Return only JSON with 3-7 candidate descriptions.
""".trimIndent()

    private fun parseCandidates(raw: String): List<String> {
        val normalized = extractJsonPayload(stripMarkdownJsonFence(raw))
        val element = json.parseToJsonElement(normalized)
        val values = when {
            normalized.trim().startsWith("[") -> element.jsonArray
            else -> {
                val obj = element.jsonObject
                obj["candidates"]?.jsonArray ?: error("Missing candidates field")
            }
        }

        val candidates = values.map {
            it.jsonPrimitive.content.trim()
        }.filter { it.isNotEmpty() }
            .distinct()

        require(candidates.size in 3..7) { "Candidates count must be in range 3..7" }
        return candidates
    }

    private fun stripMarkdownJsonFence(input: String): String {
        var text = input.trim()
        for (fence in listOf("```json", "```")) {
            val idx = text.indexOf(fence)
            if (idx >= 0) {
                val afterFence = text.substring(idx + fence.length).trimStart()
                val endIdx = afterFence.indexOf("```")
                text = if (endIdx >= 0) afterFence.substring(0, endIdx).trim() else afterFence.trim()
                break
            }
        }
        return text
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val start = trimmed.indexOf('[')
            val end = trimmed.lastIndexOf(']')
            require(start >= 0 && end > start) { "No JSON array in response" }
            return trimmed.substring(start, end + 1)
        }
        val objectStart = trimmed.indexOf('{')
        val objectEnd = trimmed.lastIndexOf('}')
        require(objectStart >= 0 && objectEnd > objectStart) { "No JSON object in response" }
        return trimmed.substring(objectStart, objectEnd + 1)
    }
}
