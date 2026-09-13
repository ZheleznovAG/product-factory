package productfactory.workflow

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupplyChainVersionResolverTest {

    @Test
    fun `tool values have highest priority`() {
        val resolver = SupplyChainVersionResolver(
            getenv = { key ->
                when (key) {
                    "FACTORY_SBOM_VERSION" -> "sbom:cyclonedx:ci"
                    "FACTORY_SIGNATURE_VERSION" -> "signature:cosign:ci"
                    else -> null
                }
            },
        )
        val workspace = createTempDirectory("supply-chain-test-")
        val repo = workspace.resolve("repo").also { it.createDirectories() }
        repo.resolve("sbom-cyclonedx.json").writeText("""{"bomFormat":"CycloneDX"}""")
        repo.resolve("cosign.bundle.json").writeText("""{"signature":"x"}""")

        val resolved = resolver.resolve(
            workspaceRoot = workspace,
            repoName = "repo",
            toolSbomVersion = "sbom:cyclonedx:tool",
            toolSignatureVersion = "signature:cosign:tool",
        )

        assertEquals("sbom:cyclonedx:tool", resolved.sbomVersion)
        assertEquals("signature:cosign:tool", resolved.signatureVersion)
    }

    @Test
    fun `ci values are used when tool values are absent`() {
        val resolver = SupplyChainVersionResolver(
            getenv = { key ->
                when (key) {
                    "FACTORY_SBOM_VERSION" -> "sbom:cyclonedx:from-ci"
                    "FACTORY_SIGNATURE_VERSION" -> "signature:cosign:from-ci"
                    else -> null
                }
            },
        )

        val resolved = resolver.resolve(
            workspaceRoot = createTempDirectory("supply-chain-test-"),
            repoName = "repo",
            toolSbomVersion = null,
            toolSignatureVersion = null,
        )

        assertEquals("sbom:cyclonedx:from-ci", resolved.sbomVersion)
        assertEquals("signature:cosign:from-ci", resolved.signatureVersion)
    }

    @Test
    fun `workspace post-step artifacts are used when tool and ci are absent`() {
        val resolver = SupplyChainVersionResolver(getenv = { null })
        val workspace = createTempDirectory("supply-chain-test-")
        val repo = workspace.resolve("repo")
        Files.createDirectories(repo)
        repo.resolve("sbom-cyclonedx.json").writeText("""{"bomFormat":"CycloneDX"}""")
        repo.resolve("cosign.bundle.json").writeText("""{"bundle":"sigstore"}""")

        val resolved = resolver.resolve(
            workspaceRoot = workspace,
            repoName = "repo",
            toolSbomVersion = null,
            toolSignatureVersion = null,
        )

        assertTrue(resolved.sbomVersion?.startsWith("sbom:cyclonedx:sha256:") == true)
        assertTrue(resolved.signatureVersion?.startsWith("signature:cosign:sha256:") == true)
    }

    @Test
    fun `returns nulls when no source is available`() {
        val resolver = SupplyChainVersionResolver(getenv = { null })
        val workspace = createTempDirectory("supply-chain-test-")

        val resolved = resolver.resolve(
            workspaceRoot = workspace,
            repoName = "missing-repo",
            toolSbomVersion = null,
            toolSignatureVersion = null,
        )

        assertNull(resolved.sbomVersion)
        assertNull(resolved.signatureVersion)
    }

    @Test
    fun `explicit env artifact paths cannot escape repo directory`() {
        val workspace = createTempDirectory("supply-chain-test-")
        val repo = workspace.resolve("repo").also { it.createDirectories() }
        val outsideSbom = workspace.resolve("outside-sbom.json")
        val outsideSignature = workspace.resolve("outside-signature.json")
        outsideSbom.writeText("""{"bomFormat":"CycloneDX"}""")
        outsideSignature.writeText("""{"bundle":"sigstore"}""")

        val resolver = SupplyChainVersionResolver(
            getenv = { key ->
                when (key) {
                    "FACTORY_SBOM_PATH" -> outsideSbom.absolutePathString()
                    "FACTORY_SIGNATURE_PATH" -> "../outside-signature.json"
                    else -> null
                }
            },
        )

        val resolved = resolver.resolve(
            workspaceRoot = workspace,
            repoName = "repo",
            toolSbomVersion = null,
            toolSignatureVersion = null,
        )

        assertNull(resolved.sbomVersion)
        assertNull(resolved.signatureVersion)
    }
}
