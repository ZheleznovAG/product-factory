package productfactory.workflow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestRunnerTest {

    @Test
    fun `run returns skipped when repo directory does not exist`() {
        val workspace = Files.createTempDirectory("test-runner").toAbsolutePath()
        val result = TestRunner.run(workspace, "nonexistent-repo")
        assertTrue(result.skipped)
        assertTrue(result.passed)
        assertTrue(result.output.contains("not found") || result.output.isNotBlank())
    }

    @Test
    fun `run returns skipped when repo has no gradle build file`() {
        val workspace = Files.createTempDirectory("test-runner").toAbsolutePath()
        val repoDir = Files.createDirectory(workspace.resolve("empty-repo"))
        Files.writeString(repoDir.resolve("README.md"), "# empty")
        val result = TestRunner.run(workspace, "empty-repo")
        assertTrue(result.skipped)
        assertTrue(result.passed)
        assertTrue(result.output.contains("no Gradle") || result.output.isNotBlank())
    }

    @Test
    fun `run returns skipped when execution context cannot start command`() {
        val workspace = Files.createTempDirectory("test-runner").toAbsolutePath()
        val repoDir = Files.createDirectory(workspace.resolve("repo"))
        Files.writeString(repoDir.resolve("build.gradle.kts"), "plugins {}")
        val unavailableContext = object : ExecutionContext {
            override fun runCommand(
                command: List<String>,
                workingDirectory: Path?,
                timeoutMs: Long,
                env: Map<String, String>,
            ): CommandExecutionResult {
                return CommandExecutionResult(started = false, errorMessage = "runbook-only mode")
            }
        }
        val result = TestRunner.run(workspace, "repo", unavailableContext)
        assertTrue(result.skipped)
        assertTrue(result.passed)
        assertEquals("runbook-only mode", result.error)
    }
}
