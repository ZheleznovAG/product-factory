package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import productfactory.api.FactoryRunRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs

@Serializable
data class ArtifactManifestDocument(
    val apiVersion: String = "productfactory.io/v1",
    val kind: String = "ArtifactManifest",
    val manifest: ArtifactManifestPayload,
    val seed: ArtifactManifestSeed,
    val inputs: ArtifactManifestInputs,
)

@Serializable
data class ArtifactManifestPayload(
    val version: String,
    val runId: String,
    val workflowState: String,
    val artifactType: String,
    val artifactLocation: String,
    val checksum: String,
    val provenance: ArtifactManifestProvenance,
    val producedAt: String,
    val evidence: ArtifactManifestEvidence,
)

@Serializable
data class ArtifactManifestProvenance(
    val gitCommit: String,
    val timestamp: String,
)

@Serializable
data class ArtifactManifestEvidence(
    val auditLogPath: String,
    val artifactRegistryRecord: String,
    val toolCallIds: List<String> = emptyList(),
)

@Serializable
data class ArtifactManifestSeed(
    val value: Long,
    val source: String,
    val algorithm: String = "sha256-prefix64",
)

@Serializable
data class ArtifactManifestInputs(
    val goal: String,
    val constraints: List<String>,
    val targetStack: String,
    val contracts: Map<String, String>,
    val runtime: ArtifactManifestRuntime? = null,
)

@Serializable
data class ArtifactManifestRuntime(
    val plannerModel: String? = null,
    val codegenModel: String? = null,
    val toolRegistryVersion: String? = null,
)

@Serializable
private data class ArtifactChecksumPayload(
    val goal: String,
    val constraints: List<String>,
    val targetStack: String,
    val contracts: Map<String, String>,
)

private const val ARTIFACT_VERSION = "1.0.0"

fun buildArtifactManifest(
    runId: String,
    request: FactoryRunRequest,
    state: WorkflowState,
    toolResult: ToolStepResult,
    auditLogPath: String,
    artifactRegistryRecordPath: String,
    toolRegistryVersion: String?,
): ArtifactManifestDocument {
    val normalizedRunId = runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")
    val artifactLocation = toolResult.artifactLocation
        ?: toolResult.repoUrl
        ?: "workspace/pf-$normalizedRunId"

    val manifestState = when (state) {
        WorkflowState.STAGED -> "STAGED"
        WorkflowState.DONE -> "DONE"
        else -> "STAGED"
    }
    val targetStack = request.targetStack?.trim()?.takeIf { it.isNotBlank() } ?: "catalog-service"
    val seed = runSeed(runId)
    val checksum = buildDeterministicChecksum(request, targetStack)
    val provenanceTimestamp = Instant.now().toString()

    return ArtifactManifestDocument(
        manifest = ArtifactManifestPayload(
            version = ARTIFACT_VERSION,
            runId = runId,
            workflowState = manifestState,
            artifactType = "repository-archive",
            artifactLocation = artifactLocation,
            checksum = checksum,
            provenance = ArtifactManifestProvenance(
                gitCommit = resolveGitCommit(),
                timestamp = provenanceTimestamp,
            ),
            producedAt = provenanceTimestamp,
            evidence = ArtifactManifestEvidence(
                auditLogPath = auditLogPath,
                artifactRegistryRecord = artifactRegistryRecordPath,
                toolCallIds = toolResult.executedToolCallIds,
            ),
        ),
        seed = seed,
        inputs = ArtifactManifestInputs(
            goal = request.goal,
            constraints = request.constraints,
            targetStack = targetStack,
            contracts = request.contracts,
            runtime = ArtifactManifestRuntime(
                plannerModel = System.getenv("FACTORY_PLANNER_MODEL")?.trim()?.takeIf { it.isNotBlank() },
                codegenModel = System.getenv("FACTORY_CODEGEN_MODEL")?.trim()?.takeIf { it.isNotBlank() },
                toolRegistryVersion = toolRegistryVersion,
            ),
        ),
    )
}

private fun runSeed(runId: String): ArtifactManifestSeed {
    val envSeed = System.getenv("FACTORY_RUN_SEED")?.trim()
    val value = envSeed?.toLongOrNull() ?: abs(runId.hashCode().toLong())
    val source = if (envSeed == null) "system" else "user"
    return ArtifactManifestSeed(value = value, source = source)
}

fun ArtifactManifestDocument.toJsonString(): String {
    return Json { prettyPrint = true }.encodeToString(this)
}

private fun buildDeterministicChecksum(request: FactoryRunRequest, targetStack: String): String {
    val normalizedContracts = request.contracts.toSortedMap()
    val payload = ArtifactChecksumPayload(
        goal = request.goal,
        constraints = request.constraints,
        targetStack = targetStack,
        contracts = normalizedContracts,
    )
    val canonicalJson = Json {
        prettyPrint = false
        encodeDefaults = true
    }.encodeToString(payload)
    return sha256Hex(canonicalJson.toByteArray(StandardCharsets.UTF_8))
}

private fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(data)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun resolveGitCommit(): String {
    val fromEnv = System.getenv("GIT_COMMIT")?.trim()?.takeIf { it.isNotBlank() }
    if (fromEnv != null) return fromEnv

    return runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        if (exitCode == 0 && output.matches(Regex("^[a-fA-F0-9]{40}$"))) output else "unknown"
    }.getOrElse { "unknown" }
}
