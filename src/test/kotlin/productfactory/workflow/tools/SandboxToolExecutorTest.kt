package productfactory.workflow.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import productfactory.workflow.AuditLog
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SandboxToolExecutorTest {

    @Test
    fun `executor scaffolds repository from catalog archetype and replays by idempotency key`() {
        val archetypesRoot = Files.createTempDirectory("archetypes-root")
        val workspaceRoot = Files.createTempDirectory("workspace-root")
        val archetypeDir = archetypesRoot.resolve("catalog-service")
        Files.createDirectories(archetypeDir.resolve("nested"))
        archetypeDir.resolve("README.md").writeText("catalog archetype")
        archetypeDir.resolve("nested/settings.conf").writeText("sample=true")

        val auditLog = InMemoryAuditLog()
        val executor = SandboxToolExecutor(
            auditLog = auditLog,
            archetypesRoot = archetypesRoot,
            workspaceRoot = workspaceRoot,
        )
        val request = ToolCallRequest(
            toolName = "create_repo_from_archetype",
            idempotencyKey = "idem-001",
            arguments = buildJsonObject {
                put("repo_name", "demo-repo")
                put("archetype_id", "catalog-service")
            },
        )

        val first = executor.execute(runId = "run-1", request = request)
        val targetReadme = workspaceRoot.resolve("demo-repo/README.md")
        targetReadme.writeText("local change after first call")
        val second = executor.execute(runId = "run-1", request = request)

        assertTrue(first.success)
        assertTrue(second.success)
        assertNotNull(first.result["repo_path"])
        assertEquals("catalog-service", first.result["archetype_id"]?.jsonPrimitive?.content)
        assertEquals("local change after first call", targetReadme.readText())
        assertEquals(false, first.replayed)
        assertEquals(true, second.replayed)
        assertEquals(2, auditLog.events.size)
        assertTrue(auditLog.events.all { it.eventType == "tool_call_executed" })
        assertTrue(auditLog.events.all { it.payload.contains(""""idempotencyKey":"idem-001"""") })
        assertTrue(auditLog.events.all { it.payload.contains(""""toolName":"create_repo_from_archetype"""") })
        assertTrue(auditLog.events.all { it.payload.contains(""""role":"Implementer"""") })
        assertTrue(auditLog.events.all { it.payload.contains(""""inputDigest":"""") })
        assertTrue(auditLog.events.all { it.payload.contains(""""outputDigest":"""") })
    }

    @Test
    fun `executor rejects unsupported archetype id`() {
        val workspaceRoot = Files.createTempDirectory("workspace-root")
        val auditLog = InMemoryAuditLog()
        val executor = SandboxToolExecutor(
            auditLog = auditLog,
            archetypesRoot = Files.createTempDirectory("archetypes-root"),
            workspaceRoot = workspaceRoot,
        )
        val request = ToolCallRequest(
            toolName = "create_repo_from_archetype",
            idempotencyKey = "idem-unsupported",
            arguments = buildJsonObject {
                put("repo_name", "demo-repo")
                put("archetype_id", "api-service-kotlin-ktor")
            },
        )

        val result = executor.execute(runId = "run-2", request = request)

        assertFalse(result.success)
        assertTrue(result.message?.contains("Archetype not found") == true)
        assertEquals(1, auditLog.events.size)
        assertTrue(Files.list(workspaceRoot).use { it.findAny().isEmpty })
    }

    @Test
    fun `apply_patch applies unified diff in repo directory`() {
        val workspaceRoot = Files.createTempDirectory("workspace-apply-patch")
        val repoDir = workspaceRoot.resolve("my-repo")
        Files.createDirectories(repoDir)
        repoDir.resolve("README.md").writeText("Hello\n")
        runGit(repoDir, "init")
        runGit(repoDir, "config", "user.email", "test@test")
        runGit(repoDir, "config", "user.name", "Test")
        runGit(repoDir, "add", ".")
        runGit(repoDir, "commit", "-m", "initial")
        repoDir.resolve("README.md").writeText("Hello\nPatched line\n")
        val patchProcess = ProcessBuilder("git", "diff", "HEAD")
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
        val patch = patchProcess.inputStream.reader().readText()
        patchProcess.waitFor()
        repoDir.resolve("README.md").writeText("Hello\n")

        val auditLog = InMemoryAuditLog()
        val executor = SandboxToolExecutor(
            auditLog = auditLog,
            archetypesRoot = Files.createTempDirectory("archetypes-empty"),
            workspaceRoot = workspaceRoot,
        )
        val request = ToolCallRequest(
            toolName = "apply_patch",
            idempotencyKey = "apply-1",
            arguments = buildJsonObject {
                put("repo_name", "my-repo")
                put("patch_content", patch)
            },
        )

        val result = executor.execute(runId = "run-apply", request = request)

        assertTrue(result.success, result.message ?: "")
        assertTrue(repoDir.resolve("README.md").readText().contains("Patched line"))
    }

    private fun runGit(repoDir: java.nio.file.Path, vararg args: String) {
        ProcessBuilder("git", *args)
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }
}

private class InMemoryAuditLog : AuditLog {
    val events = mutableListOf<LoggedEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedEvent(runId, eventType, payload)
    }
}

private data class LoggedEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)
