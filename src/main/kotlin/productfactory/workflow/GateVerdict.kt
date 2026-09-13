package productfactory.workflow

/**
 * Итог quality gate: PASS / WARN / FAIL.
 * Используется для тестов, security и других гейтов; единый формат в audit и API.
 */
enum class VerdictStatus {
    PASS,
    WARN,
    FAIL,
}

/**
 * Коды причин вердикта для машинной обработки и отображения.
 */
object VerdictReason {
    const val GATE_PASSED = "GATE_PASSED"
    const val GATE_FAILED = "GATE_FAILED"
    const val GATE_SKIPPED = "GATE_SKIPPED"
    const val TESTS_PASSED = "TESTS_PASSED"
    const val TESTS_FAILED = "TESTS_FAILED"
    const val TESTS_SKIPPED = "TESTS_SKIPPED"
    const val SECURITY_PASSED = "SECURITY_PASSED"
    const val SECURITY_FAILED = "SECURITY_FAILED"
    const val SECURITY_SKIPPED = "SECURITY_SKIPPED"
    const val RUNNER_UNAVAILABLE = "RUNNER_UNAVAILABLE"
    const val SLO_GATE_PASSED = "SLO_GATE_PASSED"
    const val SLO_GATE_FAILED = "SLO_GATE_FAILED"
    const val SLO_GATE_SKIPPED = "SLO_GATE_SKIPPED"
}

/**
 * Единый вердикт гейта: статус, код причины, сообщение, опциональные ссылки на артефакты (отчёты).
 */
data class GateVerdict(
    val status: VerdictStatus,
    val reasonCode: String,
    val message: String,
    val artifactRefs: List<String> = emptyList(),
) {
    fun isFailure(): Boolean = status == VerdictStatus.FAIL
    fun isPassOrWarn(): Boolean = status == VerdictStatus.PASS || status == VerdictStatus.WARN
}

/** Преобразование результата тестов в единый вердикт. */
fun TestRunResult.toVerdict(gateName: String = "tests"): GateVerdict = when {
    skipped -> GateVerdict(
        status = VerdictStatus.WARN,
        reasonCode = VerdictReason.TESTS_SKIPPED,
        message = output.ifBlank { "tests skipped: $gateName" },
    )
    passed -> GateVerdict(
        status = VerdictStatus.PASS,
        reasonCode = VerdictReason.TESTS_PASSED,
        message = "tests passed",
    )
    else -> GateVerdict(
        status = VerdictStatus.FAIL,
        reasonCode = VerdictReason.TESTS_FAILED,
        message = error.take(500).ifBlank { "exit code $exitCode" },
    )
}

/** Преобразование результата security-проверок в единый вердикт. */
fun SecurityRunResult.toVerdict(gateName: String = "security"): GateVerdict = when {
    skipped -> GateVerdict(
        status = VerdictStatus.WARN,
        reasonCode = VerdictReason.SECURITY_SKIPPED,
        message = output.ifBlank { error.ifBlank { "security checks skipped: $gateName" } },
    )
    passed -> GateVerdict(
        status = VerdictStatus.PASS,
        reasonCode = VerdictReason.SECURITY_PASSED,
        message = "security checks passed",
    )
    else -> GateVerdict(
        status = VerdictStatus.FAIL,
        reasonCode = VerdictReason.SECURITY_FAILED,
        message = error.take(500).ifBlank { "gitleaks=$gitleaksExitCode trivy=$trivyExitCode" },
    )
}
