package productfactory.workflow

import productfactory.agent.AgentPlannerArtifacts
import productfactory.agent.CodegenPatchSetArtifact

/**
 * Контекст run: накопленные артефакты по шагам pipeline (planner → codegen → qa → sec).
 * Context pack для передачи в следующие шаги или будущим агентам; единая точка доступа к результатам run.
 */
data class RunContext(
    val runId: String,
    val plannerArtifacts: AgentPlannerArtifacts? = null,
    val codegenArtifact: CodegenPatchSetArtifact? = null,
    val qaOutput: QaGateOutput? = null,
    val secOutput: SecGateOutput? = null,
) {
    fun withPlannerArtifacts(artifacts: AgentPlannerArtifacts) = copy(plannerArtifacts = artifacts)
    fun withCodegenArtifact(artifact: CodegenPatchSetArtifact) = copy(codegenArtifact = artifact)
    fun withQaOutput(output: QaGateOutput) = copy(qaOutput = output)
    fun withSecOutput(output: SecGateOutput) = copy(secOutput = output)
}
