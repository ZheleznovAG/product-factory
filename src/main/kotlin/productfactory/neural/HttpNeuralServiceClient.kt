package productfactory.neural

import productfactory.observability.FactoryMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.http.HttpClient.Version

@Serializable
private data class ChatCompletionRequestBody(
    val messages: List<ChatMessage>,
    val model: String? = null,
    val temperature: Double? = null,
)

/**
 * Реализация [NeuralServiceClient] через HTTP к OpenAI-совместимому эндпоинту.
 * URL задаётся через [baseUrl] (например NEURAL_SERVICE_URL); при пустом — клиент не выполняет запросы.
 * При любой ошибке (сеть, не 200, неверный JSON) возвращает null — вызывающий должен использовать stub или повторить.
 */
class HttpNeuralServiceClient(
    private val fallbackMatrix: NeuralFallbackMatrix = NeuralFallbackMatrix.fromEnvOrYaml(),
    private val defaultModel: String? = null,
) : NeuralServiceClient {

    private val client = HttpClient.newBuilder()
        .version(Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun chatCompletion(
        messages: List<ChatMessage>,
        temperature: Double?,
        timeout: Duration?,
    ): ChatCompletionResult {
        val providers = fallbackMatrix.providers
        if (providers.isEmpty()) return ChatCompletionResult(content = null)
        if (messages.isEmpty()) return ChatCompletionResult(content = null)

        for (provider in providers) {
            val callResult = sendChatCompletion(
                provider = provider,
                messages = messages,
                temperature = temperature,
                timeout = timeout,
            )
            if (callResult?.content != null) return callResult
        }

        return ChatCompletionResult(content = null)
    }

    private fun sendChatCompletion(
        provider: NeuralProviderConfig,
        messages: List<ChatMessage>,
        temperature: Double?,
        timeout: Duration?,
    ): ChatCompletionResult? {
        val body = json.encodeToString(
            ChatCompletionRequestBody(
                messages = messages,
                model = provider.model ?: defaultModel,
                temperature = temperature,
            ),
        )
        val base = provider.baseUrl.replace(Regex("/+$"), "")
        val uri = URI.create("$base/v1/chat/completions")
        val reqBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(resolveTimeout(timeout, provider.timeout))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        provider.apiKey?.let { reqBuilder.header("Authorization", "Bearer $it") }
        val req = reqBuilder.build()
        return try {
            val response = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            val tree = json.parseToJsonElement(response.body())
            val usageObj = tree.jsonObject["usage"]?.jsonObject
            var inputTokens = 0L
            var outputTokens = 0L
            usageObj?.let { u ->
                inputTokens = u["prompt_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                outputTokens = u["completion_tokens"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                if (inputTokens > 0 || outputTokens > 0) FactoryMetrics.recordLlmTokens(inputTokens, outputTokens)
            }
            val content = tree.jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: return null
            ChatCompletionResult(content = content, inputTokens = inputTokens, outputTokens = outputTokens)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTimeout(requestedTimeout: Duration?, providerTimeout: Duration?): Duration = when {
        requestedTimeout != null && providerTimeout != null -> minOf(requestedTimeout, providerTimeout)
        requestedTimeout != null -> requestedTimeout
        providerTimeout != null -> providerTimeout
        else -> Duration.ofSeconds(120)
    }

    constructor(
        baseUrl: String?,
        apiKey: String?,
        model: String? = null,
    ) : this(
        fallbackMatrix = NeuralFallbackMatrix.single(baseUrl = baseUrl, apiKey = apiKey, model = model),
        defaultModel = model,
    )
}
