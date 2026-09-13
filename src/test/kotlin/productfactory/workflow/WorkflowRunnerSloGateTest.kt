package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowRunnerSloGateTest {
    @Test
    fun `workflow is rejected when slo gate thresholds are violated`() {
        val auditLog = InMemorySloAuditLog()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = InMemoryApprovalStore(),
            toolExecutor = AllowSloToolExecutor(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
        )

        val result = runner.run(
            runId = "run-slo-fail",
            request = FactoryRunRequest(
                goal = "Create catalog service",
                contracts = mapOf("quality_profile" to strictSloQualityProfileYaml()),
            ),
        )

        assertEquals("rejected", result.status)
        assertTrue(result.message.contains("SLO gate failed"), result.message)
        assertTrue(auditLog.events.any { it.eventType == "slo_gate_evaluated" && it.payload.contains(""""status":"FAIL"""") })
        assertTrue(auditLog.events.any { it.eventType == "state_changed" && it.payload.contains(""""newState":"FAILED"""") })
        assertTrue(auditLog.events.none { it.eventType == "state_changed" && it.payload.contains(""""newState":"DONE"""") })
    }

    @Test
    fun `workflow is accepted when slo gate thresholds are satisfied`() {
        val auditLog = InMemorySloAuditLog()
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = InMemoryApprovalStore(),
            toolExecutor = AllowSloToolExecutor(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
        )

        val result = runner.run(
            runId = "run-slo-pass",
            request = FactoryRunRequest(
                goal = "Create catalog service",
                contracts = mapOf("quality_profile" to permissiveSloQualityProfileYaml()),
            ),
        )

        assertEquals("accepted", result.status)
        assertTrue(auditLog.events.any { it.eventType == "slo_gate_evaluated" && it.payload.contains(""""status":"PASS"""") })
        assertTrue(auditLog.events.any { it.eventType == "state_changed" && it.payload.contains(""""newState":"DONE"""") })
    }
}

private class AllowSloToolExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(success = true, message = "ok")
    }
}

private class InMemorySloAuditLog : AuditLog {
    val events = mutableListOf<LoggedSloEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedSloEvent(runId, eventType, payload)
    }
}

private data class LoggedSloEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)

private fun strictSloQualityProfileYaml(): String = """
apiVersion: productfactory.io/v1
kind: QualityProfile
testing:
  unitCoverageMinPercent: 70
  integrationTestsRequired: true
  smokeTestsRequired: true
securityGates:
  sbom:
    format: cyclonedx
    required: true
  vulnerabilityScan:
    tool: trivy
    failOnSeverity: [HIGH, CRITICAL]
  signing:
    tool: cosign
    required: true
observability:
  tracing:
    required: true
    protocol: otlp
  metrics:
    required: true
sloGate:
  enabled: true
  maxGenerationTimeSeconds: 0.000001
  maxPolicyDenySharePercent: 100
  minSuccessRatePercent: 0
  lookbackRuns: 20
""".trimIndent()

private fun permissiveSloQualityProfileYaml(): String = """
apiVersion: productfactory.io/v1
kind: QualityProfile
testing:
  unitCoverageMinPercent: 70
  integrationTestsRequired: true
  smokeTestsRequired: true
securityGates:
  sbom:
    format: cyclonedx
    required: true
  vulnerabilityScan:
    tool: trivy
    failOnSeverity: [HIGH, CRITICAL]
  signing:
    tool: cosign
    required: true
observability:
  tracing:
    required: true
    protocol: otlp
  metrics:
    required: true
sloGate:
  enabled: true
  maxGenerationTimeSeconds: 7200
  maxPolicyDenySharePercent: 100
  minSuccessRatePercent: 0
  lookbackRuns: 20
""".trimIndent()
