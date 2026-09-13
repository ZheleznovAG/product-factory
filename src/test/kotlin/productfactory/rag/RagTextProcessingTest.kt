package productfactory.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RagTextProcessingTest {

    @Test
    fun `chunking uses overlap and returns multiple chunks`() {
        val text = (1..120).joinToString(" ") { "w$it" }
        val chunks = RagTextProcessing.chunk(text, chunkSize = 80, chunkOverlap = 20)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.isNotBlank() })
    }

    @Test
    fun `source fingerprint is deterministic and content-sensitive`() {
        val docA = RagSourceDocument(sourceType = "doc", path = "docs/a.md", content = "hello")
        val docB = RagSourceDocument(sourceType = "doc", path = "docs/b.md", content = "world")

        val first = RagTextProcessing.sourceFingerprint(listOf(docA, docB))
        val second = RagTextProcessing.sourceFingerprint(listOf(docB, docA))
        val changed = RagTextProcessing.sourceFingerprint(listOf(docA.copy(content = "hello!"), docB))

        assertEquals(first, second)
        assertNotEquals(first, changed)
    }

    @Test
    fun `vector literal is pgvector compatible`() {
        val literal = RagTextProcessing.vectorLiteral(floatArrayOf(1.0f, -2.5f, 0.0f))
        assertEquals("[1.0,-2.5,0.0]", literal)
    }
}
