package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import productfactory.workflow.tools.ToolCallRequest

/**
 * Результаты шагов workflow для передачи между шагами (в т.ч. в Temporal activities).
 * Все DTO сериализуемы для durable execution.
 */
@Serializable
data class PlanStepResult(
    val tokenUsage: Int = 0,
)

@Serializable
data class GenerateStepResult(
    val tokenUsage: Int,
    val toolCallsToExecute: List<ToolCallRequest>,
)

@Serializable
data class ToolStepResult(
    val repoUrl: String? = null,
    val artifactLocation: String? = null,
    /** Реальная версия SBOM (например из Syft/CI); при null в реестре пишется placeholder. */
    val sbomVersion: String? = null,
    /** Реальная версия подписи (например Cosign); при null в реестре пишется placeholder. */
    val signatureVersion: String? = null,
    /** Список idempotency key выполненных tool call для evidence в artifact manifest. */
    val executedToolCallIds: List<String> = emptyList(),
    /** True when workflow executed in dry-run mode (tool side-effects were not executed). */
    val dryRun: Boolean = false,
    /** Detailed tool execution plan produced during dry-run. */
    val dryRunPlan: List<DryRunToolCallPlanItem> = emptyList(),
)

@Serializable
data class DryRunToolCallPlanItem(
    val toolName: String,
    val idempotencyKey: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val policy: DryRunPolicyOutcome,
    val approval: DryRunApprovalOutcome? = null,
    val wouldExecute: Boolean,
)

@Serializable
data class DryRunPolicyOutcome(
    val allowed: Boolean,
    val requireHumanApproval: Boolean,
    val source: String,
    val reason: String,
    val decision: String? = null,
)

@Serializable
data class DryRunApprovalOutcome(
    val status: String,
    val reason: String? = null,
)
