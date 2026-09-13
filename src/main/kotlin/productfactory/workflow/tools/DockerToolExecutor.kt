package productfactory.workflow.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import productfactory.workflow.AuditLog
import productfactory.workflow.ExecutionContext
import productfactory.workflow.HostExecutionContext
import productfactory.workflow.WorkflowAgentRole
import productfactory.workflow.digestPayload
import productfactory.workflow.storage.ArtifactStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

/**
 * Executor, запускающий create_repo_from_archetype и apply_patch в отдельном Docker-контейнере
 * (образ tool-runner). Остальные tools делегируются [sandbox]. Изоляция без доступа к хосту.
 * Включение: FACTORY_TOOL_RUNNER=docker и задан DOCKER_IMAGE_TOOL_RUNNER.
 */
class DockerToolExecutor(
    private val sandbox: ToolExecutor,
    private val dockerImage: String,
    private val workspaceRoot: Path,
    private val archetypesRoot: Path,
    private val auditLog: AuditLog,
    private val artifactStorage: ArtifactStorage? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val timeoutSeconds: Long = 300L,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val idempotencyStore = ConcurrentHashMap<String, ToolCallResult>()

    private val dockerizedTools = setOf("create_repo_from_archetype", "apply_patch")

    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return execute(runId, request, HostExecutionContext)
    }

    override fun execute(runId: String, request: ToolCallRequest, executionContext: ExecutionContext): ToolCallResult {
        if (toolRegistry != null && request.toolName !in toolRegistry.allowedNames()) {
            val result = ToolCallResult(
                success = false,
                message = "Tool is not in registry: ${request.toolName}",
            )
            logToolCall(runId, request, result)
            return result
        }

        if (request.toolName !in dockerizedTools) {
            return sandbox.execute(runId, request, executionContext)
        }

        val key = "${request.toolName}:${request.idempotencyKey}"
        idempotencyStore[key]?.let { replayed ->
            val copied = replayed.copy(replayed = true)
            logToolCall(runId, request, copied)
            return copied
        }

        val dockerResult = runInDocker(request)
        if (dockerResult != null) {
            var result = dockerResult
            if (result.success && request.toolName == "create_repo_from_archetype" && artifactStorage != null) {
                result = uploadArtifactToStorageIfPresent(runId, result)
            }
            if (result.success) {
                idempotencyStore[key] = result
            }
            logToolCall(runId, request, result)
            return result
        }

        return sandbox.execute(runId, request, executionContext)
    }

    private fun runInDocker(request: ToolCallRequest): ToolCallResult? {
        val wsAbs = workspaceRoot.toAbsolutePath().normalize()
        val archAbs = archetypesRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(wsAbs)) Files.createDirectories(wsAbs)
        if (!archAbs.exists() || !archAbs.toFile().isDirectory) {
            return ToolCallResult(success = false, message = "Archetypes root not found: $archAbs")
        }

        val env = mutableListOf(
            "TOOL_NAME=${request.toolName}",
        )
        when (request.toolName) {
            "create_repo_from_archetype" -> {
                val archetypeId = request.arguments["archetype_id"]?.jsonPrimitive?.content
                    ?: return ToolCallResult(success = false, message = "archetype_id is required")
                val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content ?: archetypeId
                env.add("ARCHETYPE_ID=$archetypeId")
                env.add("REPO_NAME=$repoName")
            }
            "apply_patch" -> {
                val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content
                    ?: return ToolCallResult(success = false, message = "repo_name is required")
                val patchContent = request.arguments["patch_content"]?.jsonPrimitive?.content
                    ?: return ToolCallResult(success = false, message = "patch_content is required")
                env.add("REPO_NAME=$repoName")
                env.add("PATCH_B64=${Base64.getEncoder().encodeToString(patchContent.toByteArray(Charsets.UTF_8))}")
            }
            else -> return null
        }

        val cmd = mutableListOf(
            "docker", "run", "--rm",
            "-v", "$wsAbs:/workspace",
            "-v", "$archAbs:/archetypes:ro",
        )
        env.forEach { cmd.add("-e"); cmd.add(it) }
        cmd.add(dockerImage)

        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ToolCallResult(success = false, message = "Docker tool run timed out after ${timeoutSeconds}s", result = buildJsonObject { put("output", output.take(2000)) })
            } else {
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    ToolCallResult(success = false, message = "Docker tool run failed with exit $exitCode", result = buildJsonObject { put("output", output.take(5000)) })
                } else {
                    parseRunnerOutput(output)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseRunnerOutput(output: String): ToolCallResult? {
        val line = output.lineSequence().lastOrNull { it.trim().startsWith("{") } ?: return null
        return try {
            val parsed = json.parseToJsonElement(line.trim())
            val obj = parsed as? JsonObject ?: return null
            val success = obj["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: return null
            val message = obj["message"]?.jsonPrimitive?.content ?: ""
            val resultObj = (obj["result"] as? JsonObject) ?: buildJsonObject { }
            val fixedResult = fixRepoPathInResult(resultObj)
            ToolCallResult(success = success, result = fixedResult, message = message)
        } catch (_: Exception) {
            null
        }
    }

    private fun fixRepoPathInResult(result: JsonObject): JsonObject {
        val repoName = result["repo_name"]?.jsonPrimitive?.content ?: return result
        val hostRepoPath = workspaceRoot.resolve(repoName).toAbsolutePath().normalize().toString()
        return buildJsonObject {
            result.forEach { (k, v) ->
                put(k, if (k == "repo_path") JsonPrimitive(hostRepoPath) else v)
            }
        }
    }

    private fun uploadArtifactToStorageIfPresent(runId: String, result: ToolCallResult): ToolCallResult {
        val repoPath = result.result["repo_path"]?.jsonPrimitive?.content ?: return result
        val repoName = result.result["repo_name"]?.jsonPrimitive?.content ?: return result
        val localDir = Path.of(repoPath)
        if (!Files.isDirectory(localDir)) return result
        val locations = artifactStorage?.upload(localDir, runId, repoName) ?: return result
        if (locations.isEmpty()) return result
        val newResult = buildJsonObject {
            result.result.forEach { (k, v) -> put(k, v) }
            put("artifact_location", JsonPrimitive(locations.first()))
            put("artifact_locations", buildJsonArray { locations.forEach { add(JsonPrimitive(it)) } })
        }
        return result.copy(
            result = newResult,
            message = "Repository scaffolded and uploaded to storage",
        )
    }

    private fun logToolCall(runId: String, request: ToolCallRequest, result: ToolCallResult) {
        val requestPayload = json.encodeToString(ToolCallRequest.serializer(), request)
        val resultPayload = json.encodeToString(ToolCallResult.serializer(), result)
        val payload = buildJsonObject {
            put("runId", runId)
            put("role", WorkflowAgentRole.IMPLEMENTER.auditValue)
            put("toolName", request.toolName)
            put("idempotencyKey", request.idempotencyKey)
            put("inputDigest", digestPayload(requestPayload))
            put("outputDigest", digestPayload(resultPayload))
            put("result", json.encodeToJsonElement(ToolCallResult.serializer(), result))
        }
        auditLog.log(runId, "tool_call_executed", payload.toString())
    }
}
