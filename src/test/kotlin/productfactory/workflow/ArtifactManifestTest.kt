package productfactory.workflow

import productfactory.api.FactoryRunRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtifactManifestTest {

    @Test
    fun `buildArtifactManifest produces stable checksum for same contract set`() {
        val request = FactoryRunRequest(
            goal = "Generate deployable catalog service",
            constraints = listOf("Kotlin", "Ktor"),
            targetStack = "catalog-service",
            contracts = mapOf(
                "product" to "apiVersion: productfactory.io/v1\nkind: Product\n",
                "constraints" to "apiVersion: productfactory.io/v1\nkind: Constraints\n",
                "quality_profile" to "apiVersion: productfactory.io/v1\nkind: QualityProfile\n",
                "risk_profile" to "apiVersion: productfactory.io/v1\nkind: RiskProfile\n",
                "target_stack" to "apiVersion: productfactory.io/v1\nkind: TargetStack\n",
            ),
        )
        val toolResult = ToolStepResult(
            artifactLocation = "workspace/pf-test",
            executedToolCallIds = listOf("create_repo_from_archetype:test"),
        )

        val manifestA = buildArtifactManifest(
            runId = "run-a",
            request = request,
            state = WorkflowState.DONE,
            toolResult = toolResult,
            auditLogPath = "audit.log",
            artifactRegistryRecordPath = "artifact-registry/run-a.json",
            toolRegistryVersion = "2026-02-26",
        )
        val manifestB = buildArtifactManifest(
            runId = "run-b",
            request = request,
            state = WorkflowState.DONE,
            toolResult = toolResult,
            auditLogPath = "audit.log",
            artifactRegistryRecordPath = "artifact-registry/run-b.json",
            toolRegistryVersion = "2026-02-26",
        )

        assertEquals(manifestA.manifest.checksum, manifestB.manifest.checksum)
        assertEquals("1.0.0", manifestA.manifest.version)
        assertTrue(manifestA.manifest.provenance.gitCommit.isNotBlank())
        assertTrue(Instant.parse(manifestA.manifest.provenance.timestamp).toString().isNotBlank())
    }

    @Test
    fun `buildArtifactManifest checksum changes when yaml content changes`() {
        val baseRequest = FactoryRunRequest(
            goal = "Generate deployable catalog service",
            constraints = listOf("Kotlin", "Ktor"),
            targetStack = "catalog-service",
            contracts = mapOf(
                "product" to "apiVersion: productfactory.io/v1\nkind: Product\n",
                "constraints" to "apiVersion: productfactory.io/v1\nkind: Constraints\n",
                "quality_profile" to "apiVersion: productfactory.io/v1\nkind: QualityProfile\n",
                "risk_profile" to "apiVersion: productfactory.io/v1\nkind: RiskProfile\n",
                "target_stack" to "apiVersion: productfactory.io/v1\nkind: TargetStack\n",
            ),
        )
        val changedRequest = baseRequest.copy(
            contracts = baseRequest.contracts + ("product" to "apiVersion: productfactory.io/v1\nkind: Product\nname: changed\n"),
        )

        val manifestBase = buildArtifactManifest(
            runId = "run-base",
            request = baseRequest,
            state = WorkflowState.DONE,
            toolResult = ToolStepResult(),
            auditLogPath = "audit.log",
            artifactRegistryRecordPath = "artifact-registry/run-base.json",
            toolRegistryVersion = "2026-02-26",
        )
        val manifestChanged = buildArtifactManifest(
            runId = "run-changed",
            request = changedRequest,
            state = WorkflowState.DONE,
            toolResult = ToolStepResult(),
            auditLogPath = "audit.log",
            artifactRegistryRecordPath = "artifact-registry/run-changed.json",
            toolRegistryVersion = "2026-02-26",
        )

        assertNotEquals(manifestBase.manifest.checksum, manifestChanged.manifest.checksum)
    }
}
