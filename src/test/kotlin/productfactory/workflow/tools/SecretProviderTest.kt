package productfactory.workflow.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecretProviderTest {

    @Test
    fun `EnvSecretProvider returns null when tool not allowed for secret`() {
        val reg = ToolRegistry(
            registry_version = "0.1.0",
            entries = listOf(
                ToolRegistryEntry("create_repo_from_archetype", "write_limited", false, allowed_secrets = emptyList()),
            ),
        )
        val provider = EnvSecretProvider(reg)
        assertNull(provider.getSecret("create_repo_from_archetype", "GITHUB_TOKEN"))
    }

    @Test
    fun `EnvSecretProvider returns null when tool allowed but secret not in list`() {
        val reg = ToolRegistry(
            registry_version = "0.1.0",
            entries = listOf(
                ToolRegistryEntry("create_github_repo", "privileged", true, allowed_secrets = listOf("GITHUB_TOKEN")),
            ),
        )
        val provider = EnvSecretProvider(reg)
        assertNull(provider.getSecret("create_github_repo", "OTHER_SECRET"))
    }

    @Test
    fun `EnvSecretProvider with null registry returns env value when present`() {
        val provider = EnvSecretProvider(null)
        val expected = System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
        assertEquals(expected, provider.getSecret("any_tool", "GITHUB_TOKEN"))
    }
}
