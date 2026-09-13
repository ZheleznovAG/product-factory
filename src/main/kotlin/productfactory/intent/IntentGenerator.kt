package productfactory.intent

/**
 * Draft Intent model aligned with contracts/schemas/intent.schema.json.
 * This is a deterministic stub: no LLM calls and no side effects.
 */
data class IntentDraft(
    val apiVersion: String = "productfactory.io/v1",
    val kind: String = "IntentSpec",
    val outcome: IntentOutcome,
    val experience: IntentExperience,
    val constraints: IntentConstraints,
    val confidence: Double? = null,
    val referenceIds: List<String> = emptyList(),
)

data class IntentOutcome(
    val goal: String,
    val result: String,
    val successSignals: List<String> = emptyList(),
)

data class IntentExperience(
    val feeling: String,
    val interactionStyle: String,
    val tempo: String = "balanced",
    val intensity: String = "medium",
)

data class IntentConstraints(
    val mustHave: List<String>,
    val mustNot: List<String> = emptyList(),
    val maxSessionMinutes: Int? = 90,
    val budgetUsdMax: Double? = null,
)

open class IntentGenerator {
    open fun generate(goal: String, constraints: List<String>): IntentDraft {
        val normalizedGoal = goal.trim().ifBlank { "unspecified goal" }
        val normalizedConstraints = constraints.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val mustHave = if (normalizedConstraints.isEmpty()) {
            listOf("Clarify must-have constraints via 5 A/B protocol")
        } else {
            normalizedConstraints
        }

        return IntentDraft(
            outcome = IntentOutcome(
                goal = normalizedGoal,
                result = "Produce deployable first iteration for: $normalizedGoal",
                successSignals = listOf(
                    "User confirms intent draft reflects desired outcome",
                    "Plan can be generated without unresolved blockers",
                ),
            ),
            experience = IntentExperience(
                feeling = "Clear progress with controlled risk",
                interactionStyle = "5 A/B clarification protocol",
                tempo = inferTempo(normalizedGoal, normalizedConstraints),
                intensity = "medium",
            ),
            constraints = IntentConstraints(
                mustHave = mustHave,
                mustNot = emptyList(),
                maxSessionMinutes = 90,
                budgetUsdMax = null,
            ),
            confidence = 0.4,
            referenceIds = emptyList(),
        )
    }

    private fun inferTempo(goal: String, constraints: List<String>): String {
        val text = buildString {
            append(goal.lowercase())
            append(" ")
            append(constraints.joinToString(" ").lowercase())
        }
        return when {
            text.contains("urgent") || text.contains("asap") || text.contains("fast") -> "fast"
            text.contains("careful") || text.contains("stable") || text.contains("safety") -> "slow"
            else -> "balanced"
        }
    }
}
