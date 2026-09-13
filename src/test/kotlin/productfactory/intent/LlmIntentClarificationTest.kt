package productfactory.intent

import productfactory.neural.ChatCompletionResult
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmIntentClarificationTest {

    @Test
    fun `returns llm question for valid response`() {
        val neuralClient = RecordingNeuralServiceClient(
            response = """{"next_question":"Что выбрать: A) MVP или B) baseline?","done":false}""",
        )
        val clarifier = LlmIntentClarification(
            neuralClient = neuralClient,
            neuralServiceUrl = "http://neural.local",
        )

        val question = clarifier.nextQuestion(
            goal = "Launch API",
            constraints = listOf("Kotlin"),
            previousAnswers = emptyList(),
        )

        assertEquals("Что выбрать: A) MVP или B) baseline?", question)
        assertEquals(0.2, neuralClient.lastTemperature)
        assertEquals(Duration.ofSeconds(30), neuralClient.lastTimeout)
        assertTrue(neuralClient.calls == 1)
        assertTrue(neuralClient.lastMessages.first().content.contains("Return strictly JSON only"))
        assertTrue(neuralClient.lastMessages[1].content.contains("current_intent_draft"))
    }

    @Test
    fun `returns null when llm says done`() {
        val clarifier = LlmIntentClarification(
            neuralClient = RecordingNeuralServiceClient(response = """{"next_question":null,"done":true}"""),
            neuralServiceUrl = "http://neural.local",
        )

        val question = clarifier.nextQuestion(
            goal = "Launch API",
            constraints = emptyList(),
            previousAnswers = listOf("A", "B", "A"),
        )

        assertNull(question)
    }

    @Test
    fun `falls back to stub on invalid llm response`() {
        val clarifier = LlmIntentClarification(
            neuralClient = RecordingNeuralServiceClient(response = "not json"),
            neuralServiceUrl = "http://neural.local",
        )

        val question = clarifier.nextQuestion(
            goal = "Launch API",
            constraints = listOf("Kotlin"),
            previousAnswers = emptyList(),
        )

        assertEquals(
            "Что важнее для API на первом шаге: A) быстро отдать рабочие эндпоинты или B) сначала зафиксировать production baseline?",
            question,
        )
    }

    @Test
    fun `falls back to stub when neural service url is absent`() {
        val neuralClient = RecordingNeuralServiceClient(
            response = """{"next_question":"Что выбрать: A) MVP или B) baseline?","done":false}""",
        )
        val clarifier = LlmIntentClarification(
            neuralClient = neuralClient,
            neuralServiceUrl = null,
        )

        val question = clarifier.nextQuestion(
            goal = "Launch API",
            constraints = listOf("Kotlin"),
            previousAnswers = emptyList(),
        )

        assertEquals(
            "Что важнее для API на первом шаге: A) быстро отдать рабочие эндпоинты или B) сначала зафиксировать production baseline?",
            question,
        )
        assertEquals(0, neuralClient.calls)
    }
}

private class RecordingNeuralServiceClient(
    private val response: String?,
) : NeuralServiceClient {
    var calls: Int = 0
    lateinit var lastMessages: List<ChatMessage>
    var lastTemperature: Double? = null
    var lastTimeout: Duration? = null

    override fun chatCompletion(messages: List<ChatMessage>, temperature: Double?, timeout: Duration?): ChatCompletionResult {
        calls += 1
        lastMessages = messages
        lastTemperature = temperature
        lastTimeout = timeout
        return ChatCompletionResult(content = response)
    }
}
