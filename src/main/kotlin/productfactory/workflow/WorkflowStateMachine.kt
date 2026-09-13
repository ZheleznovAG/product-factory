package productfactory.workflow

enum class WorkflowState {
    NEW,
    PLANNED,
    GENERATED,
    TESTED,
    SECURED,
    STAGED,
    DONE,
    FAILED,
}

class WorkflowStateMachine {
    private var currentState: WorkflowState = WorkflowState.NEW

    fun transition(stepId: String, newState: WorkflowState): WorkflowState {
        val allowed = allowedTransitions[currentState].orEmpty()
        require(newState in allowed) {
            "Invalid workflow transition at step=$stepId: $currentState -> $newState"
        }
        currentState = newState
        return currentState
    }

    fun isTerminal(): Boolean {
        return currentState == WorkflowState.DONE || currentState == WorkflowState.FAILED
    }

    companion object {
        private val allowedTransitions: Map<WorkflowState, Set<WorkflowState>> = mapOf(
            WorkflowState.NEW to setOf(WorkflowState.PLANNED, WorkflowState.FAILED),
            WorkflowState.PLANNED to setOf(WorkflowState.GENERATED, WorkflowState.FAILED),
            WorkflowState.GENERATED to setOf(WorkflowState.TESTED, WorkflowState.DONE, WorkflowState.FAILED),
            WorkflowState.TESTED to setOf(WorkflowState.SECURED, WorkflowState.FAILED),
            WorkflowState.SECURED to setOf(WorkflowState.STAGED, WorkflowState.FAILED),
            WorkflowState.STAGED to setOf(WorkflowState.DONE, WorkflowState.FAILED),
            WorkflowState.DONE to emptySet(),
            WorkflowState.FAILED to emptySet(),
        )
    }
}
