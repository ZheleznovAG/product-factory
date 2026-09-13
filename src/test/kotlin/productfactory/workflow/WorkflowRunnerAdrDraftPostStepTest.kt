package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import java.nio.file.Files
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.streams.toList

class WorkflowRunnerAdrDraftPostStepTest {
    @Test
    fun `workflow post step generates adr markdown draft`() {
        val tempDir = Files.createTempDirectory("workflow-adr-post")
        val auditLog = FileAuditLog(tempDir.resolve("audit.log").toString())
        val approvals = InMemoryApprovalStore()
        val outputDir = tempDir.resolve("docs/adr/generated")
        val generator = TemplateSprintAdrDraftGenerator(
            auditLog = auditLog,
            approvalStore = approvals,
            outputDirectory = outputDir,
        )
        val runner = WorkflowRunner(
            auditLog = auditLog,
            policyCheck = PolicyCheck(opaBaseUrl = null),
            approvalStore = approvals,
            toolExecutor = AllowAdrToolExecutor(),
            tracer = GlobalOpenTelemetry.getTracer("test"),
            sprintAdrDraftGenerator = generator,
        )

        val result = runner.run(
            runId = "run-adr-post",
            request = FactoryRunRequest(
                goal = "Generate sprint ADR",
                contracts = mapOf(
                    "risk_profile" to """
                        apiVersion: productfactory.io/v1
                        kind: RiskProfile
                        riskTier: medium
                        agentPolicy:
                          approvalsRequiredFor:
                            - write_repository
                          budgets:
                            maxLlmtokensPerRun: 100000
                            maxToolCallsPerRun: 50
                            maxWallClockSecondsPerRun: 900
                        threatModel:
                          llmTop10Focus:
                            - excessive_agency
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals("accepted", result.status)
        val generated = Files.list(outputDir).use { stream -> stream.toList() }
        assertEquals(1, generated.size)
        val markdown = generated.first().toFile().readText()
        assertTrue(markdown.contains("## Контекст"))
        assertTrue(markdown.contains("## Решение"))
        assertTrue(markdown.contains("## Альтернативы"))
        assertTrue(markdown.contains("## Риски и контроль"))
        assertTrue(markdown.contains("run-adr-post"))

        val recent = auditLog.recentEvents("run-adr-post", 100)
        assertTrue(recent.any { it.eventType == "adr_markdown_generated" })
    }
}

private class AllowAdrToolExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(success = true, message = "ok")
    }
}
