package productfactory.cli

import productfactory.api.FactoryRunRequest
import productfactory.contracts.ContractValidationCli
import productfactory.contracts.ContractDriftGuardCli
import productfactory.rag.RagCli
import productfactory.workflow.FileAuditLog
import productfactory.workflow.FileApprovalStore
import productfactory.workflow.SloGate

object ProductFactoryCli {
    private val usage = """
Usage:
  product-factory validate <contracts-dir>
  product-factory pf validate <contracts-dir>
  product-factory contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>]
  product-factory pf contract-drift-guard <contracts-dir> [--format text|json] [--report <output-file>]
  product-factory approve <runId> [comment]
  product-factory reject <runId> [comment]
  product-factory approval-status <runId>
  product-factory run --goal "<text>" [--constraint "<text>"] [--target-stack <id>] [--url <http://localhost:9080>] [--tenant <id>]
  product-factory status <runId> [--url <http://localhost:9080>] [--tenant <id>]
  product-factory intent --query "<text>" [--risk-tier low|medium|high] [--variants 3..7] [--pick <n>] [--run] [--target-stack <id>] [--url <http://localhost:9080>] [--tenant <id>]
  product-factory slo-gate --quality-profile <path> [--run-id <runId>] [--audit-log <path>] [--generation-time-seconds <seconds>]
  product-factory adr-draft --run-id <runId> [--audit-log <path>] [--approvals-dir <path>] [--output <path>] [--risk-profile <path>]
  product-factory rag ingest|status|activate <versionId?>
""".trimIndent()

    fun run(args: Array<String>): Int? {
        if (args.isEmpty()) {
            return null
        }

        return when (args[0]) {
            "validate" -> ContractValidationCli.run(args)
            "pf" -> runPfSubcommand(args)
            "contract-drift-guard" -> ContractDriftGuardCli.run(args)
            "approve" -> decide(args, approve = true)
            "reject" -> decide(args, approve = false)
            "approval-status" -> status(args)
            "run" -> FactoryConsumerCli.runCommand(args)
            "status" -> FactoryConsumerCli.statusCommand(args)
            "intent" -> FactoryConsumerCli.intentCommand(args)
            "slo-gate" -> runSloGate(args)
            "adr-draft" -> AdrDraftCli.run(args)
            "rag" -> RagCli.run(args)
            else -> {
                System.err.println(usage)
                2
            }
        }
    }

    private fun runPfSubcommand(args: Array<String>): Int {
        return when (args.getOrNull(1)) {
            "validate" -> ContractValidationCli.run(args)
            "contract-drift-guard" -> ContractDriftGuardCli.run(args)
            "adr-draft" -> AdrDraftCli.run(args.copyOfRange(1, args.size))
            else -> {
                System.err.println(usage)
                2
            }
        }
    }

    private fun decide(args: Array<String>, approve: Boolean): Int {
        val runId = args.getOrNull(1)
        if (runId.isNullOrBlank()) {
            System.err.println(usage)
            return 2
        }
        val comment = args.drop(2).joinToString(" ").trim().ifEmpty { null }
        val operator = System.getenv("USER") ?: "operator"
        val store = FileApprovalStore()

        val result = if (approve) {
            store.approve(runId = runId, decidedBy = operator, comment = comment)
        } else {
            store.reject(runId = runId, decidedBy = operator, comment = comment)
        }

        if (result == null) {
            System.err.println("Approval request for runId=$runId was not found")
            return 1
        }

        val action = if (approve) "approved" else "rejected"
        println("Approval $action for runId=$runId by $operator")
        return 0
    }

    private fun status(args: Array<String>): Int {
        val runId = args.getOrNull(1)
        if (runId.isNullOrBlank()) {
            System.err.println(usage)
            return 2
        }

        val record = FileApprovalStore().get(runId)
        if (record == null) {
            System.err.println("Approval request for runId=$runId was not found")
            return 1
        }

        println(
            "runId=${record.runId} status=${record.status} timestamp=${record.timestamp} reason=${record.reason} proposed_actions=${record.proposedActions}",
        )
        return 0
    }

    private fun runSloGate(args: Array<String>): Int {
        val qualityProfilePath = findOption(args, "--quality-profile")
        if (qualityProfilePath.isNullOrBlank()) {
            System.err.println(usage)
            return 2
        }
        val runId = findOption(args, "--run-id")?.takeIf { it.isNotBlank() } ?: "slo-gate-run"
        val auditPath = findOption(args, "--audit-log")?.takeIf { it.isNotBlank() } ?: (System.getenv("AUDIT_LOG_PATH") ?: "audit.log")
        val generationTimeSeconds = findOption(args, "--generation-time-seconds")?.toDoubleOrNull() ?: 0.0
        val qualityProfileYaml = runCatching { java.nio.file.Path.of(qualityProfilePath).toFile().readText() }.getOrNull()
        if (qualityProfileYaml == null) {
            System.err.println("Unable to read quality profile: $qualityProfilePath")
            return 2
        }

        val evaluation = SloGate(auditLog = FileAuditLog(auditPath), auditLogPath = auditPath)
            .evaluate(
                request = FactoryRunRequest(
                    goal = "slo-gate-cli:$runId",
                    contracts = mapOf("quality_profile" to qualityProfileYaml),
                ),
                generationTimeMs = (generationTimeSeconds * 1000.0).toLong(),
            )
        val verdict = evaluation.verdict
        println("slo_gate verdict=${verdict.status} reason=${verdict.reasonCode} message=${verdict.message}")
        evaluation.metrics?.let { metrics ->
            println(
                "slo_gate metrics generation_time_seconds=${"%.2f".format(metrics.generationTimeSeconds)} policy_deny_share_percent=${"%.2f".format(metrics.policyDenySharePercent)} success_rate_percent=${"%.2f".format(metrics.successRatePercent)} lookback_runs_evaluated=${metrics.lookbackRunsEvaluated}",
            )
        }
        return if (verdict.isFailure()) 1 else 0
    }

    private fun findOption(args: Array<String>, key: String): String? {
        val index = args.indexOf(key)
        if (index < 0) return null
        return args.getOrNull(index + 1)
    }
}
