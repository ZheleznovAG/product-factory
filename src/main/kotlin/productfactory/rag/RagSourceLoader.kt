package productfactory.rag

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

class RagSourceLoader(
    private val root: Path,
    private val maxFileSizeBytes: Long = 1_000_000,
) {
    fun load(relativeDirectories: List<String>): List<RagSourceDocument> {
        return relativeDirectories
            .flatMap { dir -> loadDirectory(dir.trim()) }
            .sortedWith(compareBy<RagSourceDocument> { it.sourceType }.thenBy { it.path })
    }

    private fun loadDirectory(relativeDirectory: String): List<RagSourceDocument> {
        if (relativeDirectory.isBlank()) return emptyList()
        val directory = root.resolve(relativeDirectory).normalize()
        if (!Files.exists(directory) || !Files.isDirectory(directory)) return emptyList()
        val sourceType = sourceTypeOf(relativeDirectory)

        return Files.walk(directory).use { stream ->
            val out = mutableListOf<RagSourceDocument>()
            stream
                .filter { it.isRegularFile() }
                .filter { shouldIngest(it) }
                .forEach { path ->
                    val relative = root.relativize(path.normalize()).toString().replace('\\', '/')
                    val content = readText(path) ?: return@forEach
                    out += RagSourceDocument(
                        sourceType = sourceType,
                        path = relative,
                        content = content,
                    )
                }
            out
        }
    }

    private fun shouldIngest(path: Path): Boolean {
        val name = path.fileName?.toString()?.lowercase() ?: return false
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif")) return false
        if (name.endsWith(".pdf") || name.endsWith(".zip") || name.endsWith(".jar")) return false
        val size = try {
            Files.size(path)
        } catch (_: IOException) {
            return false
        }
        return size in 1..maxFileSizeBytes
    }

    private fun readText(path: Path): String? {
        return try {
            val bytes = Files.readAllBytes(path)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun sourceTypeOf(relativeDirectory: String): String = when {
        relativeDirectory.startsWith("archetypes") -> "archetype"
        relativeDirectory.startsWith("docs") -> "doc"
        else -> "source"
    }
}
