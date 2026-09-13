package productfactory.neural

import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NeuralFallbackMatrixTest {

    @Test
    fun `fromEnvOrYaml reads indexed providers and sorts by priority`() {
        val env = mapOf(
            "NEURAL_SERVICE_PROVIDER_1_URL" to "http://primary.example",
            "NEURAL_SERVICE_PROVIDER_1_PRIORITY" to "200",
            "NEURAL_SERVICE_PROVIDER_2_URL" to "http://secondary.example",
            "NEURAL_SERVICE_PROVIDER_2_PRIORITY" to "100",
            "NEURAL_SERVICE_PROVIDER_2_TIMEOUT_SECONDS" to "7",
            "NEURAL_SERVICE_PROVIDER_2_API_KEY" to "k2",
        )

        val matrix = NeuralFallbackMatrix.fromEnvOrYaml(env)

        assertEquals(2, matrix.providers.size)
        assertEquals("http://secondary.example", matrix.providers[0].baseUrl)
        assertEquals("k2", matrix.providers[0].apiKey)
        assertEquals(7L, matrix.providers[0].timeout?.seconds)
        assertEquals("http://primary.example", matrix.providers[1].baseUrl)
    }

    @Test
    fun `fromEnvOrYaml reads YAML fallback matrix`() {
        val file = createTempFile(prefix = "neural-matrix-", suffix = ".yaml")
        try {
            file.writeText(
                """
                apiVersion: productfactory.io/v1
                kind: NeuralFallbackMatrix
                providers:
                  - name: secondary
                    base_url: http://secondary.local
                    priority: 200
                    timeout_seconds: 15
                  - name: primary
                    base_url: http://primary.local
                    priority: 100
                    timeout_seconds: 5
                    api_key: top-secret
                """.trimIndent(),
            )
            val env = mapOf("NEURAL_SERVICE_FALLBACK_MATRIX_PATH" to file.toString())

            val matrix = NeuralFallbackMatrix.fromEnvOrYaml(env)

            assertEquals(2, matrix.providers.size)
            val primary = matrix.primaryProvider()
            assertNotNull(primary)
            assertEquals("primary", primary.name)
            assertEquals("http://primary.local", primary.baseUrl)
            assertEquals(5L, primary.timeout?.seconds)
            assertEquals("top-secret", primary.apiKey)
        } finally {
            File(file.toString()).delete()
        }
    }

    @Test
    fun `fromEnvOrYaml returns empty matrix when no configuration provided`() {
        val matrix = NeuralFallbackMatrix.fromEnvOrYaml(emptyMap())
        assertEquals(0, matrix.providers.size)
        assertNull(matrix.primaryProvider())
    }

    @Test
    fun `fromEnvOrYaml treats legacy url as primary when only secondary env is set`() {
        val env = mapOf(
            "NEURAL_SERVICE_URL" to "http://legacy-primary.local",
            "NEURAL_SERVICE_URL_SECONDARY" to "http://secondary.local",
            "NEURAL_SERVICE_PRIORITY_SECONDARY" to "250",
        )

        val matrix = NeuralFallbackMatrix.fromEnvOrYaml(env)

        assertEquals(2, matrix.providers.size)
        assertEquals("http://legacy-primary.local", matrix.providers[0].baseUrl)
        assertEquals("http://secondary.local", matrix.providers[1].baseUrl)
    }
}
