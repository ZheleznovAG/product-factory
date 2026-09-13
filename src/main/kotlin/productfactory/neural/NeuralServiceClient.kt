package productfactory.neural

import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * Клиент к единому API нейросервиса (OpenAI-совместимый /v1/chat/completions).
 * Бэкенд прозрачен: OpenAI, self-hosted или gateway на хосте (в т.ч. Codex).
 */
interface NeuralServiceClient {
    /**
     * Отправляет сообщения и возвращает контент и usage первого assistant-ответа.
     * При ошибке/недоступности content = null; usage при этом (0,0).
     */
    fun chatCompletion(
        messages: List<ChatMessage>,
        temperature: Double? = null,
        timeout: Duration? = null,
    ): ChatCompletionResult
}

/** Результат вызова chat/completions: контент ответа и потребление токенов. */
data class ChatCompletionResult(
    val content: String?,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

@Serializable
data class ChatMessage(val role: String, val content: String)
