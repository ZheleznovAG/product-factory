package productfactory.rag

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min

object RagTextProcessing {
    fun chunk(text: String, chunkSize: Int, chunkOverlap: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= chunkSize) return listOf(normalized)

        val chunks = mutableListOf<String>()
        val step = (chunkSize - chunkOverlap).coerceAtLeast(1)
        var start = 0
        while (start < normalized.length) {
            val end = min(start + chunkSize, normalized.length)
            chunks += normalized.substring(start, end).trim()
            if (end >= normalized.length) break
            start += step
        }
        return chunks.filter { it.isNotBlank() }
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sourceFingerprint(documents: List<RagSourceDocument>): String {
        val canonical = documents
            .sortedWith(compareBy<RagSourceDocument> { it.sourceType }.thenBy { it.path })
            .joinToString("\n") { doc ->
                val contentHash = sha256Hex(doc.content)
                "${doc.sourceType}:${doc.path}:${contentHash}"
            }
        return sha256Hex(canonical)
    }

    fun vectorLiteral(embedding: FloatArray): String =
        embedding.joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
            val asDouble = value.toDouble()
            if (asDouble.isFinite()) asDouble.toString() else "0.0"
        }
}
