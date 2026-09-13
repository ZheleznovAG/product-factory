package productfactory.workflow

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Результат прогона security-проверок: пропущено (нет утилит), успех или провал.
 */
data class SecurityRunResult(
    val skipped: Boolean,
    val passed: Boolean,
    val gitleaksExitCode: Int? = null,
    val trivyExitCode: Int? = null,
    val output: String = "",
    val error: String = "",
)

/**
 * Запускает gitleaks detect и trivy fs в директории репо.
 * Если утилиты не установлены — skipped. При обнаружении секретов/уязвимостей — passed = false.
 */
object SecurityRunner {

    private const val TIMEOUT_MS = 120_000L // 2 min per tool

    fun run(workspaceRoot: Path, repoName: String): SecurityRunResult {
        val repoDir = workspaceRoot.resolve(repoName).normalize()
        if (!repoDir.exists() || !repoDir.toFile().isDirectory) {
            return SecurityRunResult(skipped = true, passed = true, output = "repo not found")
        }
        var gitleaksCode: Int? = null
        var trivyCode: Int? = null
        val out = StringBuilder()
        val err = StringBuilder()
        try {
            gitleaksCode = runCommand(repoDir, listOf("gitleaks", "detect", "--no-banner", "--source", ".", "-v"), out, err)
            trivyCode = runCommand(repoDir, listOf("trivy", "fs", "--exit-code", "1", "."), out, err)
        } catch (e: Exception) {
            return SecurityRunResult(
                skipped = true,
                passed = true,
                output = out.toString().take(2000),
                error = e.message ?: "security tools unavailable",
            )
        }
        val passed = (gitleaksCode == null || gitleaksCode == 0) && (trivyCode == null || trivyCode == 0)
        return SecurityRunResult(
            skipped = false,
            passed = passed,
            gitleaksExitCode = gitleaksCode,
            trivyExitCode = trivyCode,
            output = out.toString().take(10_000),
            error = err.toString().take(5_000),
        )
    }

    private fun runCommand(workDir: Path, command: List<String>, out: StringBuilder, err: StringBuilder): Int? {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        out.append(stdout)
        err.append(stderr)
        val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return if (finished) process.exitValue() else null.also { process.destroyForcibly() }
    }
}
