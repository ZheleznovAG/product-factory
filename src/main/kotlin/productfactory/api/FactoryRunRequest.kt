package productfactory.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Product request: goal and constraints for one factory run.
 */
@Serializable
data class FactoryRunRequest(
    val tenantId: String? = null,
    val goal: String,
    val constraints: List<String> = emptyList(),
    val productSpec: String? = null,
    val contracts: Map<String, String> = emptyMap(),
    @SerialName("target_stack")
    val targetStack: String? = null,
    @SerialName("dry_run")
    val dryRun: Boolean = false,
    val budget: RunBudget? = null,
)

@Serializable
data class RunBudget(
    @SerialName("token_budget")
    val tokenBudget: Int? = null,
    @SerialName("tool_calls_budget")
    val toolCallsBudget: Int? = null,
    @SerialName("wall_clock_seconds")
    val wallClockSeconds: Int? = null,
)

@Serializable
data class FactoryRunResponse(
    val runId: String,
    val tenantId: String,
    val status: String,
    val message: String,
)
