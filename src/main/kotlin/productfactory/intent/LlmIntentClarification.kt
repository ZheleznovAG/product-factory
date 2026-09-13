package productfactory.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration

class LlmIntentClarification(
    private val neuralClient: NeuralServiceClient,
    private val fallbackClarifier: IntentClarifier = IntentClarification(),
    private val neuralServiceUrl: String? = System.getenv("NEURAL_SERVICE_URL")?.trim()?.takeIf { it.isNotBlank() },
) : IntentClarifier {

    private val json = Json { ignoreUnknownKeys = true }

    override fun updateIntentDraft(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
    ): IntentDraft = fallbackClarifier.updateIntentDraft(goal, constraints, previousAnswers)

    override fun nextQuestion(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
        currentIntentDraft: IntentDraft?,
    ): String? {
        if (previousAnswers.size >= IntentClarification.MAX_QUESTIONS) return null
        val draft = currentIntentDraft ?: fallbackClarifier.updateIntentDraft(goal, constraints, previousAnswers)
        if (neuralServiceUrl == null) {
            return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)
        }

        val timeoutSeconds = System.getenv("INTENT_CLARIFICATION_LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 30L
        val result = try {
            neuralClient.chatCompletion(
                messages = listOf(
                    ChatMessage(role = "system", content = buildSystemPrompt()),
                    ChatMessage(role = "user", content = buildUserPrompt(goal, constraints, previousAnswers, draft)),
                ),
                temperature = 0.2,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        } catch (_: Exception) {
            return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)
        }

        val content = result.content?.trim()
            ?: return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)

        val parsed = try {
            parseResponse(content)
        } catch (_: Exception) {
            return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)
        }

        if (parsed.done) return null
        val question = parsed.nextQuestion?.trim().takeUnless { it.isNullOrBlank() }
            ?: return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)
        if (!question.contains("A)") || !question.contains("B)")) {
            return fallbackClarifier.nextQuestion(goal, constraints, previousAnswers, draft)
        }
        return question
    }

    private fun buildSystemPrompt(): String = """
You are Product Factory Intent Clarifier.
Return strictly JSON only without markdown.
Schema:
{"next_question":"string|null","done":true|false}
Rules:
- Ask one short A/B clarification question in Russian.
- Keep question aligned to goal/constraints, already given answers, and current intent draft.
- Assume current_intent_draft is updated after every answer.
- Users may answer either with A/B or short phrase.
- If latest answer is ambiguous, ask one short disambiguation question; if still ambiguous, force explicit A/B choice.
- If clarification is complete OR 5 questions already asked, return {"next_question":null,"done":true}.
- If asking question, return {"next_question":"...A)... B)...","done":false}.
""".trimIndent()

    private fun buildUserPrompt(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
        currentIntentDraft: IntentDraft,
    ): String {
        val normalizedGoal = goal.trim().ifBlank { "<empty>" }
        val normalizedConstraints = constraints.map { it.trim() }.filter { it.isNotEmpty() }
        val constraintsBlock = if (normalizedConstraints.isEmpty()) "[]" else normalizedConstraints.joinToString(prefix = "[", postfix = "]")
        val answersBlock = if (previousAnswers.isEmpty()) "[]" else previousAnswers.joinToString(prefix = "[", postfix = "]")
        return """
goal: $normalizedGoal
constraints: $constraintsBlock
previous_answers: $answersBlock
current_intent_draft: ${serializeDraft(currentIntentDraft)}
Return only JSON object with fields next_question and done.
""".trimIndent()
    }

    private fun serializeDraft(draft: IntentDraft): String {
        val mustHave = if (draft.constraints.mustHave.isEmpty()) "[]" else draft.constraints.mustHave.joinToString(prefix = "[", postfix = "]")
        return "outcome.goal=${draft.outcome.goal}; outcome.result=${draft.outcome.result}; " +
            "experience.tempo=${draft.experience.tempo}; constraints.mustHave=$mustHave; confidence=${draft.confidence}"
    }

    private fun parseResponse(raw: String): ClarificationResponse {
        val jsonText = extractJsonObject(stripMarkdownJsonFence(raw))
        val obj = json.parseToJsonElement(jsonText).jsonObject
        val done = obj["done"]?.jsonPrimitive?.booleanOrNull ?: false
        val nextQuestion = obj["next_question"]?.jsonPrimitive?.contentOrNull
        return ClarificationResponse(nextQuestion = nextQuestion, done = done)
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object in response" }
        return raw.substring(start, end + 1)
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

    private data class ClarificationResponse(
        val nextQuestion: String?,
        val done: Boolean,
    )
}
