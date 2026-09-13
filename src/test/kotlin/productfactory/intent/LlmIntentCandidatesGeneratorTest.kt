package productfactory.intent

import productfactory.neural.ChatCompletionResult
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmIntentCandidatesGeneratorTest {

    @Test
    fun `generate returns llm candidates for valid response`() {
        val neuralClient = RecordingCandidatesNeuralClient(
            response = """{"candidates":["Option A","Option B","Option C"]}""",
        )
        val generator = LlmIntentCandidatesGenerator(
            neuralClient = neuralClient,
            neuralServiceUrl = "http://neural.local",
        )

        val candidates = generator.generate("Catalog service with strict audit trail")

        assertEquals(listOf("Option A", "Option B", "Option C"), candidates)
        assertEquals(0.25, neuralClient.lastTemperature)
        assertEquals(Duration.ofSeconds(30), neuralClient.lastTimeout)
        assertTrue(neuralClient.calls == 1)
    }

    @Test
    fun `generate falls back to stub on invalid llm response`() {
        val generator = LlmIntentCandidatesGenerator(
            neuralClient = RecordingCandidatesNeuralClient(response = """{"candidates":["One"]}"""),
            neuralServiceUrl = "http://neural.local",
        )

        val candidates = generator.generate("Catalog service")

        assertEquals(3, candidates.size)
        assertTrue(candidates.first().startsWith("Candidate 1:"))
    }

    @Test
    fun `generate falls back when neural service url is absent`() {
        val neuralClient = RecordingCandidatesNeuralClient(
            response = """{"candidates":["Option A","Option B","Option C"]}""",
        )
        val generator = LlmIntentCandidatesGenerator(
            neuralClient = neuralClient,
            neuralServiceUrl = null,
        )

        val candidates = generator.generate("Catalog service")

        assertEquals(3, candidates.size)
        assertTrue(candidates.first().startsWith("Candidate 1:"))
        assertEquals(0, neuralClient.calls)
    }
}

private class RecordingCandidatesNeuralClient(
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
