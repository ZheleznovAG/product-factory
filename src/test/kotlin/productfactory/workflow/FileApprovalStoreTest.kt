package productfactory.workflow

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FileApprovalStoreTest {
    @Test
    fun `store persists and updates approval decisions`() {
        val dir = Files.createTempDirectory("approvals-test-")
        val store = FileApprovalStore(dir)

        val pending = store.upsertPending(
            runId = "run-1",
            reason = "human approval required",
            proposedActions = listOf("tool:create_repo_from_archetype"),
        )
        assertEquals(ApprovalStatus.PENDING, pending.status)

        val approved = store.approve("run-1", decidedBy = "operator", comment = "ok")
        assertNotNull(approved)
        assertEquals(ApprovalStatus.APPROVED, approved.status)

        val reloaded = FileApprovalStore(dir).get("run-1")
        assertNotNull(reloaded)
        assertEquals(ApprovalStatus.APPROVED, reloaded.status)
        assertEquals("operator", reloaded.decidedBy)
    }
}
