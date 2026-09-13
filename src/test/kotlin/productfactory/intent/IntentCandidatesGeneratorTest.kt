package productfactory.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentCandidatesGeneratorTest {
    private val generator = IntentCandidatesGenerator()

    @Test
    fun `generate returns at least three candidate descriptions`() {
        val candidates = generator.generate("Catalog service with strict audit trail")

        assertEquals(3, candidates.size)
        assertTrue(candidates.all { it.isNotBlank() })
    }

    @Test
    fun `generate normalizes blank intent`() {
        val candidates = generator.generate("   ")

        assertTrue(candidates.all { it.contains("unspecified intent") })
    }
}
