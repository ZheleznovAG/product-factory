package productfactory.workflow

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.api.FactoryRunRequest

data class GeneratedAdrDraft(
    val path: Path,
    val title: String,
)

interface SprintAdrDraftGenerator {
    fun generate(runId: String, request: FactoryRunRequest, outputPath: Path? = null): GeneratedAdrDraft?
}

object NoopSprintAdrDraftGenerator : SprintAdrDraftGenerator {
    override fun generate(runId: String, request: FactoryRunRequest, outputPath: Path?): GeneratedAdrDraft? = null
}

class TemplateSprintAdrDraftGenerator(
    private val auditLog: AuditLog,
    private val approvalStore: ApprovalStore,
    private val templatePath: Path = Path.of("docs", "adr", "template.md"),
    private val outputDirectory: Path = Path.of("docs", "adr", "generated"),
    private val riskRegisterPath: Path = Path.of("docs", "risk-register.md"),
    private val approvalPolicyPath: Path = Path.of("docs", "approval-policy.md"),
    private val clock: Clock = Clock.systemUTC(),
) : SprintAdrDraftGenerator {
    private val json = Json { ignoreUnknownKeys = true }
    private val yamlMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override fun generate(runId: String, request: FactoryRunRequest, outputPath: Path?): GeneratedAdrDraft? {
        val events = auditLog.recentEvents(runId = runId, limit = 1_000)
        if (events.isEmpty()) return null

        val adrDraft = events.lastPayload("adr_draft")
        val pipelinePlan = events.lastPayload("pipeline_plan")
        val approvalRecord = approvalStore.get(runId)
        val riskProfile = parseRiskProfile(request.contracts)

        val decisionText = adrDraft?.get("decision")?.jsonPrimitive?.contentOrNull
            ?: "Спринт выполнен с policy-gated workflow и proposals из planner/codegen."
        val contextText = adrDraft?.get("context")?.jsonPrimitive?.contentOrNull
            ?: "Результат автогенерации из audit событий run `$runId`."
        val consequences = adrDraft?.get("consequences")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val planSteps = pipelinePlan?.get("steps")
            ?.jsonArray
            ?.mapNotNull { step ->
                val obj = step.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                if (description.isBlank()) id else "$id: $description"
            }
            .orEmpty()
        val deniedTools = events.filter { it.eventType == "tool_call_denied" }
            .mapNotNull { event ->
                val payload = event.payload.parseJsonObjectOrNull() ?: return@mapNotNull null
                val tool = payload["toolName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val reason = payload["reason"]?.jsonPrimitive?.contentOrNull ?: "denied by policy"
                "$tool ($reason)"
            }
        val approvalEvents = events.filter { it.eventType == "approval_required" }
        val proposedRiskTiers = approvalEvents.flatMap { event ->
            val payload = event.payload.parseJsonObjectOrNull() ?: return@flatMap emptyList<String>()
            payload["proposed_actions"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.mapNotNull { action ->
                    if (action.startsWith("risk_tier:")) action.removePrefix("risk_tier:").ifBlank { null } else null
                }
                .orEmpty()
        }.distinct()

        val finalState = events
            .filter { it.eventType == "state_changed" }
            .mapNotNull { it.payload.parseJsonObjectOrNull()?.get("newState")?.jsonPrimitive?.contentOrNull }
            .lastOrNull()
            ?: "UNKNOWN"

        val testOutcome = when {
            events.any { it.eventType == "tests_failed" } -> "tests_failed"
            events.any { it.eventType == "tests_passed" } -> "tests_passed"
            else -> "tests_skipped"
        }
        val securityOutcome = when {
            events.any { it.eventType == "security_checks_failed" } -> "security_checks_failed"
            events.any { it.eventType == "security_checks_passed" } -> "security_checks_passed"
            else -> "security_checks_skipped"
        }
        val sloOutcome = events.lastPayload("slo_gate_evaluated")
            ?.get("verdict")
            ?.jsonObject
            ?.get("status")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: "SKIPPED"

        val adrDate = LocalDate.now(clock).toString()
        val runSlug = sanitizeRunId(runId)
        val title = "ADR-AUTO-$runSlug: Sprint run $runId decision draft"
        val destination = (outputPath ?: outputDirectory.resolve("adr-auto-$adrDate-$runSlug.md")).toAbsolutePath().normalize()
        destination.parent?.let { Files.createDirectories(it) }
        val markdown = renderMarkdown(
            title = title,
            date = adrDate,
            context = contextText,
            decision = decisionText,
            planSteps = planSteps,
            alternatives = deniedTools,
            consequences = consequences,
            runId = runId,
            finalState = finalState,
            testOutcome = testOutcome,
            securityOutcome = securityOutcome,
            sloOutcome = sloOutcome,
            approvalRecord = approvalRecord,
            approvalEventsCount = approvalEvents.size,
            riskProfile = riskProfile,
            proposedRiskTiers = proposedRiskTiers,
            destination = destination,
        )
        Files.writeString(destination, markdown)
        return GeneratedAdrDraft(path = destination, title = title)
    }

    private fun renderMarkdown(
        title: String,
        date: String,
        context: String,
        decision: String,
        planSteps: List<String>,
        alternatives: List<String>,
        consequences: List<String>,
        runId: String,
        finalState: String,
        testOutcome: String,
        securityOutcome: String,
        sloOutcome: String,
        approvalRecord: ApprovalRecord?,
        approvalEventsCount: Int,
        riskProfile: RiskProfileContract?,
        proposedRiskTiers: List<String>,
        destination: Path,
    ): String {
        val templateNote = if (Files.exists(templatePath)) {
            "Template: [docs/adr/template.md](${toRelativeLink(destination, templatePath)})"
        } else {
            "Template: docs/adr/template.md (not found at generation time)"
        }
        val riskRegisterLink = "[docs/risk-register.md](${toRelativeLink(destination, riskRegisterPath)})"
        val approvalPolicyLink = "[docs/approval-policy.md](${toRelativeLink(destination, approvalPolicyPath)})"
        val tier = riskProfile?.riskTier ?: proposedRiskTiers.firstOrNull() ?: "unknown"
        val approvalStatus = approvalRecord?.status?.name ?: if (approvalEventsCount > 0) "PENDING" else "NOT_REQUIRED"
        val approvalReason = approvalRecord?.reason ?: "approval not requested in this run"
        val approvalActions = approvalRecord?.proposedActions?.joinToString(", ").orEmpty()
        val approver = approvalRecord?.decidedBy?.takeIf { it.isNotBlank() } ?: "n/a"

        val alternativesSection = if (alternatives.isEmpty()) {
            "- Оставить текущий workflow без изменений: отклонено, не фиксирует sprint-specific решения в ADR.\n" +
                "- Делать ADR вручную после каждого прогона: отклонено, теряется traceability с audit/approvals."
        } else {
            alternatives.joinToString("\n") { "- Ограничить/исключить `${it.substringBefore(" (")}`: отклонено policy во время run." }
        }

        val consequencesSection = buildList {
            addAll(consequences.map { "- $it" })
            add("- Run `$runId` завершён в состоянии `$finalState`.")
            add("- Quality outcomes: tests=`$testOutcome`, security=`$securityOutcome`, slo=`$sloOutcome`.")
        }.joinToString("\n")

        val planSection = if (planSteps.isEmpty()) {
            "- План из sprint audit недоступен (pipeline_plan отсутствует в окне событий)."
        } else {
            planSteps.joinToString("\n") { "- $it" }
        }

        val riskFocus = riskProfile?.threatModel?.llmTop10Focus?.joinToString(", ").orEmpty().ifBlank { "not specified" }
        val riskApprovals = riskProfile?.agentPolicy?.approvalsRequiredFor?.joinToString(", ").orEmpty().ifBlank { "not specified" }
        val budgets = riskProfile?.agentPolicy?.budgets
        val budgetSummary = if (budgets == null) {
            "not specified"
        } else {
            "maxLlmtokensPerRun=${budgets.maxLlmtokensPerRun}, maxToolCallsPerRun=${budgets.maxToolCallsPerRun}, maxWallClockSecondsPerRun=${budgets.maxWallClockSecondsPerRun}"
        }

        return """
# $title

**Дата:** $date  
**Статус:** proposed  
**Контекст:** Автогенерация из sprint run `$runId` с привязкой к risk/approval артефактам.

## Контекст

$context

- Источник рисков: $riskRegisterLink
- Политика approvals: $approvalPolicyLink
- $templateNote

## Решение

$decision

План шага sprint (по `pipeline_plan`):
$planSection

### Инварианты (если применимо)

- Side-effects остаются в Tool Executor; агент формирует proposals.
- Policy/approval решения фиксируются в audit и используются для контроля high-risk действий.

## Альтернативы

$alternativesSection

## Риски и контроль

- Risk tier: `$tier`
- threatModel.llmTop10Focus: `$riskFocus`
- agentPolicy.approvalsRequiredFor: `$riskApprovals`
- agentPolicy.budgets: `$budgetSummary`
- Approval status: `$approvalStatus` (events: $approvalEventsCount)
- Approval reason: $approvalReason
- Approval proposed actions: ${if (approvalActions.isBlank()) "n/a" else approvalActions}
- Decided by: $approver

## Последствия

$consequencesSection

## Триггеры пересмотра

- Изменение risk tier или policy matrix в `risk_profile`/OPA.
- Появление новых privileged tools или изменение approval-процесса.
- Повторные отказы quality/security/SLO gate в спринтах.

---

*Generated by Product Factory sprint ADR autogenerator.*
""".trimIndent()
    }

    private fun parseRiskProfile(contracts: Map<String, String>): RiskProfileContract? {
        val raw = contracts["risk_profile"]
            ?: contracts["risk_profile.yaml"]
            ?: contracts["RiskProfile"]
            ?: return null
        return runCatching { yamlMapper.readValue<RiskProfileContract>(raw) }.getOrNull()
    }

    private fun toRelativeLink(fromFile: Path, targetFile: Path): String {
        val fromDir = fromFile.parent?.toAbsolutePath()?.normalize() ?: Path.of(".").toAbsolutePath().normalize()
        val target = targetFile.toAbsolutePath().normalize()
        return runCatching { fromDir.relativize(target).toString().replace('\\', '/') }
            .getOrElse { target.toString().replace('\\', '/') }
    }

    private fun sanitizeRunId(runId: String): String = runId
        .lowercase()
        .replace(Regex("[^a-z0-9._-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
        .ifBlank { "run" }
}

private fun String.parseJsonObjectOrNull(): JsonObject? =
    runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()

private fun List<AuditEvent>.lastPayload(eventType: String): JsonObject? =
    asReversed().firstOrNull { it.eventType == eventType }?.payload?.parseJsonObjectOrNull()

private data class RiskProfileContract(
    val riskTier: String? = null,
    val agentPolicy: RiskAgentPolicy? = null,
    val threatModel: RiskThreatModel? = null,
)

private data class RiskAgentPolicy(
    val approvalsRequiredFor: List<String> = emptyList(),
    val budgets: RiskAgentBudgets? = null,
)

private data class RiskAgentBudgets(
    val maxLlmtokensPerRun: Int? = null,
    val maxToolCallsPerRun: Int? = null,
    val maxWallClockSecondsPerRun: Int? = null,
)

private data class RiskThreatModel(
    val llmTop10Focus: List<String> = emptyList(),
)
