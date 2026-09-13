package productfactory.workflow

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class SupplyChainVersions(
    val sbomVersion: String? = null,
    val signatureVersion: String? = null,
)

/**
 * Определяет версии SBOM/подписи для ToolStepResult.
 * Приоритет источников:
 * 1) явные значения из tool result;
 * 2) значения из CI env;
 * 3) артефакты post-step в workspace (файлы SBOM/signature).
 */
class SupplyChainVersionResolver(
    private val getenv: (String) -> String? = { key -> System.getenv(key) },
) {

    fun resolve(
        workspaceRoot: Path,
        repoName: String,
        toolSbomVersion: String?,
        toolSignatureVersion: String?,
    ): SupplyChainVersions {
        val normalizedToolSbom = toolSbomVersion?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedToolSignature = toolSignatureVersion?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedToolSbom != null || normalizedToolSignature != null) {
            return SupplyChainVersions(
                sbomVersion = normalizedToolSbom,
                signatureVersion = normalizedToolSignature,
            )
        }

        val ciSbom = firstEnvValue("FACTORY_SBOM_VERSION", "CI_SBOM_VERSION", "SBOM_VERSION")
        val ciSignature = firstEnvValue("FACTORY_SIGNATURE_VERSION", "CI_SIGNATURE_VERSION", "SIGNATURE_VERSION")
        if (ciSbom != null || ciSignature != null) {
            return SupplyChainVersions(
                sbomVersion = ciSbom,
                signatureVersion = ciSignature,
            )
        }

        val repoDir = workspaceRoot.resolve(repoName).normalize()
        if (!Files.isDirectory(repoDir)) return SupplyChainVersions()

        val sbomVersion = resolveFromWorkspaceSbom(repoDir)
        val signatureVersion = resolveFromWorkspaceSignature(repoDir)
        return SupplyChainVersions(
            sbomVersion = sbomVersion,
            signatureVersion = signatureVersion,
        )
    }

    private fun resolveFromWorkspaceSbom(repoDir: Path): String? {
        val explicit = getenv("FACTORY_SBOM_PATH")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolvePathWithinRepo(repoDir, it) }
            ?.takeIf { Files.isRegularFile(it) }
        if (explicit != null) {
            return "sbom:${sbomFormat(explicit.fileName.toString())}:sha256:${sha256Hex(explicit)}"
        }

        val defaults = listOf(
            "sbom-cyclonedx.json",
            "sbom.spdx.json",
            "sbom.json",
            "build/sbom-cyclonedx.json",
            "build/sbom.spdx.json",
        )
        val candidate = defaults
            .asSequence()
            .map { repoDir.resolve(it).normalize() }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return null

        return "sbom:${sbomFormat(candidate.fileName.toString())}:sha256:${sha256Hex(candidate)}"
    }

    private fun resolveFromWorkspaceSignature(repoDir: Path): String? {
        val explicit = getenv("FACTORY_SIGNATURE_PATH")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolvePathWithinRepo(repoDir, it) }
            ?.takeIf { Files.isRegularFile(it) }
        if (explicit != null) {
            return "signature:cosign:sha256:${sha256Hex(explicit)}"
        }

        val defaults = listOf(
            "cosign.bundle.json",
            "cosign-signature.json",
            "signature.json",
            "build/cosign.bundle.json",
            "build/signature.json",
        )
        val candidate = defaults
            .asSequence()
            .map { repoDir.resolve(it).normalize() }
            .firstOrNull { Files.isRegularFile(it) }
            ?: return null

        return "signature:cosign:sha256:${sha256Hex(candidate)}"
    }

    private fun firstEnvValue(vararg keys: String): String? {
        return keys
            .asSequence()
            .mapNotNull { key -> getenv(key)?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
    }

    private fun resolvePathWithinRepo(repoDir: Path, configuredPath: String): Path? {
        val candidate = repoDir.resolve(configuredPath).normalize()
        return candidate.takeIf { it.startsWith(repoDir) }
    }

    private fun sbomFormat(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            "cyclonedx" in lower -> "cyclonedx"
            "spdx" in lower -> "spdx"
            else -> "unknown"
        }
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
