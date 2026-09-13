package productfactory.workflow

/**
 * Явные роли агентного pipeline для audit и маршрутизации шагов.
 */
enum class WorkflowAgentRole(val auditValue: String) {
    PLANNER("Planner"),
    IMPLEMENTER("Implementer"),
    TESTER("Tester"),
    REVIEWER("Reviewer"),
}

/**
 * Идентификаторы шагов workflow.
 */
object WorkflowStepId {
    const val SELECT_INTENT_CANDIDATE = "select_intent_candidate"
    const val PLAN_WORKFLOW = "plan_workflow"
    const val GENERATE_ARTIFACTS = "generate_artifacts"
    const val RUN_TESTS = "run_tests"
    const val RUN_SECURITY_CHECKS = "run_security_checks"
    const val STAGE_ARTIFACTS = "stage_artifacts"
    const val FINISH_WORKFLOW = "finish_workflow"
    const val WORKFLOW_FAILED = "workflow_failed"
}

/**
 * Привязка шагов workflow к роли.
 */
object WorkflowStepRoleMapping {
    fun roleForStep(stepId: String): WorkflowAgentRole? = when (stepId) {
        WorkflowStepId.PLAN_WORKFLOW -> WorkflowAgentRole.PLANNER
        WorkflowStepId.GENERATE_ARTIFACTS -> WorkflowAgentRole.IMPLEMENTER
        WorkflowStepId.RUN_TESTS -> WorkflowAgentRole.TESTER
        WorkflowStepId.RUN_SECURITY_CHECKS -> WorkflowAgentRole.REVIEWER
        else -> null
    }
}
