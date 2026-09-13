package productfactory.cli

import java.nio.file.Path
import productfactory.api.FactoryRunRequest
import productfactory.workflow.FileApprovalStore
import productfactory.workflow.FileAuditLog
import productfactory.workflow.TemplateSprintAdrDraftGenerator

object AdrDraftCli {
    fun run(args: Array<String>): Int {
        val runId = findOption(args, "--run-id")
            ?.takeIf { it.isNotBlank() }
            ?: args.getOrNull(1)?.takeIf { !it.startsWith("--") && it.isNotBlank() }
        if (runId.isNullOrBlank()) {
            System.err.println("Usage: product-factory adr-draft --run-id <runId> [--audit-log <path>] [--approvals-dir <path>] [--output <path>] [--risk-profile <path>]")
            return 2
        }

        val auditPath = findOption(args, "--audit-log")?.takeIf { it.isNotBlank() }
            ?: (System.getenv("AUDIT_LOG_PATH") ?: "audit.log")
        val approvalsDir = findOption(args, "--approvals-dir")?.takeIf { it.isNotBlank() }
            ?: (System.getenv("APPROVALS_DIR") ?: "approvals")
        val outputPath = findOption(args, "--output")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val templatePath = findOption(args, "--template")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of("docs", "adr", "template.md")
        val riskRegisterPath = findOption(args, "--risk-register")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of("docs", "risk-register.md")
        val approvalPolicyPath = findOption(args, "--approval-policy")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of("docs", "approval-policy.md")
        val riskProfile = findOption(args, "--risk-profile")?.takeIf { it.isNotBlank() }?.let { riskProfilePath ->
            runCatching { Path.of(riskProfilePath).toFile().readText() }.getOrNull()
        }

        val auditLog = FileAuditLog(auditPath)
        val generator = TemplateSprintAdrDraftGenerator(
            auditLog = auditLog,
            approvalStore = FileApprovalStore(Path.of(approvalsDir)),
            templatePath = templatePath,
            outputDirectory = outputPath?.parent ?: Path.of("docs", "adr", "generated"),
            riskRegisterPath = riskRegisterPath,
            approvalPolicyPath = approvalPolicyPath,
        )
        val request = FactoryRunRequest(
            goal = "adr-draft-cli:$runId",
            contracts = buildMap {
                if (!riskProfile.isNullOrBlank()) {
                    put("risk_profile", riskProfile)
                }
            },
        )
        val generated = generator.generate(runId = runId, request = request, outputPath = outputPath)
        if (generated == null) {
            System.err.println("No audit events found for runId=$runId in $auditPath")
            return 1
        }

        println("ADR draft generated: ${generated.path}")
        return 0
    }

    private fun findOption(args: Array<String>, key: String): String? {
        val index = args.indexOf(key)
        if (index < 0) return null
        return args.getOrNull(index + 1)
    }
}
