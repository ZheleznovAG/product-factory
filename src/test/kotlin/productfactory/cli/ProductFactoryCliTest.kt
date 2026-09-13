package productfactory.cli

import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import productfactory.workflow.AuditEvent
import productfactory.workflow.FileApprovalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductFactoryCliTest {

    @Test
    fun `run command requires goal`() {
        val exitCode = ProductFactoryCli.run(arrayOf("run"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `status command requires run id`() {
        val exitCode = ProductFactoryCli.run(arrayOf("status"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `intent command validates variants range`() {
        val exitCode = ProductFactoryCli.run(
            arrayOf(
                "intent",
                "--query",
                "test",
                "--variants",
                "9",
            ),
        )
        assertEquals(2, exitCode)
    }

    @Test
    fun `pf contract drift guard requires contracts dir`() {
        val exitCode = ProductFactoryCli.run(arrayOf("pf", "contract-drift-guard"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `slo-gate command requires quality profile`() {
        val exitCode = ProductFactoryCli.run(arrayOf("slo-gate"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `slo-gate command returns success for disabled gate`() {
        val qualityProfile = Files.createTempFile("quality-profile", ".yaml")
        qualityProfile.toFile().writeText(
            """
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
              enabled: false
              maxGenerationTimeSeconds: 900
              maxPolicyDenySharePercent: 10
              minSuccessRatePercent: 95
            """.trimIndent(),
        )

        val exitCode = ProductFactoryCli.run(
            arrayOf(
                "slo-gate",
                "--quality-profile",
                qualityProfile.toString(),
                "--audit-log",
                Files.createTempFile("audit", ".log").toString(),
            ),
        )
        assertEquals(0, exitCode)
    }

    @Test
    fun `adr-draft command requires run id`() {
        val exitCode = ProductFactoryCli.run(arrayOf("adr-draft"))
        assertEquals(2, exitCode)
    }

    @Test
    fun `adr-draft command generates markdown from audit and approvals`() {
        val tempDir = Files.createTempDirectory("adr-draft-cli")
        val auditPath = tempDir.resolve("audit.log")
        val approvalsDir = tempDir.resolve("approvals")
        val outputPath = tempDir.resolve("adr-output.md")
        val riskProfile = tempDir.resolve("risk_profile.yaml")

        val runId = "run-adr-cli"
        val events = listOf(
            AuditEvent(
                timestamp = "2026-01-01T00:00:00Z",
                runId = runId,
                eventType = "pipeline_plan",
                payload = """{"type":"pipeline_plan","steps":[{"id":"generate_repo","description":"Generate repository","tool":"create_repo_from_archetype","depends_on":[]}]}""",
            ),
            AuditEvent(
                timestamp = "2026-01-01T00:00:02Z",
                runId = runId,
                eventType = "adr_draft",
                payload = """{"type":"adr_draft","context":"CLI sprint context","decision":"Use generated ADR post-step","consequences":["Traceable decisions"]}""",
            ),
            AuditEvent(
                timestamp = "2026-01-01T00:00:03Z",
                runId = runId,
                eventType = "approval_required",
                payload = """{"runId":"run-adr-cli","proposed_actions":["risk_tier:high","tool:create_github_repo"]}""",
            ),
            AuditEvent(
                timestamp = "2026-01-01T00:00:05Z",
                runId = runId,
                eventType = "state_changed",
                payload = """{"runId":"run-adr-cli","newState":"DONE"}""",
            ),
        )
        val encoded = events.joinToString(separator = "\n") { Json.encodeToString(it) } + "\n"
        auditPath.toFile().writeText(encoded)

        val approvals = FileApprovalStore(approvalsDir)
        approvals.upsertPending(
            runId = runId,
            reason = "manual approval required for high-risk tool create_github_repo",
            proposedActions = listOf("risk_tier:high", "tool:create_github_repo"),
        )
        approvals.approve(runId = runId, decidedBy = "operator", comment = "approved")

        riskProfile.toFile().writeText(
            """
            apiVersion: productfactory.io/v1
            kind: RiskProfile
            riskTier: high
            agentPolicy:
              approvalsRequiredFor:
                - write_repository
              budgets:
                maxLlmtokensPerRun: 50000
                maxToolCallsPerRun: 20
                maxWallClockSecondsPerRun: 600
            threatModel:
              llmTop10Focus:
                - prompt_injection
            """.trimIndent(),
        )

        val exitCode = ProductFactoryCli.run(
            arrayOf(
                "adr-draft",
                "--run-id",
                runId,
                "--audit-log",
                auditPath.toString(),
                "--approvals-dir",
                approvalsDir.toString(),
                "--output",
                outputPath.toString(),
                "--risk-profile",
                riskProfile.toString(),
            ),
        )

        assertEquals(0, exitCode)
        assertTrue(Files.isRegularFile(outputPath))
        val content = outputPath.toFile().readText()
        assertTrue(content.contains("# ADR-AUTO-run-adr-cli"))
        assertTrue(content.contains("## Контекст"))
        assertTrue(content.contains("## Риски и контроль"))
        assertTrue(content.contains("risk-register.md"))
        assertTrue(content.contains("Approval status: `APPROVED`"))
    }
}
