package productfactory.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration

class LlmIntentGenerator(
    private val neuralClient: NeuralServiceClient,
    private val fallbackGenerator: IntentGenerator = IntentGenerator(),
    private val neuralServiceUrl: String? = System.getenv("NEURAL_SERVICE_URL")?.trim()?.takeIf { it.isNotBlank() },
) : IntentGenerator() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(goal: String, constraints: List<String>): IntentDraft {
        val normalizedGoal = goal.trim().ifBlank { "unspecified goal" }
        val normalizedConstraints = constraints.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (neuralServiceUrl == null) {
            return fallbackGenerator.generate(normalizedGoal, normalizedConstraints)
        }

        val timeoutSeconds = System.getenv("INTENT_GENERATOR_LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 30L
        val result = try {
            neuralClient.chatCompletion(
                messages = listOf(
                    ChatMessage(role = "system", content = buildSystemPrompt()),
                    ChatMessage(role = "user", content = buildUserPrompt(normalizedGoal, normalizedConstraints)),
                ),
                temperature = 0.2,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        } catch (_: Exception) {
            return fallbackGenerator.generate(normalizedGoal, normalizedConstraints)
        }

        val content = result.content?.trim() ?: return fallbackGenerator.generate(normalizedGoal, normalizedConstraints)
        return try {
            parseIntent(content)
        } catch (_: Exception) {
            fallbackGenerator.generate(normalizedGoal, normalizedConstraints)
        }
    }

    private fun buildSystemPrompt(): String = """
You are Product Factory Intent Generator.
Return strictly JSON only without markdown.
Schema:
{
  "apiVersion":"productfactory.io/v1",
  "kind":"IntentSpec",
  "outcome":{"goal":"string","result":"string","successSignals":["string"]},
  "experience":{"feeling":"string","interactionStyle":"string","tempo":"slow|balanced|fast","intensity":"low|medium|high"},
  "constraints":{"mustHave":["string"],"mustNot":["string"],"maxSessionMinutes":90,"budgetUsdMax":null},
  "confidence":0.0,
  "referenceIds":[]
}
Rules:
- Keep mustHave practical and non-empty.
- Keep confidence in range [0,1].
- Do not include extra keys.
""".trimIndent()

    private fun buildUserPrompt(goal: String, constraints: List<String>): String {
        val constraintsBlock = if (constraints.isEmpty()) "[]" else constraints.joinToString(prefix = "[", postfix = "]")
        return """
goal: $goal
constraints: $constraintsBlock
Return only JSON object compatible with IntentSpec.
""".trimIndent()
    }

    private fun parseIntent(raw: String): IntentDraft {
        val jsonText = extractJsonValue(stripMarkdownJsonFence(raw))
        val root = json.parseToJsonElement(jsonText).jsonObject
        val apiVersion = root["apiVersion"].asString("apiVersion")
        val kind = root["kind"].asString("kind")
        require(apiVersion == "productfactory.io/v1") { "Invalid apiVersion" }
        require(kind == "IntentSpec") { "Invalid kind" }

        val outcomeObj = root["outcome"].asObject("outcome")
        val experienceObj = root["experience"].asObject("experience")
        val constraintsObj = root["constraints"].asObject("constraints")

        val outcome = IntentOutcome(
            goal = outcomeObj["goal"].asString("outcome.goal"),
            result = outcomeObj["result"].asString("outcome.result"),
            successSignals = outcomeObj["successSignals"].asStringList("outcome.successSignals"),
        )

        val tempo = experienceObj["tempo"]?.jsonPrimitive?.content?.trim()?.takeUnless { it.isEmpty() }
        val intensity = experienceObj["intensity"]?.jsonPrimitive?.content?.trim()?.takeUnless { it.isEmpty() }
        if (tempo != null) require(tempo in setOf("slow", "balanced", "fast")) { "Invalid experience.tempo" }
        if (intensity != null) require(intensity in setOf("low", "medium", "high")) { "Invalid experience.intensity" }
        val experience = IntentExperience(
            feeling = experienceObj["feeling"].asString("experience.feeling"),
            interactionStyle = experienceObj["interactionStyle"].asString("experience.interactionStyle"),
            tempo = tempo ?: "balanced",
            intensity = intensity ?: "medium",
        )

        val maxSessionMinutes = constraintsObj["maxSessionMinutes"]?.jsonPrimitive?.intOrNull
        if (maxSessionMinutes != null) require(maxSessionMinutes > 0) { "Invalid constraints.maxSessionMinutes" }
        val budgetUsdMax = constraintsObj["budgetUsdMax"]?.jsonPrimitive?.doubleOrNull
        if (budgetUsdMax != null) require(budgetUsdMax >= 0) { "Invalid constraints.budgetUsdMax" }
        val intentConstraints = IntentConstraints(
            mustHave = constraintsObj["mustHave"].asStringList("constraints.mustHave"),
            mustNot = constraintsObj["mustNot"]?.asStringList("constraints.mustNot", allowEmpty = true) ?: emptyList(),
            maxSessionMinutes = maxSessionMinutes,
            budgetUsdMax = budgetUsdMax,
        )

        val confidence = root["confidence"]?.jsonPrimitive?.doubleOrNull
        if (confidence != null) require(confidence in 0.0..1.0) { "Invalid confidence" }
        val referenceIds = root["referenceIds"]?.asStringList("referenceIds", allowEmpty = true) ?: emptyList()

        return IntentDraft(
            apiVersion = apiVersion,
            kind = kind,
            outcome = outcome,
            experience = experience,
            constraints = intentConstraints,
            confidence = confidence,
            referenceIds = referenceIds,
        )
    }

    private fun extractJsonValue(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val array = json.parseToJsonElement(trimmed).jsonArray
            require(array.isNotEmpty()) { "LLM response array is empty" }
            val first = array.first().jsonObject
            return first.toString()
        }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object in response" }
        return trimmed.substring(start, end + 1)
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

    private fun kotlinx.serialization.json.JsonElement?.asObject(path: String): JsonObject {
        val value = this ?: error("$path is missing")
        return value.jsonObject
    }

    private fun kotlinx.serialization.json.JsonElement?.asString(path: String): String {
        val value = this ?: error("$path is missing")
        val text = value.jsonPrimitive.content.trim()
        require(text.isNotEmpty()) { "$path is blank" }
        return text
    }

    private fun kotlinx.serialization.json.JsonElement?.asStringList(path: String, allowEmpty: Boolean = false): List<String> {
        val value = this ?: error("$path is missing")
        val list = value.jsonArray.map {
            val text = it.jsonPrimitive.content.trim()
            require(text.isNotEmpty()) { "$path contains blank values" }
            text
        }.distinct()
        if (!allowEmpty) {
            require(list.isNotEmpty()) { "$path is empty" }
        }
        return list
    }
}
