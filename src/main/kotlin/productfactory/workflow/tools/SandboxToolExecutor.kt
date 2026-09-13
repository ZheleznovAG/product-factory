package productfactory.workflow.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import productfactory.workflow.AuditLog
import productfactory.workflow.ExecutionContext
import productfactory.workflow.HostExecutionContext
import productfactory.workflow.WorkflowAgentRole
import productfactory.workflow.digestPayload
import productfactory.workflow.storage.ArtifactStorage
import productfactory.workflow.tools.GitHubApiClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.writeText
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class SandboxToolExecutor(
    private val auditLog: AuditLog,
    schemaPath: Path = Path.of("contracts", "tools.schema.json"),
    private val archetypesRoot: Path = Path.of("archetypes"),
    private val workspaceRoot: Path = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace"),
    private val artifactStorage: ArtifactStorage? = null,
    private val toolRegistry: ToolRegistry? = null,
    private val secretProvider: SecretProvider? = null,
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = false }
    private val objectMapper = ObjectMapper()
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val toolCallSchema = schemaFactory.getSchema(schemaPath.toUri())
    private val idempotencyStore = ConcurrentHashMap<String, ToolCallResult>()

    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return execute(runId, request, HostExecutionContext)
    }

    override fun execute(runId: String, request: ToolCallRequest, executionContext: ExecutionContext): ToolCallResult {
        validateRequest(request)

        if (toolRegistry != null && request.toolName !in toolRegistry.allowedNames()) {
            val result = ToolCallResult(
                success = false,
                message = "Tool is not in registry: ${request.toolName}",
            )
            logToolCall(runId, request, result)
            return result
        }

        val key = idempotencyStorageKey(request)
        val replayed = idempotencyStore[key]
        if (replayed != null) {
            val replayedResult = replayed.copy(replayed = true)
            logToolCall(runId, request, replayedResult)
            return replayedResult
        }

        var result = when (request.toolName) {
            "create_repo_from_archetype" -> createRepoFromArchetype(request)
            "create_github_repo" -> createGitHubRepo(request)
            "apply_patch" -> applyPatch(request, executionContext)
            "push_repo_to_github" -> pushRepoToGitHub(request, executionContext)
            else -> ToolCallResult(
                success = false,
                message = "Tool is not allowed by sandbox executor: ${request.toolName}",
            )
        }

        if (result.success && request.toolName == "create_repo_from_archetype" && artifactStorage != null) {
            result = uploadArtifactToStorageIfPresent(runId, result)
        }

        if (result.success) {
            idempotencyStore[key] = result
        }
        logToolCall(runId, request, result)
        return result
    }

    private fun applyPatch(request: ToolCallRequest, executionContext: ExecutionContext): ToolCallResult {
        val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "Required argument repo_name is missing")
        val patchContent = request.arguments["patch_content"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "Required argument patch_content is missing")
        if (!repoName.matches(Regex("^[a-z0-9._-]+$"))) {
            return ToolCallResult(success = false, message = "Invalid repo_name: $repoName")
        }
        val repoDir = workspaceRoot.resolve(repoName).toAbsolutePath().normalize()
        if (!Files.isDirectory(repoDir)) {
            return ToolCallResult(success = false, message = "Repo directory not found: $repoName")
        }
        val patchFile = kotlin.io.path.createTempFile("apply_patch_", ".patch")
        try {
            patchFile.writeText(patchContent)
            val commandResult = executionContext.runCommand(
                command = listOf("git", "apply", "--ignore-whitespace", patchFile.toString()),
                workingDirectory = repoDir,
                timeoutMs = 60_000L,
                env = emptyMap(),
            )
            if (!commandResult.started) {
                return ToolCallResult(
                    success = false,
                    message = "git apply unavailable: ${commandResult.errorMessage ?: "executor unavailable"}",
                )
            }
            if (commandResult.timedOut) {
                return ToolCallResult(
                    success = false,
                    message = "git apply timed out",
                    result = buildJsonObject {
                        put("detail", commandResult.stdout.take(1000))
                    },
                )
            }
            val exitCode = commandResult.exitCode ?: -1
            val output = (commandResult.stdout + commandResult.stderr)
            if (exitCode != 0) {
                return ToolCallResult(
                    success = false,
                    result = buildJsonObject { put("git_apply_exit", exitCode); put("detail", output) },
                    message = "git apply failed with exit code $exitCode",
                )
            }
            return ToolCallResult(
                success = true,
                result = buildJsonObject { put("repo_name", repoName); put("applied", true) },
                message = "Patch applied",
            )
        } catch (e: Exception) {
            return ToolCallResult(success = false, message = "apply_patch failed: ${e.message}")
        } finally {
            patchFile.deleteIfExists()
        }
    }

    private fun createGitHubRepo(request: ToolCallRequest): ToolCallResult {
        val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "Required argument repo_name is missing")
        val owner = request.arguments["owner"]?.jsonPrimitive?.content?.trim() ?: ""
        val privateRepo = request.arguments["private"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val token = secretProvider?.getSecret(request.toolName, "GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
        val url = GitHubApiClient.createRepoForOwner(owner, repoName, privateRepo, token)
            ?: return ToolCallResult(
                success = false,
                message = "Failed to create GitHub repo (check GITHUB_TOKEN and repo name)",
            )
        return ToolCallResult(
            success = true,
            result = buildJsonObject {
                put("repo_url", url)
                put("repo_name", repoName)
                put("owner", if (owner.isBlank()) "current user" else owner)
            },
            message = "GitHub repo created",
        )
    }

    private fun pushRepoToGitHub(request: ToolCallRequest, executionContext: ExecutionContext): ToolCallResult {
        val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "Required argument repo_name is missing")
        if (!repoName.matches(Regex("^[a-z0-9._-]+$"))) {
            return ToolCallResult(success = false, message = "Invalid repo_name: $repoName")
        }
        val repoDir = workspaceRoot.resolve(repoName).toAbsolutePath().normalize()
        if (!Files.isDirectory(repoDir)) {
            return ToolCallResult(success = false, message = "Repo directory not found: $repoName")
        }
        val token = secretProvider?.getSecret(request.toolName, "GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolCallResult(success = false, message = "GITHUB_TOKEN is not set")
        var owner = request.arguments["owner"]?.jsonPrimitive?.content?.trim() ?: ""
        if (owner.isBlank()) {
            owner = GitHubApiClient.getCurrentUserLogin(token)
                ?: return ToolCallResult(success = false, message = "Could not resolve GitHub owner (set GITHUB_OWNER or GITHUB_TOKEN with user scope)")
        }
        val remoteUrl = "https://${token}@github.com/${owner}/${repoName}.git"
        return try {
            val gitDir = repoDir.resolve(".git")
            if (!Files.isDirectory(gitDir)) {
                runProcess(executionContext, repoDir, "git", "init")?.let { return it }
            }
            runProcess(executionContext, repoDir, "git", "config", "user.email", "product-factory@local")?.let { return it }
            runProcess(executionContext, repoDir, "git", "config", "user.name", "Product Factory")?.let { return it }
            runProcess(executionContext, repoDir, "git", "add", ".")?.let { return it }
            runProcess(executionContext, repoDir, "git", "commit", "-m", "initial")?.let { return it }
            runProcess(executionContext, repoDir, "git", "remote", "remove", "origin")
            runProcess(executionContext, repoDir, "git", "remote", "add", "origin", remoteUrl)?.let { return it }
            runProcess(executionContext, repoDir, "git", "branch", "-M", "main")?.let { return it }
            runProcess(executionContext, repoDir, "git", "push", "-u", "origin", "main")?.let { return it }
            ToolCallResult(
                success = true,
                result = buildJsonObject {
                    put("repo_name", repoName)
                    put("pushed", true)
                    put("remote", "origin")
                },
                message = "Pushed to GitHub",
            )
        } catch (e: Exception) {
            ToolCallResult(success = false, message = "push_repo_to_github failed: ${e.message}")
        }
    }

    /** Запускает процесс в dir; при ненулевом exit возвращает ToolCallResult с ошибкой, иначе null. */
    private fun runProcess(executionContext: ExecutionContext, dir: Path, vararg command: String): ToolCallResult? {
        val result = executionContext.runCommand(
            command = command.toList(),
            workingDirectory = dir,
            timeoutMs = 120_000L,
            env = emptyMap(),
        )
        if (!result.started) {
            return ToolCallResult(
                success = false,
                message = "${command.first()} unavailable: ${result.errorMessage ?: "executor unavailable"}",
            )
        }
        if (result.timedOut) {
            return ToolCallResult(
                success = false,
                result = buildJsonObject { put("detail", result.stdout.take(500)) },
                message = "${command.first()} timed out",
            )
        }
        val exitCode = result.exitCode ?: -1
        val output = (result.stdout + result.stderr)
        if (exitCode != 0) {
            return ToolCallResult(
                success = false,
                result = buildJsonObject { put("exit_code", exitCode); put("detail", output) },
                message = "${command.first()} failed with exit code $exitCode",
            )
        }
        return null
    }

    private fun validateRequest(request: ToolCallRequest) {
        val requestJson = json.encodeToString(request)
        val errors = toolCallSchema.validate(objectMapper.readTree(requestJson))
        require(errors.isEmpty()) {
            "Invalid tool call request: ${errors.first().message}"
        }
    }

    private fun createRepoFromArchetype(request: ToolCallRequest): ToolCallResult {
        val archetypeId = request.arguments["archetype_id"]?.jsonPrimitive?.content
            ?: return ToolCallResult(
                success = false,
                message = "Required argument archetype_id is missing",
            )

        if (!archetypeId.matches(Regex("^[a-z0-9][a-z0-9.-]*$"))) {
            return ToolCallResult(
                success = false,
                message = "Invalid archetype_id: $archetypeId",
            )
        }

        val sourceDir = archetypesRoot.resolve(archetypeId).normalize()
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            return ToolCallResult(
                success = false,
                message = "Archetype not found: $archetypeId (path: $sourceDir)",
            )
        }

        val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content ?: archetypeId
        if (!repoName.matches(Regex("^[a-z0-9._-]+$"))) {
            return ToolCallResult(
                success = false,
                message = "Invalid repo_name: $repoName",
            )
        }

        val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
        Files.createDirectories(normalizedWorkspaceRoot)
        val targetDir = normalizedWorkspaceRoot.resolve(repoName).normalize()
        if (!targetDir.startsWith(normalizedWorkspaceRoot)) {
            return ToolCallResult(
                success = false,
                message = "Target repository path escapes workspace root",
            )
        }

        if (targetDir.exists() && !targetDir.isDirectory()) {
            return ToolCallResult(
                success = false,
                message = "Target repository path already exists and is not a directory: $targetDir",
            )
        }
        if (targetDir.exists() && targetDir.isDirectory() && Files.list(targetDir).use { it.findAny().isPresent }) {
            return ToolCallResult(
                success = false,
                message = "Target repository directory is not empty: $targetDir",
            )
        }

        copyDirectory(sourceDir, targetDir)

        val resultObj = buildJsonObject {
            put("repo_path", targetDir.toString())
            put("repo_name", repoName)
            put("archetype_id", archetypeId)
        }
        return ToolCallResult(
            success = true,
            result = resultObj,
            message = "Repository scaffolded from archetype in sandbox workspace",
        )
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
            put("artifact_location", locations.first())
            put("artifact_locations", buildJsonArray { locations.forEach { add(JsonPrimitive(it)) } })
        }
        return result.copy(
            result = newResult,
            message = "Repository scaffolded and uploaded to storage",
        )
    }

    private fun copyDirectory(sourceDir: Path, targetDir: Path) {
        Files.walk(sourceDir).use { paths ->
            paths.forEach { sourcePath ->
                val relativePath = sourceDir.relativize(sourcePath)
                val targetPath = targetDir.resolve(relativePath)
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
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

    private fun idempotencyStorageKey(request: ToolCallRequest): String {
        return "${request.toolName}:${request.idempotencyKey}"
    }
}
