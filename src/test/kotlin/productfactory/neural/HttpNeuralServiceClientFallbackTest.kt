package productfactory.neural

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpNeuralServiceClientFallbackTest {

    @Test
    fun `chatCompletion switches to secondary when primary returns 5xx`() {
        val primary = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        val secondary = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val response = """
                    {
                      "choices":[{"message":{"content":"from-secondary"}}],
                      "usage":{"prompt_tokens":12,"completion_tokens":5}
                    }
                """.trimIndent()
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val matrix = NeuralFallbackMatrix(
                providers = listOf(
                    NeuralProviderConfig(
                        name = "primary",
                        baseUrl = "http://127.0.0.1:${primary.address.port}",
                        priority = 100,
                        timeout = Duration.ofSeconds(2),
                    ),
                    NeuralProviderConfig(
                        name = "secondary",
                        baseUrl = "http://127.0.0.1:${secondary.address.port}",
                        priority = 200,
                        timeout = Duration.ofSeconds(2),
                    ),
                ),
            )
            val client = HttpNeuralServiceClient(fallbackMatrix = matrix)

            val result = client.chatCompletion(
                messages = listOf(ChatMessage(role = "user", content = "hello")),
                timeout = Duration.ofSeconds(2),
            )

            assertEquals("from-secondary", result.content)
            assertEquals(12L, result.inputTokens)
            assertEquals(5L, result.outputTokens)
        } finally {
            primary.stop(0)
            secondary.stop(0)
        }
    }

    @Test
    fun `chatCompletion switches to secondary when primary times out`() {
        val primary = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                Thread.sleep(400)
                val response = """{"choices":[{"message":{"content":"slow-primary"}}]}"""
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val secondary = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val response = """{"choices":[{"message":{"content":"fast-secondary"}}]}"""
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        try {
            val matrix = NeuralFallbackMatrix(
                providers = listOf(
                    NeuralProviderConfig(
                        name = "primary",
                        baseUrl = "http://127.0.0.1:${primary.address.port}",
                        priority = 100,
                        timeout = Duration.ofMillis(50),
                    ),
                    NeuralProviderConfig(
                        name = "secondary",
                        baseUrl = "http://127.0.0.1:${secondary.address.port}",
                        priority = 200,
                        timeout = Duration.ofSeconds(2),
                    ),
                ),
            )
            val client = HttpNeuralServiceClient(fallbackMatrix = matrix)

            val result = client.chatCompletion(
                messages = listOf(ChatMessage(role = "user", content = "hello")),
                timeout = Duration.ofSeconds(2),
            )

            assertNotNull(result.content)
            assertEquals("fast-secondary", result.content)
        } finally {
            primary.stop(0)
            secondary.stop(0)
        }
    }
}
