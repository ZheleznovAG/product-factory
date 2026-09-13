package productfactory.workflow.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import productfactory.workflow.AuditLog
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerToolExecutorTest {

    private val noopAuditLog = object : AuditLog {
        override fun log(runId: String, event: String, payload: String) {}
    }

    @Test
    fun `create_github_repo is delegated to sandbox`() {
        val delegatedResult = ToolCallResult(
            success = false,
            message = "delegated",
            result = buildJsonObject { put("delegated", true) },
        )
        val stubSandbox = object : ToolExecutor {
            override fun execute(runId: String, request: ToolCallRequest) = delegatedResult
        }
        val workspace = Files.createTempDirectory("pf-docker-exec").toAbsolutePath()
        val archetypes = Files.createTempDirectory("pf-docker-archetypes").toAbsolutePath()
        val executor = DockerToolExecutor(
            sandbox = stubSandbox,
            dockerImage = "product-factory-tool-runner:latest",
            workspaceRoot = workspace,
            archetypesRoot = archetypes,
            auditLog = noopAuditLog,
        )
        val request = ToolCallRequest(
            toolName = "create_github_repo",
            idempotencyKey = "test-key",
            arguments = buildJsonObject {
                put("repo_name", JsonPrimitive("test-repo"))
            },
        )
        val result = executor.execute("run-1", request)
        assertEquals(delegatedResult.message, result.message)
        assertEquals(delegatedResult.success, result.success)
    }

    @Test
    fun `push_repo_to_github is delegated to sandbox`() {
        val delegatedResult = ToolCallResult(
            success = true,
            message = "pushed",
            result = buildJsonObject { put("pushed", true) },
        )
        val stubSandbox = object : ToolExecutor {
            override fun execute(runId: String, request: ToolCallRequest) = delegatedResult
        }
        val workspace = Files.createTempDirectory("pf-docker-exec2").toAbsolutePath()
        val archetypes = Files.createTempDirectory("pf-docker-archetypes2").toAbsolutePath()
        val executor = DockerToolExecutor(
            sandbox = stubSandbox,
            dockerImage = "product-factory-tool-runner:latest",
            workspaceRoot = workspace,
            archetypesRoot = archetypes,
            auditLog = noopAuditLog,
        )
        val request = ToolCallRequest(
            toolName = "push_repo_to_github",
            idempotencyKey = "test-key",
            arguments = buildJsonObject {
                put("repo_name", JsonPrimitive("test-repo"))
            },
        )
        val result = executor.execute("run-2", request)
        assertEquals("pushed", result.message)
        assertEquals(true, result.success)
    }
}
