package webapp.integration

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import webapp.module

class ApplicationStartupIntegrationTest {
    @Test
    fun `application starts and serves ready endpoint`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            module()
        }.start()

        try {
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:$port/health/ready"))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("ready"))
        } finally {
            server.stop(1_000, 2_000)
        }
    }
}
