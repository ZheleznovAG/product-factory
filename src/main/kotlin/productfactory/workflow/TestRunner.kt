package productfactory.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Результат прогона тестов в workspace-репо: пропущено (нет сборки/runner), успех или провал.
 */
data class TestRunResult(
    val skipped: Boolean,
    val passed: Boolean,
    val exitCode: Int? = null,
    val output: String = "",
    val error: String = "",
)

/**
 * Запускает тесты в директории репо (Gradle): ./gradlew test или gradle test.
 * Если нет build.gradle.kts/build.gradle или нет gradle/gradlew — возвращает skipped.
 */
object TestRunner {

    private const val TEST_TIMEOUT_MS = 300_000L // 5 min

    fun run(
        workspaceRoot: Path,
        repoName: String,
        executionContext: ExecutionContext = HostExecutionContext,
    ): TestRunResult {
        val repoDir = workspaceRoot.resolve(repoName).normalize()
        if (!repoDir.exists() || !repoDir.toFile().isDirectory) {
            return TestRunResult(skipped = true, passed = true, output = "repo directory not found")
        }
        val hasGradleKts = repoDir.resolve("build.gradle.kts").exists()
        val hasGradle = repoDir.resolve("build.gradle").exists()
        if (!hasGradleKts && !hasGradle) {
            return TestRunResult(skipped = true, passed = true, output = "no Gradle build file")
        }
        val gradlew = repoDir.resolve("gradlew")
        val useWrapper = gradlew.exists() && Files.isExecutable(gradlew)
        val command = if (useWrapper) {
            listOf(gradlew.toAbsolutePath().toString(), "test", "--no-daemon")
        } else {
            listOf("gradle", "test", "--no-daemon")
        }
        return runProcess(repoDir, command, executionContext)
    }

    private fun runProcess(workDir: Path, command: List<String>, executionContext: ExecutionContext): TestRunResult {
        val result = executionContext.runCommand(
            command = command,
            workingDirectory = workDir,
            timeoutMs = TEST_TIMEOUT_MS,
            env = emptyMap(),
        )
        if (!result.started) {
            return TestRunResult(
                skipped = true,
                passed = true,
                output = "",
                error = result.errorMessage ?: "test runner unavailable",
            )
        }
        if (result.timedOut) {
            return TestRunResult(
                skipped = false,
                passed = false,
                exitCode = null,
                output = result.stdout.take(10_000),
                error = buildString {
                    append("timeout after ${TEST_TIMEOUT_MS / 1000}s")
                    if (result.stderr.isNotBlank()) append(". ").append(result.stderr.take(2000))
                    if (!result.errorMessage.isNullOrBlank()) append(". ").append(result.errorMessage)
                },
            )
        }
        val passed = result.exitCode != null && result.exitCode == 0
        return TestRunResult(
            skipped = false,
            passed = passed,
            exitCode = result.exitCode,
            output = result.stdout.take(10_000),
            error = result.stderr.take(10_000),
        )
    }
}
