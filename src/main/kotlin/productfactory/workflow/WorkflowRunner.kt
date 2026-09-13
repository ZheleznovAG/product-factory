package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import productfactory.agent.AgentCodegen
import productfactory.agent.AgentPlanner
import productfactory.agent.StubAgentCodegen
import productfactory.agent.StubAgentPlanner
import productfactory.api.FactoryRunRequest
import productfactory.config.FactoryEnvironment
import productfactory.contracts.ContractValidator
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolExecutor
import java.nio.file.Path
import kotlin.runCatching
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Оркестратор pipeline: state machine + вызов шагов через [FactoryWorkflowExecution].
 * При заданном TEMPORAL_ADDRESS run можно выполнять через Temporal (Worker + Workflow);
 * иначе — синхронно здесь (persist/retry через Temporal при использовании Worker).
 */
class WorkflowRunner(
    private val auditLog: AuditLog,
    private val policyCheck: PolicyCheck,
    private val approvalStore: ApprovalStore,
    private val toolExecutor: ToolExecutor,
    private val agentPlanner: AgentPlanner = StubAgentPlanner(),
    private val agentCodegen: AgentCodegen = StubAgentCodegen(),
    private val artifactRegistry: ArtifactRegistry = NoopArtifactRegistry,
    private val contractValidator: ContractValidator? = null,
    private val contractsDirectory: Path? = null,
    private val tracer: Tracer = GlobalOpenTelemetry.getTracer("productfactory.workflow"),
    private val environment: FactoryEnvironment? = null,
    private val environmentProvider: EnvironmentProvider = StubEnvironmentProvider(),
    private val toolRegistryVersion: String? = null,
    private val sprintAdrDraftGenerator: SprintAdrDraftGenerator = NoopSprintAdrDraftGenerator,
) {
    private val execution = FactoryWorkflowExecution(
        auditLog = auditLog,
        policyCheck = policyCheck,
        approvalStore = approvalStore,
        toolExecutor = toolExecutor,
        agentPlanner = agentPlanner,
        agentCodegen = agentCodegen,
        artifactRegistry = artifactRegistry,
        contractValidator = contractValidator,
        contractsDirectory = contractsDirectory,
        tracer = tracer,
        environment = environment,
        environmentProvider = environmentProvider,
        toolRegistryVersion = toolRegistryVersion,
    )

    fun run(runId: String, request: FactoryRunRequest): RunResult {
        return withSpan("factory_run", runId) { rootSpan ->
            rootSpan.setAttribute("factory.goal", request.goal)
            val runStartedAtMs = System.currentTimeMillis()
            val stateMachine = WorkflowStateMachine()
            execution.logRequestReceived(runId, request)

            val result = runCatching {
                val planResult = execution.executePlan(runId, request)
                transitionState(runId, stateMachine, WorkflowStepId.PLAN_WORKFLOW, WorkflowState.PLANNED)

                val genResult = execution.executeGenerate(runId, request, planResult)
                val toolResult = execution.executeTools(runId, request, genResult)
                transitionState(runId, stateMachine, WorkflowStepId.GENERATE_ARTIFACTS, WorkflowState.GENERATED)
                if (request.dryRun) {
                    auditLog.log(
                        runId,
                        "dry_run_completed",
                        buildJsonObject {
                            put("dry_run", true)
                            put("message", "Tool side-effects were not executed. Review tool_call_dry_run_plan in audit.")
                            put("planned_steps", toolResult.dryRunPlan.size)
                        }.toString(),
                    )
                    transitionState(runId, stateMachine, WorkflowStepId.FINISH_WORKFLOW, WorkflowState.DONE)
                    return@runCatching RunResult(
                        "accepted",
                        "Dry-run completed. Side-effects were not executed; see audit for plan and policy results.",
                    )
                }

                execution.executeTests(runId)
                transitionState(runId, stateMachine, WorkflowStepId.RUN_TESTS, WorkflowState.TESTED)

                execution.executeSecurity(runId)
                transitionState(runId, stateMachine, WorkflowStepId.RUN_SECURITY_CHECKS, WorkflowState.SECURED)

                execution.executeStage(runId, request, toolResult)
                transitionState(runId, stateMachine, WorkflowStepId.STAGE_ARTIFACTS, WorkflowState.STAGED)

                execution.executeSloGate(
                    runId = runId,
                    request = request,
                    generationTimeMs = System.currentTimeMillis() - runStartedAtMs,
                )

                execution.executeFinish(runId, request, toolResult)
                runCatching {
                    sprintAdrDraftGenerator.generate(runId = runId, request = request)
                }.onSuccess { generated ->
                    if (generated != null) {
                        auditLog.log(
                            runId,
                            "adr_markdown_generated",
                            buildJsonObject {
                                put("runId", runId)
                                put("path", generated.path.toString())
                                put("title", generated.title)
                            }.toString(),
                        )
                    }
                }.onFailure { error ->
                    auditLog.log(
                        runId,
                        "adr_markdown_generation_failed",
                        buildJsonObject {
                            put("runId", runId)
                            put("message", error.message ?: "Unable to generate ADR markdown draft")
                        }.toString(),
                    )
                }
                transitionState(runId, stateMachine, WorkflowStepId.FINISH_WORKFLOW, WorkflowState.DONE)

                RunResult("accepted", "Workflow completed with sandbox tool executor.")
            }.getOrElse { throwable ->
                transitionToFailed(runId, stateMachine)
                if (throwable is WorkflowRejectedException) {
                    RunResult("rejected", throwable.message ?: "Workflow rejected")
                } else {
                    rootSpan.recordException(throwable)
                    rootSpan.setStatus(StatusCode.ERROR)
                    auditLog.log(
                        runId,
                        "workflow_error",
                        buildJsonObject {
                            put("message", throwable.message ?: "Unexpected workflow error")
                        }.toString(),
                    )
                    RunResult("rejected", "Workflow failed")
                }
            }

            rootSpan.setAttribute("factory.status", result.status)
            result
        }
    }

    private fun transitionState(
        runId: String,
        stateMachine: WorkflowStateMachine,
        stepId: String,
        newState: WorkflowState,
    ) {
        val state = stateMachine.transition(stepId, newState)
        val role = WorkflowStepRoleMapping.roleForStep(stepId)
        val roleAuditValue = if (stepId == WorkflowStepId.STAGE_ARTIFACTS) "" else role?.auditValue
        auditLog.logStateChange(
            runId = runId,
            stepId = stepId,
            newState = state,
            role = role,
            roleAuditValue = roleAuditValue,
        )
    }

    private fun transitionToFailed(runId: String, stateMachine: WorkflowStateMachine) {
        if (stateMachine.isTerminal()) return
        transitionState(runId, stateMachine, WorkflowStepId.WORKFLOW_FAILED, WorkflowState.FAILED)
    }

    private inline fun <T> withSpan(name: String, runId: String, block: (Span) -> T): T {
        val span = tracer.spanBuilder(name)
            .setAttribute("factory.run_id", runId)
            .startSpan()
        return try {
            span.makeCurrent().use { block(span) }
        } catch (throwable: Throwable) {
            span.recordException(throwable)
            span.setStatus(StatusCode.ERROR)
            throw throwable
        } finally {
            span.end()
        }
    }
}

data class RunResult(val status: String, val message: String)
