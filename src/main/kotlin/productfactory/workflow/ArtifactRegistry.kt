package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
data class ArtifactRunRecord(
    val runId: String,
    val workflowState: String,
    val updatedAt: String,
    val repositoryVersion: String? = null,
    val imageVersion: String? = null,
    val sbomVersion: String? = null,
    val signatureVersion: String? = null,
    /** Ссылка на репозиторий (например GitHub), если создан через create_github_repo. */
    val repoUrl: String? = null,
    /** Ссылка на артефакт в хранилище (s3://…), если загружен в S3/MinIO. */
    val artifactLocation: String? = null,
)

interface ArtifactRegistry {
    fun upsert(record: ArtifactRunRecord)
    fun get(runId: String): ArtifactRunRecord?
    fun recordPath(runId: String): String? = null
    fun upsertManifest(runId: String, state: WorkflowState, manifestJson: String): String? = null
    fun manifestPath(runId: String, state: WorkflowState): String? = null
    fun hasManifest(runId: String, state: WorkflowState): Boolean = false
}

object NoopArtifactRegistry : ArtifactRegistry {
    override fun upsert(record: ArtifactRunRecord) = Unit
    override fun get(runId: String): ArtifactRunRecord? = null
}

/**
 * File-based artifact registry: one JSON file per runId.
 * Directory can be configured via env ARTIFACT_REGISTRY_DIR.
 */
class FileArtifactRegistry(
    private val directoryPath: String = System.getenv("ARTIFACT_REGISTRY_DIR") ?: "artifact-registry",
) : ArtifactRegistry {
    private val directory = File(directoryPath).apply { mkdirs() }
    private val json = Json { prettyPrint = true }

    override fun upsert(record: ArtifactRunRecord) {
        val file = File(directory, "${record.runId}.json")
        file.writeText(json.encodeToString(record))
    }

    override fun get(runId: String): ArtifactRunRecord? {
        val file = File(directory, "$runId.json")
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<ArtifactRunRecord>(file.readText())
        }.getOrNull()
    }

    override fun recordPath(runId: String): String {
        return File(directory, "$runId.json").path
    }

    override fun upsertManifest(runId: String, state: WorkflowState, manifestJson: String): String {
        val file = manifestFile(runId, state)
        file.writeText(manifestJson)
        return file.path
    }

    override fun manifestPath(runId: String, state: WorkflowState): String {
        return manifestFile(runId, state).path
    }

    override fun hasManifest(runId: String, state: WorkflowState): Boolean {
        return manifestFile(runId, state).isFile
    }

    private fun manifestFile(runId: String, state: WorkflowState): File {
        return File(directory, "$runId.manifest.${state.name.lowercase()}.json")
    }
}

fun artifactRecordForState(
    runId: String,
    state: WorkflowState,
    repoUrl: String? = null,
    artifactLocation: String? = null,
    /** При DONE: реальная версия SBOM (Syft/CI); при null используется placeholder. */
    sbomVersion: String? = null,
    /** При DONE: реальная версия подписи (Cosign); при null используется placeholder. */
    signatureVersion: String? = null,
): ArtifactRunRecord {
    val normalizedRunId = runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")
    val repoVersion = "repo:product-factory/pf-$normalizedRunId:v1"
    val imageVersion = if (state == WorkflowState.DONE) {
        "image:ghcr.io/product-factory/pf-$normalizedRunId:v1"
    } else {
        null
    }
    val sbomVersion = if (state == WorkflowState.DONE) {
        sbomVersion ?: "sbom:cyclonedx:placeholder-v1"
    } else {
        null
    }
    val signatureVersion = if (state == WorkflowState.DONE) {
        signatureVersion ?: "signature:cosign:placeholder-v1"
    } else {
        null
    }

    return ArtifactRunRecord(
        runId = runId,
        workflowState = state.name,
        updatedAt = Instant.now().toString(),
        repositoryVersion = repoVersion,
        imageVersion = imageVersion,
        sbomVersion = sbomVersion,
        signatureVersion = signatureVersion,
        repoUrl = repoUrl,
        artifactLocation = artifactLocation,
    )
}
