package productfactory.workflow.temporal

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import productfactory.api.FactoryRunRequest
import java.time.Duration

/**
 * Реализация [FactoryTemporalWorkflow]: последовательный вызов activities.
 * Детерминированная логика — только вызовы activities, без I/O.
 */
class FactoryTemporalWorkflowImpl : FactoryTemporalWorkflow {

    private val activityOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(15))
        .setRetryOptions(
            io.temporal.common.RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .setInitialInterval(Duration.ofSeconds(5))
                .setBackoffCoefficient(2.0)
                .build(),
        )
        .build()

    private val activities: FactoryTemporalActivities = Workflow.newActivityStub(
        FactoryTemporalActivities::class.java,
        activityOptions,
    )

    override fun run(runId: String, request: FactoryRunRequest) {
        val planResult = activities.plan(runId, request)
        val genResult = activities.generate(runId, request, planResult)
        val toolResult = activities.executeTools(runId, request, genResult)
        if (request.dryRun) {
            return
        }
        activities.runTests(runId)
        activities.runSecurity(runId)
        activities.stage(runId, request, toolResult)
        activities.finish(runId, request, toolResult)
    }
}
