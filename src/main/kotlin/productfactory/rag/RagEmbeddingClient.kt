package productfactory.rag

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

interface RagEmbeddingClient {
    fun embed(text: String): FloatArray?
}

@Serializable
private data class EmbeddingRequestBody(
    val model: String,
    val input: String,
)

class NeuralRagEmbeddingClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val expectedDim: Int,
) : RagEmbeddingClient {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val uri = URI.create("${baseUrl.trimEnd('/')}/v1/embeddings")
        val body = json.encodeToString(EmbeddingRequestBody(model = model, input = text))
        val builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        apiKey?.let { builder.header("Authorization", "Bearer $it") }

        return try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return null
            val tree = json.parseToJsonElement(response.body()).jsonObject
            val vector = tree["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray ?: return null
            if (vector.size != expectedDim) return null
            FloatArray(vector.size) { index -> vector[index].jsonPrimitive.content.toFloatOrNull() ?: 0f }
        } catch (_: Exception) {
            null
        }
    }
}
