package productfactory.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubAgentPlannerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stub planner returns required artifact types`() {
        val planner = StubAgentPlanner()

        val result = planner.generate(
            AgentPlannerInput(
                goal = "Build catalog API",
                constraints = listOf("kotlin", "ktor"),
                productSpec = """{"kind":"ProductSpec"}""",
                contracts = mapOf("product.yaml" to "apiVersion: productfactory.io/v1"),
            ),
        )
        val artifacts = result.artifacts

        val pipeline = json.parseToJsonElement(artifacts.pipelinePlanJson()).jsonObject
        val adr = json.parseToJsonElement(artifacts.adrDraftJson()).jsonObject
        val testPlan = json.parseToJsonElement(artifacts.testPlanJson()).jsonObject

        assertEquals("pipeline_plan", pipeline.getValue("type").jsonPrimitive.content)
        assertEquals("adr_draft", adr.getValue("type").jsonPrimitive.content)
        assertEquals("test_plan", testPlan.getValue("type").jsonPrimitive.content)
        assertTrue(artifacts.pipelinePlan.steps.any { it.id == "validate_contracts" })
        assertTrue(artifacts.adrDraft.decision.contains("deterministic execution core"))
        assertTrue(artifacts.testPlan.coverage_targets.unit_percent >= 70)
        assertEquals(
            "pipeline_plan",
            artifacts.pipelinePlan.type,
        )
    }

    @Test
    fun `planner artifacts are serializable`() {
        val result = StubAgentPlanner().generate(AgentPlannerInput(goal = "Goal"))
        val artifacts = result.artifacts
        val encoded = Json.encodeToString(artifacts.testPlan)
        assertTrue(encoded.contains(""""scope""""))
    }
}
