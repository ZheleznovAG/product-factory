package productfactory.workflow.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test
    fun `load returns null when file does not exist`() {
        val result = loadToolRegistry(Path.of("nonexistent", "tools.registry.json"))
        assertNull(result)
    }

    @Test
    fun `load parses registry and exposes allowed names and risk tier`() {
        val dir = Files.createTempDirectory("tool-registry")
        val json = """
            {
              "registry_version": "0.1.0",
              "tools": [
                {"name": "create_repo", "risk_tier": "write_limited", "requires_human_approval": false},
                {"name": "deploy", "risk_tier": "privileged", "requires_human_approval": true}
              ]
            }
        """.trimIndent()
        val file = dir.resolve("registry.json")
        file.writeText(json)
        try {
        val registry = loadToolRegistry(file)
        assertNotNull(registry)
        assertEquals("0.1.0", registry.registry_version)
        assertEquals(setOf("create_repo", "deploy"), registry.allowedNames())
        assertEquals("write_limited", registry.getRiskTier("create_repo"))
        assertEquals("privileged", registry.getRiskTier("deploy"))
        assertTrue(registry.requiresApproval("deploy"))
        assertTrue(!registry.requiresApproval("create_repo"))
        } finally {
            file.toFile().delete()
            dir.toFile().delete()
        }
    }
}
