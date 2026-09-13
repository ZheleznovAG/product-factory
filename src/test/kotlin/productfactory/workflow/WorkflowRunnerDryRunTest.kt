package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowRunnerDryRunTest {

    @Test
    fun `dry run records tool plan in audit and does not execute tools`() {
        val auditLog = InMemoryDryRunAuditLog()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = InMemoryApprovalStore(),
            toolExecutor = FailIfToolCalledExecutor(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
        )

        val result = runner.run(
            runId = "run-dry",
            request = FactoryRunRequest(
                goal = "Create catalog service",
                dryRun = true,
            ),
        )

        assertEquals("accepted", result.status)
        assertTrue(result.message.contains("Dry-run completed"))
        val dryRunEvent = auditLog.events.firstOrNull { it.eventType == "tool_call_dry_run_plan" }
        assertTrue(dryRunEvent != null, "Expected tool_call_dry_run_plan event")
        assertTrue(dryRunEvent.payload.contains(""""toolName":"create_repo_from_archetype""""))
        assertTrue(dryRunEvent.payload.contains(""""allowed":true"""))
        assertTrue(dryRunEvent.payload.contains(""""wouldExecute":true"""))
        assertFalse(auditLog.events.any { it.eventType == "tool_call_executed" })
    }
}

private class FailIfToolCalledExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(
            success = false,
            result = buildJsonObject { put("error", "tool must not be executed in dry-run") },
            message = "tool must not be executed in dry-run",
        )
    }
}

private class InMemoryDryRunAuditLog : AuditLog {
    val events = mutableListOf<DryRunLoggedEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += DryRunLoggedEvent(runId = runId, eventType = eventType, payload = payload)
    }
}

private data class DryRunLoggedEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)
