package productfactory.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentGeneratorTest {

    private val generator = IntentGenerator()

    @Test
    fun `generate returns intent draft with schema-compatible top level fields`() {
        val draft = generator.generate(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor", "Docker only"),
        )

        assertEquals("productfactory.io/v1", draft.apiVersion)
        assertEquals("IntentSpec", draft.kind)
        assertEquals("Launch catalog API MVP", draft.outcome.goal)
        assertEquals("5 A/B clarification protocol", draft.experience.interactionStyle)
        assertEquals(listOf("Kotlin/Ktor", "Docker only"), draft.constraints.mustHave)
        val confidence = draft.confidence
        assertTrue(confidence != null)
        assertTrue(confidence in 0.0..1.0)
    }

    @Test
    fun `generate normalizes blank input and derives conservative defaults`() {
        val draft = generator.generate(
            goal = "   ",
            constraints = listOf(" ", " "),
        )

        assertEquals("unspecified goal", draft.outcome.goal)
        assertEquals(listOf("Clarify must-have constraints via 5 A/B protocol"), draft.constraints.mustHave)
        assertEquals("balanced", draft.experience.tempo)
        assertEquals(90, draft.constraints.maxSessionMinutes)
    }
}
