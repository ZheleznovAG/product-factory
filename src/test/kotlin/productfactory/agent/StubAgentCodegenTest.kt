package productfactory.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubAgentCodegenTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stub codegen returns patch set proposals`() {
        val plannerResult = StubAgentPlanner().generate(
            AgentPlannerInput(goal = "Build catalog API"),
        )
        val result = StubAgentCodegen().generate(
            AgentCodegenInput(
                goal = "Build catalog API",
                constraints = listOf("kotlin", "ktor"),
                plannerArtifacts = plannerResult.artifacts,
            ),
        )
        val artifact = result.artifact

        assertEquals("codegen_patch_set", artifact.type)
        assertTrue(artifact.proposals.isNotEmpty())
        assertTrue(artifact.proposals.all { it.patch != null || it.structuredPatch != null })
        assertTrue(artifact.proposals.any { it.structuredPatch != null })
        assertTrue(artifact.proposals.all { it.filePath.isNotBlank() })
    }

    @Test
    fun `codegen patch set is serializable`() {
        val plannerResult = StubAgentPlanner().generate(
            AgentPlannerInput(goal = "Goal"),
        )
        val result = StubAgentCodegen().generate(
            AgentCodegenInput(
                goal = "Goal",
                plannerArtifacts = plannerResult.artifacts,
            ),
        )
        val encoded = result.artifact.toJson()
        val root = json.parseToJsonElement(encoded).jsonObject
        val proposals = root.getValue("proposals").jsonArray
        assertEquals("codegen_patch_set", root.getValue("type").jsonPrimitive.content)
        assertTrue(proposals.size >= 2)
    }
}
