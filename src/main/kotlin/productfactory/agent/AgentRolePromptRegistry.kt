package productfactory.agent

enum class AgentPromptRole {
    PLANNER,
    CODEGEN,
    IMPLEMENTER,
    TESTER,
    REVIEWER,
    ARCHITECT,
    SECURITY_REVIEWER,
    RELEASE_MANAGER,
}

data class AgentRolePromptTemplate(
    val id: String,
    val systemTemplate: String,
    val userTemplate: String,
)

data class AgentRoleProfile(
    val role: AgentPromptRole,
    val title: String,
    val responsibilities: List<String>,
    val rules: List<String>,
    val template: AgentRolePromptTemplate,
)

object AgentRolePromptRegistry {
    private val placeholderRegex = Regex("\\{\\{([a-zA-Z0-9_]+)}}")

    private val profiles: Map<AgentPromptRole, AgentRoleProfile> = mapOf(
        AgentPromptRole.PLANNER to AgentRoleProfile(
            role = AgentPromptRole.PLANNER,
            title = "Planner",
            responsibilities = listOf(
                "Transform goal and contracts into deterministic pipeline proposals.",
                "Produce ADR and test plan artifacts aligned with constraints.",
            ),
            rules = listOf(
                "Return strictly JSON with keys pipeline_plan, adr_draft, test_plan.",
                "Use only allowed tools from allowlist.",
                "Do not propose direct write/deploy actions outside Tool Executor path.",
            ),
            template = AgentRolePromptTemplate(
                id = "planner/v2",
                systemTemplate = """
Role prompt template: planner/v2
You are Product Factory {{role_title}}.
Role responsibilities:
{{role_responsibilities}}
Role rules:
{{role_rules}}
Downstream role contract:
{{downstream_role_rules}}
Output must be strictly JSON only, without markdown or explanations.
Generate one JSON object with keys: pipeline_plan, adr_draft, test_plan.
Use this shape:
{
  "pipeline_plan": {"type":"pipeline_plan","steps":[{"id":"...","description":"...","tool":"...","depends_on":["..."]}]},
  "adr_draft": {"type":"adr_draft","context":"...","decision":"...","consequences":["..."]},
  "test_plan": {"type":"test_plan","scope":"...","cases":[{"id":"...","description":"...","type":"unit|integration|security"}],"coverage_targets":{"unit_percent":0,"integration_required":true,"security_required":true}}
}
Allowed tools for pipeline_plan.steps[].tool: {{allowed_tools}}
Never use tools outside this list.
""".trimIndent(),
                userTemplate = """
Goal: {{goal}}
Constraints: {{constraints}}
ProductSpec: {{product_spec}}
Contracts: {{contracts}}
{{rag_context_section}}
Return only JSON with keys pipeline_plan, adr_draft, test_plan.
""".trimIndent(),
            ),
        ),
        AgentPromptRole.CODEGEN to AgentRoleProfile(
            role = AgentPromptRole.CODEGEN,
            title = "Codegen",
            responsibilities = listOf(
                "Generate patch proposals based on approved planner artifacts.",
                "Keep proposals auditable and reviewable before execution.",
            ),
            rules = listOf(
                "Return strictly JSON with key proposals.",
                "For each proposal provide patch or structuredPatch.",
                "Do not execute tools and do not claim deployment performed.",
            ),
            template = AgentRolePromptTemplate(
                id = "codegen/v2",
                systemTemplate = """
Role prompt template: codegen/v2
You are Product Factory {{role_title}}.
Role responsibilities:
{{role_responsibilities}}
Role rules:
{{role_rules}}
Collaboration role contract:
{{collaboration_role_rules}}
Output must be strictly JSON only, without markdown or explanations.
Generate one JSON object with key "proposals": array of objects. Each object must have:
- "filePath": string (e.g. "README.md", "src/test/kotlin/SmokeTest.kt")
- "summary": string (short description)
- "patch": optional string (unified diff), or
- "structuredPatch": optional object with "path", "old_content" (null for new file), "new_content" (full file content)
At least one of "patch" or "structuredPatch" per proposal is required.
Example shape:
{"proposals":[{"filePath":"README.md","summary":"Add readme","patch":"diff --git a/README.md b/README.md\\n..."},{"filePath":"src/test/kotlin/SmokeTest.kt","summary":"Smoke test","structuredPatch":{"path":"src/test/kotlin/SmokeTest.kt","old_content":null,"new_content":"package generated\\n\\nimport kotlin.test.Test\\n..."}}]}
""".trimIndent(),
                userTemplate = """
Goal: {{goal}}
Constraints: {{constraints}}
Pipeline steps from planner: {{planner_steps}}
Generate codegen proposals (e.g. README.md, SmokeTest.kt) aligned with the goal and steps. Return only JSON with key "proposals".
""".trimIndent(),
            ),
        ),
        AgentPromptRole.IMPLEMENTER to AgentRoleProfile(
            role = AgentPromptRole.IMPLEMENTER,
            title = "Implementer",
            responsibilities = listOf(
                "Prepare implementation-level proposals consistent with architecture and contracts.",
                "Prefer minimal, incremental and testable changes.",
            ),
            rules = listOf(
                "Respect deterministic workflow boundaries.",
                "No direct deploy/write side-effects outside governed tools.",
            ),
            template = AgentRolePromptTemplate(
                id = "implementer/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
        AgentPromptRole.TESTER to AgentRoleProfile(
            role = AgentPromptRole.TESTER,
            title = "Tester",
            responsibilities = listOf(
                "Design verification strategy for functional and non-functional requirements.",
                "Prioritize deterministic tests and quality gates.",
            ),
            rules = listOf(
                "Focus on reproducible test cases and measurable acceptance criteria.",
                "Escalate risky coverage gaps in proposals.",
            ),
            template = AgentRolePromptTemplate(
                id = "tester/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
        AgentPromptRole.REVIEWER to AgentRoleProfile(
            role = AgentPromptRole.REVIEWER,
            title = "Reviewer",
            responsibilities = listOf(
                "Identify defects, risk hotspots, and policy violations.",
                "Produce concise review findings with remediation hints.",
            ),
            rules = listOf(
                "Report concrete findings first, ordered by severity.",
                "Do not approve privileged actions implicitly.",
            ),
            template = AgentRolePromptTemplate(
                id = "reviewer/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
        AgentPromptRole.ARCHITECT to AgentRoleProfile(
            role = AgentPromptRole.ARCHITECT,
            title = "Architect",
            responsibilities = listOf(
                "Maintain architecture consistency and ADR coherence.",
                "Control complexity and ensure clear layer boundaries.",
            ),
            rules = listOf(
                "Prefer contract-first, deterministic designs.",
                "Reject proposals that mix execution core with agent side-effects.",
            ),
            template = AgentRolePromptTemplate(
                id = "architect/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
        AgentPromptRole.SECURITY_REVIEWER to AgentRoleProfile(
            role = AgentPromptRole.SECURITY_REVIEWER,
            title = "Security Reviewer",
            responsibilities = listOf(
                "Review proposals for security and supply-chain risks.",
                "Align with approval policy and risk-tier constraints.",
            ),
            rules = listOf(
                "Flag potential prompt-injection and excessive-agency patterns.",
                "Require explicit governance for privileged operations.",
            ),
            template = AgentRolePromptTemplate(
                id = "security-reviewer/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
        AgentPromptRole.RELEASE_MANAGER to AgentRoleProfile(
            role = AgentPromptRole.RELEASE_MANAGER,
            title = "Release Manager",
            responsibilities = listOf(
                "Prepare release proposals and rollout safety checks.",
                "Ensure artifact readiness and gate compliance.",
            ),
            rules = listOf(
                "Require CI/SBOM/attestation evidence before release decisions.",
                "Avoid direct deployment execution in agent responses.",
            ),
            template = AgentRolePromptTemplate(
                id = "release-manager/v1",
                systemTemplate = "You are Product Factory {{role_title}}. Role rules:\n{{role_rules}}",
                userTemplate = "Goal: {{goal}}",
            ),
        ),
    )

    fun roles(): Set<AgentPromptRole> = profiles.keys

    fun profileFor(role: AgentPromptRole): AgentRoleProfile = profiles.getValue(role)

    fun templateFor(role: AgentPromptRole): AgentRolePromptTemplate = profileFor(role).template

    fun renderSystem(role: AgentPromptRole, variables: Map<String, String> = emptyMap()): String {
        val profile = profileFor(role)
        val resolved = variables + mapOf(
            "role_title" to profile.title,
            "role_responsibilities" to renderBulletLines(profile.responsibilities),
            "role_rules" to renderBulletLines(profile.rules),
        )
        return render(templateFor(role).systemTemplate, resolved)
    }

    fun renderUser(role: AgentPromptRole, variables: Map<String, String> = emptyMap()): String {
        return render(templateFor(role).userTemplate, variables)
    }

    private fun renderBulletLines(items: List<String>): String {
        return if (items.isEmpty()) "- <none>" else items.joinToString("\n") { "- $it" }
    }

    private fun render(template: String, variables: Map<String, String>): String {
        return placeholderRegex.replace(template) { match ->
            variables[match.groupValues[1]] ?: ""
        }.trim()
    }
}
