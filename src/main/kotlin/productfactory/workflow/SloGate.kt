package productfactory.workflow

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DEFAULT_SLO_LOOKBACK_RUNS = 20

data class SloGateMetrics(
    val generationTimeSeconds: Double,
    val policyDenySharePercent: Double,
    val successRatePercent: Double,
    val lookbackRunsEvaluated: Int,
)

data class SloGateThresholds(
    val maxGenerationTimeSeconds: Double,
    val maxPolicyDenySharePercent: Double,
    val minSuccessRatePercent: Double,
    val lookbackRuns: Int,
)

data class SloGateEvaluation(
    val enabled: Boolean,
    val verdict: GateVerdict,
    val metrics: SloGateMetrics?,
    val thresholds: SloGateThresholds?,
)

class SloGate(
    private val auditLog: AuditLog,
    private val auditLogPath: String? = null,
) {
    private val yamlMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun evaluate(request: productfactory.api.FactoryRunRequest, generationTimeMs: Long): SloGateEvaluation {
        val qualityProfileRaw = request.contracts["quality_profile"]
            ?: request.contracts["quality_profile.yaml"]
            ?: request.contracts["QualityProfile"]
            ?: return SloGateEvaluation(
                enabled = false,
                verdict = GateVerdict(
                    status = VerdictStatus.WARN,
                    reasonCode = VerdictReason.SLO_GATE_SKIPPED,
                    message = "SLO gate skipped: quality_profile contract is not provided",
                ),
                metrics = null,
                thresholds = null,
            )

        val qualityProfile = runCatching { yamlMapper.readValue<QualityProfileSloGateContract>(qualityProfileRaw) }.getOrNull()
            ?: return SloGateEvaluation(
                enabled = false,
                verdict = GateVerdict(
                    status = VerdictStatus.WARN,
                    reasonCode = VerdictReason.SLO_GATE_SKIPPED,
                    message = "SLO gate skipped: unable to parse quality_profile",
                ),
                metrics = null,
                thresholds = null,
            )

        val sloGate = qualityProfile.sloGate
            ?: return SloGateEvaluation(
                enabled = false,
                verdict = GateVerdict(
                    status = VerdictStatus.WARN,
                    reasonCode = VerdictReason.SLO_GATE_SKIPPED,
                    message = "SLO gate skipped: sloGate section is not configured in quality_profile",
                ),
                metrics = null,
                thresholds = null,
            )

        if (!sloGate.enabled) {
            return SloGateEvaluation(
                enabled = false,
                verdict = GateVerdict(
                    status = VerdictStatus.WARN,
                    reasonCode = VerdictReason.SLO_GATE_SKIPPED,
                    message = "SLO gate skipped: disabled in quality_profile",
                ),
                metrics = null,
                thresholds = null,
            )
        }

        val thresholds = SloGateThresholds(
            maxGenerationTimeSeconds = sloGate.maxGenerationTimeSeconds,
            maxPolicyDenySharePercent = sloGate.maxPolicyDenySharePercent,
            minSuccessRatePercent = sloGate.minSuccessRatePercent,
            lookbackRuns = (sloGate.lookbackRuns ?: DEFAULT_SLO_LOOKBACK_RUNS).coerceAtLeast(1),
        )
        val metrics = computeMetrics(generationTimeMs, thresholds.lookbackRuns)
        val failures = mutableListOf<String>()

        if (metrics.generationTimeSeconds > thresholds.maxGenerationTimeSeconds) {
            failures += "generation_time_seconds=${fmt(metrics.generationTimeSeconds)} > max=${fmt(thresholds.maxGenerationTimeSeconds)}"
        }
        if (metrics.policyDenySharePercent > thresholds.maxPolicyDenySharePercent) {
            failures += "policy_deny_share_percent=${fmt(metrics.policyDenySharePercent)} > max=${fmt(thresholds.maxPolicyDenySharePercent)}"
        }
        if (metrics.successRatePercent < thresholds.minSuccessRatePercent) {
            failures += "success_rate_percent=${fmt(metrics.successRatePercent)} < min=${fmt(thresholds.minSuccessRatePercent)}"
        }

        val verdict = if (failures.isEmpty()) {
            GateVerdict(
                status = VerdictStatus.PASS,
                reasonCode = VerdictReason.SLO_GATE_PASSED,
                message = "SLO gate passed",
            )
        } else {
            GateVerdict(
                status = VerdictStatus.FAIL,
                reasonCode = VerdictReason.SLO_GATE_FAILED,
                message = "SLO gate failed: ${failures.joinToString("; ")}",
            )
        }

        return SloGateEvaluation(
            enabled = true,
            verdict = verdict,
            metrics = metrics,
            thresholds = thresholds,
        )
    }

    private fun computeMetrics(generationTimeMs: Long, lookbackRuns: Int): SloGateMetrics {
        val generationTimeSeconds = generationTimeMs.coerceAtLeast(0).toDouble() / 1000.0
        val auditFilePath = resolveAuditPath() ?: return SloGateMetrics(
            generationTimeSeconds = generationTimeSeconds,
            policyDenySharePercent = 0.0,
            successRatePercent = 100.0,
            lookbackRunsEvaluated = 0,
        )
        val events = File(auditFilePath)
            .takeIf { it.isFile }
            ?.readLines()
            .orEmpty()
            .mapNotNull { line -> runCatching { Json.decodeFromString(AuditEvent.serializer(), line) }.getOrNull() }

        val terminalByRunId = linkedMapOf<String, String>()
        events.forEach { event ->
            if (event.eventType != "state_changed") return@forEach
            val payload = runCatching { Json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: return@forEach
            val state = payload["newState"]?.jsonPrimitive?.content ?: return@forEach
            if (state == WorkflowState.DONE.name || state == WorkflowState.FAILED.name) {
                terminalByRunId[event.runId] = state
            }
        }

        val selectedRuns = terminalByRunId.keys.toList().takeLast(lookbackRuns).toSet()
        val terminals = terminalByRunId.filterKeys { it in selectedRuns }
        val doneCount = terminals.values.count { it == WorkflowState.DONE.name }
        val successRatePercent = if (terminals.isEmpty()) 100.0 else doneCount.toDouble() * 100.0 / terminals.size.toDouble()

        var decisionCount = 0
        var denyCount = 0
        events.forEach { event ->
            if (event.runId !in selectedRuns) return@forEach
            when {
                event.eventType.startsWith("policy_check") -> {
                    decisionCount += 1
                    val payload = runCatching { Json.parseToJsonElement(event.payload).jsonObject }.getOrNull()
                    val allowed = payload?.get("allowed")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                    if (allowed == false) denyCount += 1
                }
                event.eventType == "tool_call_denied" -> {
                    decisionCount += 1
                    denyCount += 1
                }
                event.eventType == "tool_call_executed" -> {
                    decisionCount += 1
                }
            }
        }
        val denySharePercent = if (decisionCount == 0) 0.0 else denyCount.toDouble() * 100.0 / decisionCount.toDouble()

        return SloGateMetrics(
            generationTimeSeconds = generationTimeSeconds,
            policyDenySharePercent = denySharePercent,
            successRatePercent = successRatePercent,
            lookbackRunsEvaluated = terminals.size,
        )
    }

    private fun resolveAuditPath(): String? {
        if (!auditLogPath.isNullOrBlank()) return auditLogPath
        if (auditLog is FileAuditLog) return auditLog.path
        return System.getenv("AUDIT_LOG_PATH")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun fmt(value: Double): String = "%.2f".format(value)
}

private data class QualityProfileSloGateContract(
    val sloGate: QualityProfileSloGateSection? = null,
)

private data class QualityProfileSloGateSection(
    val enabled: Boolean = false,
    val maxGenerationTimeSeconds: Double = Double.POSITIVE_INFINITY,
    val maxPolicyDenySharePercent: Double = 100.0,
    val minSuccessRatePercent: Double = 0.0,
    val lookbackRuns: Int? = null,
)
