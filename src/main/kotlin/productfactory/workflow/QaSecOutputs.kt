package productfactory.workflow

import kotlinx.serialization.Serializable

/**
 * Контракт выхода «роли» QA: результат тестового гейта (verdict + опциональные детали).
 * Соответствует этапу run_tests; в будущем может заполняться QA-агентом.
 */
@Serializable
data class QaGateOutput(
    val verdict: GateVerdictSerializable,
    val summary: String? = null,
    val artifactRefs: List<String> = emptyList(),
) {
    constructor(verdict: GateVerdict, summary: String? = null, artifactRefs: List<String> = emptyList()) : this(
        verdict = GateVerdictSerializable.from(verdict),
        summary = summary,
        artifactRefs = artifactRefs,
    )

    fun toVerdict(): GateVerdict = verdict.toVerdict()
}

/**
 * Контракт выхода «роли» Security: результат security-гейта (verdict + опциональные детали).
 * Соответствует этапу run_security_checks; в будущем может заполняться Security-агентом.
 */
@Serializable
data class SecGateOutput(
    val verdict: GateVerdictSerializable,
    val summary: String? = null,
    val artifactRefs: List<String> = emptyList(),
) {
    constructor(verdict: GateVerdict, summary: String? = null, artifactRefs: List<String> = emptyList()) : this(
        verdict = GateVerdictSerializable.from(verdict),
        summary = summary,
        artifactRefs = artifactRefs,
    )

    fun toVerdict(): GateVerdict = verdict.toVerdict()
}

/** Сериализуемое представление GateVerdict для контрактов и RunContext. */
@Serializable
data class GateVerdictSerializable(
    val status: String,
    val reasonCode: String,
    val message: String,
    val artifactRefs: List<String> = emptyList(),
) {
    fun toVerdict(): GateVerdict = GateVerdict(
        status = VerdictStatus.entries.find { it.name == status } ?: VerdictStatus.WARN,
        reasonCode = reasonCode,
        message = message,
        artifactRefs = artifactRefs,
    )

    companion object {
        fun from(v: GateVerdict) = GateVerdictSerializable(
            status = v.status.name,
            reasonCode = v.reasonCode,
            message = v.message,
            artifactRefs = v.artifactRefs,
        )
    }
}
