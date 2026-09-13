package productfactory.intent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentClarificationTest {

    private val clarification = IntentClarification()

    @Test
    fun `returns first question when no previous answers`() {
        val question = clarification.nextQuestion(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor", "Docker only"),
            previousAnswers = emptyList(),
        )

        assertNotNull(question)
        assertEquals(
            "Что важнее для API на первом шаге: A) быстро отдать рабочие эндпоинты или B) сначала зафиксировать production baseline?",
            question,
        )
    }

    @Test
    fun `detects web-app domain from goal and asks web-specific first question`() {
        val question = clarification.nextQuestion(
            goal = "Build web app for storefront checkout",
            constraints = listOf("React", "responsive UI"),
            previousAnswers = emptyList(),
        )

        assertEquals(
            "Что важнее для web-app: A) быстро показать рабочий UI flow (MVP) или B) сначала выстроить production foundation?",
            question,
        )
    }

    @Test
    fun `detects data pipeline domain from constraints and asks pipeline-specific first question`() {
        val question = clarification.nextQuestion(
            goal = "Improve internal analytics flow",
            constraints = listOf("Airflow orchestration", "ETL jobs", "DWH"),
            previousAnswers = emptyList(),
        )

        assertEquals(
            "Что важнее для data pipeline: A) быстрее запустить поток данных end-to-end или B) сначала гарантировать качество и надежность данных?",
            question,
        )
    }

    @Test
    fun `returns null after max questions reached`() {
        val question = clarification.nextQuestion(
            goal = "Goal",
            constraints = listOf("c1"),
            previousAnswers = listOf("A", "B", "A", "B", "A"),
        )

        assertNull(question)
    }

    @Test
    fun `reorders first question for production-sensitive constraints`() {
        val question = clarification.nextQuestion(
            goal = "Launch production API",
            constraints = listOf("strict security", "compliance"),
            previousAnswers = emptyList(),
        )

        assertEquals(
            "По API-ограничениям выбираем: A) вторичные можно ослабить или B) все must-have и совместимость без компромиссов?",
            question,
        )
    }

    @Test
    fun `stops early after enough answers and confidence`() {
        val question = clarification.nextQuestion(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor", "Docker only"),
            previousAnswers = listOf("A", "B", "B"),
        )

        assertNull(question)
    }

    @Test
    fun `updates intent draft from answers`() {
        val draft = clarification.updateIntentDraft(
            goal = "Launch catalog API",
            constraints = listOf("Kotlin/Ktor", "Docker only"),
            previousAnswers = listOf("A", "B", "B"),
        )

        assertEquals("balanced", draft.experience.tempo)
        assertTrue((draft.confidence ?: 0.0) >= 0.8)
        assertTrue(draft.outcome.result.contains("Deliver fast MVP first"))
        assertTrue(draft.constraints.mustHave.any { it.contains("strict must-have") })
    }

    @Test
    fun `maps short free-text phrase into intent fields`() {
        val draft = clarification.updateIntentDraft(
            goal = "Launch catalog API",
            constraints = listOf("Kotlin/Ktor"),
            previousAnswers = listOf(
                "Хочу быстрый mvp",
                "нужны валидации и тесты",
                "без компромиссов по must-have",
            ),
        )

        assertTrue(draft.outcome.result.contains("Deliver fast MVP first"))
        assertTrue(draft.outcome.successSignals.any { it.contains("validation", ignoreCase = true) })
        assertTrue(draft.constraints.mustHave.any { it.contains("strict must-have") })
    }

    @Test
    fun `asks one clarifying question for ambiguous phrase then falls back to strict A or B`() {
        val clarifyingQuestion = clarification.nextQuestion(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor"),
            previousAnswers = listOf("не знаю, как лучше"),
        )

        assertNotNull(clarifyingQuestion)
        assertTrue(clarifyingQuestion.startsWith("Не до конца понял ответ."))

        val fallbackQuestion = clarification.nextQuestion(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor"),
            previousAnswers = listOf("не знаю, как лучше", "все еще не уверен"),
        )

        assertNotNull(fallbackQuestion)
        assertTrue(fallbackQuestion.startsWith("Нужен точный выбор A или B:"))
    }

    @Test
    fun `does not treat words starting with a or b letters as explicit choice`() {
        val clarifyingQuestion = clarification.nextQuestion(
            goal = "Launch catalog API MVP",
            constraints = listOf("Kotlin/Ktor"),
            previousAnswers = listOf("архитектура важнее"),
        )

        assertNotNull(clarifyingQuestion)
        assertTrue(clarifyingQuestion.startsWith("Не до конца понял ответ."))
    }
}
