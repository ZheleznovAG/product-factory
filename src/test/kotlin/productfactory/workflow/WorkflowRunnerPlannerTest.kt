package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowRunnerPlannerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `workflow emits planner artifacts after policy check`() {
        val auditLog = InMemoryPlannerAuditLog()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = InMemoryApprovalStore(),
            toolExecutor = AllowToolExecutor(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
        )

        val result = runner.run(
            runId = "run-planner",
            request = FactoryRunRequest(
                goal = "Create catalog service",
                constraints = listOf("kotlin", "ktor"),
                productSpec = """{"type":"product_spec"}""",
                contracts = mapOf("product.yaml" to "apiVersion: productfactory.io/v1"),
            ),
        )

        assertEquals("accepted", result.status)
        assertTrue(auditLog.events.any { it.eventType == "policy_check" })
        assertTrue(
            auditLog.events.any {
                it.eventType == "state_changed" &&
                    it.payload.contains(""""stepId":"plan_workflow"""") &&
                    it.payload.contains(""""role":"Planner"""")
            },
        )
        assertTrue(
            auditLog.events.any {
                it.eventType == "state_changed" &&
                    it.payload.contains(""""stepId":"generate_artifacts"""") &&
                    it.payload.contains(""""role":"Implementer"""")
            },
        )
        assertTrue(
            auditLog.events.any {
                it.eventType == "state_changed" &&
                    it.payload.contains(""""stepId":"stage_artifacts"""") &&
                    it.payload.contains(""""role":""""")
            },
        )
        assertTrue(auditLog.events.any { it.eventType == "policy_check" && it.payload.contains(""""role":"Planner"""") })
        assertTrue(auditLog.events.any { it.eventType == "proposal_recorded" && it.payload.contains(""""role":"Implementer"""") })
        assertTrue(auditLog.events.any { it.eventType == "tests_skipped" && it.payload.contains(""""role":"Tester"""") })
        assertTrue(auditLog.events.any { it.eventType == "security_checks_skipped" && it.payload.contains(""""role":"Reviewer"""") })
        assertTrue(auditLog.events.any { it.eventType == "staging_prepared" && it.payload.contains(""""role":""""") })
        assertTrue(auditLog.events.any { it.eventType == "artifact_manifest_written" && it.payload.contains(""""state":"STAGED"""") })
        assertTrue(auditLog.events.any { it.eventType == "artifact_manifest_written" && it.payload.contains(""""state":"DONE"""") })

        val pipelinePayload = auditLog.events.firstOrNull { it.eventType == "pipeline_plan" }?.payload
        val plannerExplanationPayload = auditLog.events.firstOrNull { it.eventType == "planner_explanation" }?.payload
        val adrPayload = auditLog.events.firstOrNull { it.eventType == "adr_draft" }?.payload
        val testPlanPayload = auditLog.events.firstOrNull { it.eventType == "test_plan" }?.payload
        val codegenPayload = auditLog.events.firstOrNull { it.eventType == "codegen_patch_set" }?.payload
        val proposalRecordedPayload = auditLog.events.firstOrNull { it.eventType == "proposal_recorded" }?.payload

        assertTrue(pipelinePayload != null)
        assertTrue(plannerExplanationPayload != null)
        assertTrue(adrPayload != null)
        assertTrue(testPlanPayload != null)
        assertTrue(codegenPayload != null)
        assertTrue(proposalRecordedPayload != null)
        assertEquals(
            "pipeline_plan",
            json.parseToJsonElement(pipelinePayload).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "adr_draft",
            json.parseToJsonElement(adrPayload).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "test_plan",
            json.parseToJsonElement(testPlanPayload).jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "codegen_patch_set",
            json.parseToJsonElement(codegenPayload).jsonObject.getValue("type").jsonPrimitive.content,
        )
        val proposalRecorded = json.parseToJsonElement(proposalRecordedPayload).jsonObject
        assertEquals("codegen", proposalRecorded.getValue("proposal_type").jsonPrimitive.content)
        assertTrue(proposalRecorded.getValue("file_count").jsonPrimitive.int > 0)
        val plannerExplanation = json.parseToJsonElement(plannerExplanationPayload).jsonObject
        assertTrue(plannerExplanation.getValue("explanation").jsonPrimitive.content.isNotBlank())
    }
}

private class AllowToolExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(success = true, message = "ok")
    }
}

private class InMemoryPlannerAuditLog : AuditLog {
    val events = mutableListOf<LoggedPlannerEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedPlannerEvent(runId, eventType, payload)
    }
}

private data class LoggedPlannerEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)
