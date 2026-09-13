package productfactory.workflow

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AskUserStoreTest {
    @Test
    fun `in-memory store keeps pending question and saves answer`() {
        val store = InMemoryAskUserStore()

        val pending = store.saveQuestion(
            runId = "run-1",
            stepId = WorkflowStepId.RUN_TESTS,
            question = "Publish artifact?",
            options = listOf("yes", "no"),
        )
        assertEquals(AskUserQuestionStatus.PENDING, pending.status)
        assertEquals(WorkflowStepId.RUN_TESTS, pending.stepId)

        val fetched = store.getPendingQuestion("run-1")
        assertNotNull(fetched)
        assertEquals("Publish artifact?", fetched.question)

        val answered = store.submitAnswer("run-1", "yes")
        assertNotNull(answered)
        assertEquals(AskUserQuestionStatus.ANSWERED, answered.status)
        assertEquals("yes", answered.answer)
        assertNull(store.getPendingQuestion("run-1"))
    }

    @Test
    fun `file store persists question and answer`() {
        val dir = Files.createTempDirectory("ask-user-test-")
        val store = FileAskUserStore(dir)

        store.saveQuestion(
            runId = "run-2",
            stepId = WorkflowStepId.GENERATE_ARTIFACTS,
            question = "Use fast profile?",
            options = listOf("fast", "safe"),
        )
        val pending = store.getPendingQuestion("run-2")
        assertNotNull(pending)
        assertEquals("Use fast profile?", pending.question)

        val answered = store.submitAnswer("run-2", "safe")
        assertNotNull(answered)
        assertEquals(AskUserQuestionStatus.ANSWERED, answered.status)
        assertEquals("safe", answered.answer)

        val reloadedPending = FileAskUserStore(dir).getPendingQuestion("run-2")
        assertNull(reloadedPending)
    }
}
