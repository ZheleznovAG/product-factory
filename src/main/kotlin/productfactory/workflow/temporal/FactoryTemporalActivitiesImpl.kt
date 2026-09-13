package productfactory.workflow.temporal

import productfactory.api.FactoryRunRequest
import productfactory.workflow.FactoryWorkflowExecution
import productfactory.workflow.GenerateStepResult
import productfactory.workflow.PlanStepResult
import productfactory.workflow.ToolStepResult

/**
 * Реализация [FactoryTemporalActivities]: делегирует в [FactoryWorkflowExecution].
 */
class FactoryTemporalActivitiesImpl(
    private val execution: FactoryWorkflowExecution,
) : FactoryTemporalActivities {

    override fun plan(runId: String, request: FactoryRunRequest): PlanStepResult {
        execution.logRequestReceived(runId, request)
        return execution.executePlan(runId, request)
    }

    override fun generate(runId: String, request: FactoryRunRequest, planResult: PlanStepResult): GenerateStepResult {
        return execution.executeGenerate(runId, request, planResult)
    }

    override fun executeTools(
        runId: String,
        request: FactoryRunRequest,
        generateResult: GenerateStepResult,
    ): ToolStepResult {
        return execution.executeTools(runId, request, generateResult)
    }

    override fun runTests(runId: String) {
        execution.executeTests(runId)
    }

    override fun runSecurity(runId: String) {
        execution.executeSecurity(runId)
    }

    override fun stage(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult) {
        execution.executeStage(runId, request, toolResult)
    }

    override fun finish(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult) {
        execution.executeFinish(runId, request, toolResult)
    }
}
