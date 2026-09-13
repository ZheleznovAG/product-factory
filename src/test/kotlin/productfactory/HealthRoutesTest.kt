package productfactory

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import productfactory.api.HealthResponse
import productfactory.api.NeuralHealthResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GET health returns ok`() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("ok", body.status)
    }

    @Test
    fun `GET health ready returns ready`() = testApplication {
        application { module() }
        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<HealthResponse>(response.bodyAsText())
        assertEquals("ready", body.status)
    }

    @Test
    fun `GET health neural returns not_configured when NEURAL_SERVICE_URL not set`() = testApplication {
        application { module() }
        val response = client.get("/health/neural")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<NeuralHealthResponse>(response.bodyAsText())
        assertEquals("not_configured", body.neural_service)
    }
}
