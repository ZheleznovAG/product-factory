package productfactory.intent

import productfactory.neural.ChatCompletionResult
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmIntentGeneratorTest {

    @Test
    fun `generate returns llm intent draft for valid response`() {
        val neuralClient = RecordingIntentNeuralClient(
            response = """
            {
              "apiVersion": "productfactory.io/v1",
              "kind": "IntentSpec",
              "outcome": {
                "goal": "Launch API MVP",
                "result": "Deliver MVP endpoint with validations",
                "successSignals": ["API responds 200", "User confirms first value"]
              },
              "experience": {
                "feeling": "Confident progress",
                "interactionStyle": "Adaptive 3-5 clarification protocol (A/B + short phrase)",
                "tempo": "fast",
                "intensity": "medium"
              },
              "constraints": {
                "mustHave": ["Kotlin/Ktor", "Docker only"],
                "mustNot": [],
                "maxSessionMinutes": 60
              },
              "confidence": 0.78,
              "referenceIds": []
            }
            """.trimIndent(),
        )
        val generator = LlmIntentGenerator(
            neuralClient = neuralClient,
            neuralServiceUrl = "http://neural.local",
        )

        val draft = generator.generate(
            goal = "Launch API MVP",
            constraints = listOf("Kotlin/Ktor", "Docker only"),
        )

        assertEquals("Launch API MVP", draft.outcome.goal)
        assertEquals("fast", draft.experience.tempo)
        assertEquals(listOf("Kotlin/Ktor", "Docker only"), draft.constraints.mustHave)
        assertEquals(0.78, draft.confidence)
        assertEquals(0.2, neuralClient.lastTemperature)
        assertEquals(Duration.ofSeconds(30), neuralClient.lastTimeout)
        assertTrue(neuralClient.calls == 1)
    }

    @Test
    fun `generate falls back to stub on invalid llm response`() {
        val generator = LlmIntentGenerator(
            neuralClient = RecordingIntentNeuralClient(response = "not a json"),
            neuralServiceUrl = "http://neural.local",
        )

        val draft = generator.generate(
            goal = "Launch API MVP",
            constraints = listOf("Kotlin/Ktor"),
        )

        assertEquals("Launch API MVP", draft.outcome.goal)
        assertEquals("5 A/B clarification protocol", draft.experience.interactionStyle)
        assertEquals(listOf("Kotlin/Ktor"), draft.constraints.mustHave)
    }

    @Test
    fun `generate falls back when neural service url is absent`() {
        val neuralClient = RecordingIntentNeuralClient(
            response = """{"apiVersion":"productfactory.io/v1"}""",
        )
        val generator = LlmIntentGenerator(
            neuralClient = neuralClient,
            neuralServiceUrl = null,
        )

        val draft = generator.generate(
            goal = "Launch API MVP",
            constraints = listOf("Kotlin/Ktor"),
        )

        assertEquals("Launch API MVP", draft.outcome.goal)
        assertEquals("5 A/B clarification protocol", draft.experience.interactionStyle)
        assertEquals(0, neuralClient.calls)
    }
}

private class RecordingIntentNeuralClient(
    private val response: String?,
) : NeuralServiceClient {
    var calls: Int = 0
    var lastTemperature: Double? = null
    var lastTimeout: Duration? = null
    lateinit var lastMessages: List<ChatMessage>

    override fun chatCompletion(messages: List<ChatMessage>, temperature: Double?, timeout: Duration?): ChatCompletionResult {
        calls += 1
        lastMessages = messages
        lastTemperature = temperature
        lastTimeout = timeout
        return ChatCompletionResult(content = response)
    }
}
