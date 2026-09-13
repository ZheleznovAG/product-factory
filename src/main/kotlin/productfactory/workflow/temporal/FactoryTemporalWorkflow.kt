package productfactory.workflow.temporal

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import productfactory.api.FactoryRunRequest
import productfactory.workflow.GenerateStepResult
import productfactory.workflow.PlanStepResult
import productfactory.workflow.ToolStepResult
import java.time.Duration

/**
 * Temporal Workflow: последовательное выполнение шагов pipeline через activities.
 * При падении activity Temporal повторяет её (retry); состояние сохраняется в истории.
 */
@WorkflowInterface
interface FactoryTemporalWorkflow {

    @WorkflowMethod
    fun run(runId: String, request: FactoryRunRequest)
}
