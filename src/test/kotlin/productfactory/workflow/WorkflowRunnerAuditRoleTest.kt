package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import productfactory.agent.StubAgentCodegen
import productfactory.agent.StubAgentPlanner
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowRunnerAuditRoleTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workflow audit events contain expected roles by step`() {
        val auditLog = InMemoryRoleAuditLog()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = InMemoryApprovalStore(),
            toolExecutor = RoleAwareToolExecutor(auditLog),
            agentPlanner = StubAgentPlanner(),
            agentCodegen = StubAgentCodegen(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
        )

        val result = runner.run(
            runId = "run-audit-roles",
            request = FactoryRunRequest(goal = "Create catalog service"),
        )

        assertEquals("accepted", result.status)
        assertHasRoleForEvent(auditLog.events, "policy_check", "Planner")
        assertHasRoleForStateStep(auditLog.events, "plan_workflow", "Planner")

        assertHasRoleForEvent(auditLog.events, "policy_check_codegen", "Implementer")
        assertHasRoleForEvent(auditLog.events, "tool_call_executed", "Implementer")

        assertHasRoleForEventPrefix(auditLog.events, "tests_", "Tester")
        assertHasRoleForEventPrefix(auditLog.events, "security_checks_", "Reviewer")
    }

    private fun assertHasRoleForEvent(events: List<LoggedRoleEvent>, eventType: String, expectedRole: String) {
        val event = events.firstOrNull { it.eventType == eventType }
        assertNotNull(event, "Expected event '$eventType' in audit log")
        assertEquals(expectedRole, roleFrom(event.payload), "Unexpected role for '$eventType'")
    }

    private fun assertHasRoleForEventPrefix(events: List<LoggedRoleEvent>, prefix: String, expectedRole: String) {
        val event = events.firstOrNull { it.eventType.startsWith(prefix) }
        assertNotNull(event, "Expected event with prefix '$prefix' in audit log")
        assertEquals(expectedRole, roleFrom(event.payload), "Unexpected role for '${event.eventType}'")
    }

    private fun assertHasRoleForStateStep(events: List<LoggedRoleEvent>, stepId: String, expectedRole: String) {
        val event = events.firstOrNull {
            if (it.eventType != "state_changed") return@firstOrNull false
            val payload = json.parseToJsonElement(it.payload).jsonObject
            payload["stepId"]?.jsonPrimitive?.content == stepId
        }
        assertNotNull(event, "Expected state_changed for step '$stepId'")
        assertEquals(expectedRole, roleFrom(event.payload), "Unexpected role for state step '$stepId'")
    }

    private fun roleFrom(payload: String): String {
        return json.parseToJsonElement(payload).jsonObject["role"]?.jsonPrimitive?.content.orEmpty()
    }
}

private class RoleAwareToolExecutor(
    private val auditLog: AuditLog,
) : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        auditLog.log(
            runId,
            "tool_call_executed",
            buildJsonObject {
                put("toolName", request.toolName)
                put("role", WorkflowAgentRole.IMPLEMENTER.auditValue)
            }.toString(),
        )
        return ToolCallResult(success = true, message = "ok")
    }
}

private class InMemoryRoleAuditLog : AuditLog {
    val events = mutableListOf<LoggedRoleEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedRoleEvent(runId, eventType, payload)
    }
}

private data class LoggedRoleEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)
