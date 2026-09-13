package productfactory.neural

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Проверка доступности нейросервиса (gateway/Codex). Делает GET к [baseUrl]/health с коротким таймаутом.
 * Если [baseUrl] не задан — [status] = [NeuralHealthStatus.NotConfigured].
 */
class NeuralServiceHealthCheck(
    private val baseUrl: String? = NeuralFallbackMatrix.fromEnvOrYaml().primaryProvider()?.baseUrl,
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    fun check(): NeuralHealthResult {
        val url = baseUrl ?: return NeuralHealthResult(NeuralHealthStatus.NotConfigured, null)
        val base = url.replace(Regex("/+$"), "")
        val uri = URI.create("$base/health")
        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(timeout)
            .GET()
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val status = if (response.statusCode() == 200) NeuralHealthStatus.Ok else NeuralHealthStatus.Unavailable
            NeuralHealthResult(status, response.body().take(200))
        } catch (e: Exception) {
            NeuralHealthResult(NeuralHealthStatus.Unavailable, e.message)
        }
    }
}

enum class NeuralHealthStatus { NotConfigured, Ok, Unavailable }

data class NeuralHealthResult(val status: NeuralHealthStatus, val detail: String?)
