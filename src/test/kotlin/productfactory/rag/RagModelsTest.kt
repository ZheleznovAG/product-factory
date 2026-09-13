package productfactory.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RagModelsTest {

    @Test
    fun `index config returns null without jdbc url`() {
        assertNull(RagIndexConfig.fromEnv(emptyMap()))
    }

    @Test
    fun `index config parses defaults and source dirs`() {
        val config = RagIndexConfig.fromEnv(
            mapOf(
                "RAG_PGVECTOR_JDBC_URL" to "jdbc:postgresql://localhost:5432/product_factory",
                "RAG_INDEX_SOURCE_DIRS" to "docs",
            ),
        )
        assertNotNull(config)
        assertEquals("factory-main", config.namespace)
        assertEquals(listOf("docs"), config.sourceDirectories)
        assertEquals(1536, config.embeddingDim)
        assertEquals(6, config.topK)
    }

    @Test
    fun `planner context flag is off by default and opt-in only`() {
        assertFalse(RagFlags.plannerContextEnabled(emptyMap()))
        assertFalse(RagFlags.plannerContextEnabled(mapOf("RAG_PLANNER_CONTEXT_ENABLED" to "false")))
        assertTrue(RagFlags.plannerContextEnabled(mapOf("RAG_PLANNER_CONTEXT_ENABLED" to "true")))
    }
}
