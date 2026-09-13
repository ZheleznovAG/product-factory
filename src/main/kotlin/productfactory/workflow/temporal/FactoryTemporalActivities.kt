package productfactory.workflow.temporal

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import productfactory.api.FactoryRunRequest
import productfactory.workflow.GenerateStepResult
import productfactory.workflow.PlanStepResult
import productfactory.workflow.ToolStepResult

/**
 * Activities для Factory pipeline. Каждый шаг — отдельная activity (retry на уровне шага).
 */
@ActivityInterface
interface FactoryTemporalActivities {

    @ActivityMethod
    fun plan(runId: String, request: FactoryRunRequest): PlanStepResult

    @ActivityMethod
    fun generate(runId: String, request: FactoryRunRequest, planResult: PlanStepResult): GenerateStepResult

    @ActivityMethod
    fun executeTools(runId: String, request: FactoryRunRequest, generateResult: GenerateStepResult): ToolStepResult

    @ActivityMethod
    fun runTests(runId: String): Unit

    @ActivityMethod
    fun runSecurity(runId: String): Unit

    @ActivityMethod
    fun stage(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult): Unit

    @ActivityMethod
    fun finish(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult): Unit
}
