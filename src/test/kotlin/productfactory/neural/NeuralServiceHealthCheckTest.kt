package productfactory.neural

import kotlin.test.Test
import kotlin.test.assertEquals

class NeuralServiceHealthCheckTest {

    @Test
    fun `check returns NotConfigured when baseUrl is null`() {
        val check = NeuralServiceHealthCheck(baseUrl = null)
        val result = check.check()
        assertEquals(NeuralHealthStatus.NotConfigured, result.status)
        assertEquals(null, result.detail)
    }

    @Test
    fun `check returns Unavailable when baseUrl is unreachable`() {
        val check = NeuralServiceHealthCheck(baseUrl = "http://127.0.0.1:0")
        val result = check.check()
        assertEquals(NeuralHealthStatus.Unavailable, result.status)
    }
}
