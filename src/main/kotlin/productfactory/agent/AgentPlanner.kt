package productfactory.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AgentPlannerInput(
    val goal: String,
    val constraints: List<String> = emptyList(),
    val productSpec: String? = null,
    val contracts: Map<String, String> = emptyMap(),
)

@Serializable
data class PipelinePlanStep(
    val id: String,
    val description: String,
    val tool: String,
    val depends_on: List<String> = emptyList(),
)

@Serializable
data class PipelinePlanArtifact(
    val type: String = "pipeline_plan",
    val steps: List<PipelinePlanStep>,
)

@Serializable
data class AdrDraftArtifact(
    val type: String = "adr_draft",
    val context: String,
    val decision: String,
    val consequences: List<String>,
)

@Serializable
data class TestCasePlan(
    val id: String,
    val description: String,
    val type: String,
)

@Serializable
data class CoverageTargets(
    val unit_percent: Int,
    val integration_required: Boolean,
    val security_required: Boolean,
)

@Serializable
data class TestPlanArtifact(
    val type: String = "test_plan",
    val scope: String,
    val cases: List<TestCasePlan>,
    val coverage_targets: CoverageTargets,
)

data class AgentPlannerArtifacts(
    val pipelinePlan: PipelinePlanArtifact,
    val adrDraft: AdrDraftArtifact,
    val testPlan: TestPlanArtifact,
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    fun pipelinePlanJson(): String = json.encodeToString(pipelinePlan)

    fun adrDraftJson(): String = json.encodeToString(adrDraft)

    fun testPlanJson(): String = json.encodeToString(testPlan)
}

/** Результат планировщика: артефакты и потребление токенов LLM (0 при stub). */
data class AgentPlannerResult(
    val artifacts: AgentPlannerArtifacts,
    val tokenUsage: Int = 0,
    val rationale: String,
)

interface AgentPlanner {
    fun generate(input: AgentPlannerInput): AgentPlannerResult
}

/**
 * Scale-phase planner stub: returns deterministic artifacts that follow contracts/agent_outputs.schema.json.
 * LLM-backed implementation can replace this later without changing workflow boundaries.
 */
class StubAgentPlanner : AgentPlanner {
    override fun generate(input: AgentPlannerInput): AgentPlannerResult {
        val normalizedGoal = input.goal.trim().ifBlank { "unspecified goal" }
        val constraintsSummary = if (input.constraints.isEmpty()) {
            "No explicit constraints provided."
        } else {
            "Constraints: ${input.constraints.joinToString(separator = "; ")}."
        }
        val productSpecHint = input.productSpec?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "Product spec provided by caller." }
            ?: "Product spec was not provided explicitly."
        val contractsHint = if (input.contracts.isEmpty()) {
            "Contracts bundle is empty."
        } else {
            "Contracts bundle includes: ${input.contracts.keys.sorted().joinToString(", ")}."
        }

        return AgentPlannerResult(
            artifacts = AgentPlannerArtifacts(
                pipelinePlan = PipelinePlanArtifact(
                steps = listOf(
                    PipelinePlanStep(
                        id = "validate_contracts",
                        description = "Validate product contracts and constraints before generation.",
                        tool = "contract_validator",
                    ),
                    PipelinePlanStep(
                        id = "generate_repo",
                        description = "Create repository from approved archetype for the requested goal.",
                        tool = "create_repo_from_archetype",
                        depends_on = listOf("validate_contracts"),
                    ),
                    PipelinePlanStep(
                        id = "run_quality_gates",
                        description = "Execute test and security gates and collect attestations.",
                        tool = "quality_gate_runner",
                        depends_on = listOf("generate_repo"),
                    ),
                ),
            ),
            adrDraft = AdrDraftArtifact(
                context = "Goal: $normalizedGoal $constraintsSummary $productSpecHint $contractsHint",
                decision = "Use deterministic execution core with agent-generated proposals for planning artifacts.",
                consequences = listOf(
                    "Improves auditability through structured planner artifacts.",
                    "Requires schema governance for planner output evolution.",
                    "Allows future swap to LLM planner without changing workflow control plane.",
                ),
            ),
            testPlan = TestPlanArtifact(
                scope = "Workflow planning and policy-gated execution for $normalizedGoal",
                cases = listOf(
                    TestCasePlan(
                        id = "policy-deny",
                        description = "Reject run when policy allow=false.",
                        type = "integration",
                    ),
                    TestCasePlan(
                        id = "planner-contract",
                        description = "Validate planner artifacts against agent outputs schema.",
                        type = "unit",
                    ),
                    TestCasePlan(
                        id = "tool-idempotency",
                        description = "Ensure idempotent tool execution is replay-safe.",
                        type = "integration",
                    ),
                ),
                coverage_targets = CoverageTargets(
                    unit_percent = 70,
                    integration_required = true,
                    security_required = true,
                ),
            ),
            ),
            tokenUsage = 0,
            rationale = "Deterministic stub planner selected a safe 3-step pipeline (contracts -> repo generation -> quality gates) for goal '$normalizedGoal'.",
        )
    }
}
