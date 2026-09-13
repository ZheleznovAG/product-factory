package productfactory.workflow

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactRegistryTest {

    @Test
    fun `artifactRecordForState for STAGED has repoVersion only`() {
        val record = artifactRecordForState("Run-Id_1", WorkflowState.STAGED)
        assertEquals("Run-Id_1", record.runId)
        assertEquals("STAGED", record.workflowState)
        assertEquals("repo:product-factory/pf-run-id-1:v1", record.repositoryVersion)
        assertEquals(null, record.imageVersion)
        assertEquals(null, record.sbomVersion)
        assertEquals(null, record.signatureVersion)
        assertTrue(record.updatedAt.isNotBlank())
    }

    @Test
    fun `artifactRecordForState for DONE has image sbom signature`() {
        val record = artifactRecordForState("run-abc", WorkflowState.DONE)
        assertEquals("run-abc", record.runId)
        assertEquals("DONE", record.workflowState)
        assertEquals("repo:product-factory/pf-run-abc:v1", record.repositoryVersion)
        assertEquals("image:ghcr.io/product-factory/pf-run-abc:v1", record.imageVersion)
        assertEquals("sbom:cyclonedx:placeholder-v1", record.sbomVersion)
        assertEquals("signature:cosign:placeholder-v1", record.signatureVersion)
    }

    @Test
    fun `artifactRecordForState for DONE uses provided sbom and signature when set`() {
        val record = artifactRecordForState(
            "run-xyz",
            WorkflowState.DONE,
            sbomVersion = "sbom:cyclonedx:abc123",
            signatureVersion = "signature:cosign:def456",
        )
        assertEquals("sbom:cyclonedx:abc123", record.sbomVersion)
        assertEquals("signature:cosign:def456", record.signatureVersion)
    }

    @Test
    fun `artifactRecordForState normalizes runId for repo path`() {
        val record = artifactRecordForState("UPPER_123.X", WorkflowState.STAGED)
        assertEquals("repo:product-factory/pf-upper-123-x:v1", record.repositoryVersion)
    }

    @Test
    fun `FileArtifactRegistry upsert persists record and can be read back`() {
        val dir = createTempDirectory("artifact-registry-test-").toFile()
        val registry = FileArtifactRegistry(directoryPath = dir.absolutePath)
        val record = ArtifactRunRecord(
            runId = "run-1",
            workflowState = "STAGED",
            updatedAt = "2026-02-20T12:00:00Z",
            repositoryVersion = "repo:pf:v1",
        )
        registry.upsert(record)
        val file = java.io.File(dir, "run-1.json")
        assertTrue(file.exists())
        val content = file.readText()
        assertTrue(content.contains("run-1"))
        assertTrue(content.contains("STAGED"))
        assertTrue(content.contains("repo:pf:v1"))
    }

    @Test
    fun `FileArtifactRegistry upsertManifest persists stage specific manifest`() {
        val dir = createTempDirectory("artifact-registry-manifest-test-").toFile()
        val registry = FileArtifactRegistry(directoryPath = dir.absolutePath)
        val path = registry.upsertManifest(
            runId = "run-2",
            state = WorkflowState.STAGED,
            manifestJson = """{"kind":"ArtifactManifest","manifest":{"runId":"run-2"}}""",
        )
        val file = java.io.File(path ?: "")
        assertTrue(file.exists())
        assertTrue(file.name == "run-2.manifest.staged.json")
        assertTrue(file.readText().contains("ArtifactManifest"))
    }
}
