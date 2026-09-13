package productfactory.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRolePromptRegistryTest {
    @Test
    fun `renders planner template placeholders`() {
        val prompt = AgentRolePromptRegistry.renderSystem(
            role = AgentPromptRole.PLANNER,
            variables = mapOf(
                "allowed_tools" to "contract_validator",
                "downstream_role_rules" to "- Implementer: example",
            ),
        )

        assertTrue(prompt.contains("Role prompt template: planner/v2"))
        assertTrue(prompt.contains("Allowed tools for pipeline_plan.steps[].tool: contract_validator"))
        assertTrue(prompt.contains("Role responsibilities:"))
        assertTrue(prompt.contains("Downstream role contract:"))
    }

    @Test
    fun `returns stable template id per role`() {
        assertEquals("planner/v2", AgentRolePromptRegistry.templateFor(AgentPromptRole.PLANNER).id)
        assertEquals("codegen/v2", AgentRolePromptRegistry.templateFor(AgentPromptRole.CODEGEN).id)
    }

    @Test
    fun `contains required primary and extended roles`() {
        val roles = AgentRolePromptRegistry.roles()
        assertTrue(roles.containsAll(listOf(
            AgentPromptRole.PLANNER,
            AgentPromptRole.IMPLEMENTER,
            AgentPromptRole.TESTER,
            AgentPromptRole.REVIEWER,
            AgentPromptRole.ARCHITECT,
            AgentPromptRole.SECURITY_REVIEWER,
            AgentPromptRole.RELEASE_MANAGER,
        )))
    }
}
