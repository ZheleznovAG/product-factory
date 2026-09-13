package productfactory.rag

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RagSourceLoaderTest {

    @Test
    fun `loads docs and archetypes text files`() {
        val root = Files.createTempDirectory("pf-rag-loader")
        val docs = root.resolve("docs")
        val archetypes = root.resolve("archetypes/catalog")
        Files.createDirectories(docs)
        Files.createDirectories(archetypes)

        docs.resolve("runbook.md").writeText("Runbook text")
        docs.resolve("diagram.png").writeText("binary")
        archetypes.resolve("README.md").writeText("Archetype readme")

        val loaded = RagSourceLoader(root).load(listOf("docs", "archetypes"))

        assertEquals(2, loaded.size)
        assertTrue(loaded.any { it.sourceType == "doc" && it.path.endsWith("docs/runbook.md") })
        assertTrue(loaded.any { it.sourceType == "archetype" && it.path.endsWith("archetypes/catalog/README.md") })
    }
}
