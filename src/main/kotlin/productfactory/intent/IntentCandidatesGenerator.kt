package productfactory.intent

/**
 * Stub generator for Phase A: produce 3-7 candidate result descriptions from free-form intent.
 */
open class IntentCandidatesGenerator {

    open fun generate(intent: String): List<String> {
        val normalizedIntent = intent.trim().ifBlank { "unspecified intent" }
        return listOf(
            "Candidate 1: conservative baseline for \"$normalizedIntent\" with minimum risk and clear rollback.",
            "Candidate 2: balanced implementation for \"$normalizedIntent\" with moderate scope and strong quality checks.",
            "Candidate 3: accelerated version of \"$normalizedIntent\" focused on fastest time-to-first-value.",
        )
    }
}
