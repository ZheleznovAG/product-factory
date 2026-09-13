package productfactory.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class StructuredCodePatch(
    val path: String,
    val old_content: String? = null,
    val new_content: String,
)

@Serializable
data class CodegenProposal(
    val filePath: String,
    val summary: String,
    val patch: String? = null,
    val structuredPatch: StructuredCodePatch? = null,
) {
    init {
        require(patch != null || structuredPatch != null) {
            "CodegenProposal requires either patch or structuredPatch"
        }
    }
}

@Serializable
data class CodegenPatchSetArtifact(
    val type: String = "codegen_patch_set",
    val proposals: List<CodegenProposal>,
)

data class AgentCodegenInput(
    val goal: String,
    val constraints: List<String> = emptyList(),
    val plannerArtifacts: AgentPlannerArtifacts,
    val productSpec: String? = null,
    val contracts: Map<String, String> = emptyMap(),
)

/** Результат кодогена: артефакт и потребление токенов LLM (0 при stub). */
data class AgentCodegenResult(
    val artifact: CodegenPatchSetArtifact,
    val tokenUsage: Int = 0,
)

interface AgentCodegen {
    fun generate(input: AgentCodegenInput): AgentCodegenResult
}

/**
 * Scale-phase codegen stub: creates deterministic patch proposals only.
 * No merge/commit or other side-effects are performed here by design.
 * В stub-режиме patch для apply не задаём: архетип уже содержит README, git apply к нему ломается; apply_patch вызывается только при реальном LLM-кодогене.
 */
class StubAgentCodegen : AgentCodegen {
    override fun generate(input: AgentCodegenInput): AgentCodegenResult {
        val normalizedGoal = input.goal.trim().ifBlank { "unspecified goal" }
        val pipelineSummary = input.plannerArtifacts.pipelinePlan.steps.joinToString(", ") { it.id }
        return AgentCodegenResult(
            artifact = CodegenPatchSetArtifact(
            proposals = listOf(
                CodegenProposal(
                    filePath = "README.md",
                    summary = "Add initial service readme aligned with planner output (stub: no patch to apply).",
                    patch = null,
                    structuredPatch = StructuredCodePatch(
                        path = "README.md",
                        old_content = null,
                        new_content = "# Generated Service\nGoal: $normalizedGoal\n",
                    ),
                ),
                CodegenProposal(
                    filePath = "src/test/kotlin/SmokeTest.kt",
                    summary = "Propose smoke test scaffold based on planner steps: $pipelineSummary.",
                    structuredPatch = StructuredCodePatch(
                        path = "src/test/kotlin/SmokeTest.kt",
                        old_content = null,
                        new_content = """
                            package generated

                            import kotlin.test.Test
                            import kotlin.test.assertTrue

                            class SmokeTest {
                                @Test
                                fun smoke() {
                                    assertTrue("${'$'}pipelineSummary".isNotBlank())
                                }
                            }
                        """.trimIndent(),
                    ),
                ),
            ),
            ),
            tokenUsage = 0,
        )
    }
}

fun CodegenPatchSetArtifact.toJson(): String = Json {
    prettyPrint = false
    encodeDefaults = true
}.encodeToString(this)
