package productfactory.workflow

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkflowStateMachineTest {

    @Test
    fun `allows nominal transitions to done`() {
        val stateMachine = WorkflowStateMachine()
        stateMachine.transition("plan", WorkflowState.PLANNED)
        stateMachine.transition("generate", WorkflowState.GENERATED)
        stateMachine.transition("test", WorkflowState.TESTED)
        stateMachine.transition("secure", WorkflowState.SECURED)
        stateMachine.transition("stage", WorkflowState.STAGED)
        stateMachine.transition("finish", WorkflowState.DONE)
    }

    @Test
    fun `rejects invalid transition`() {
        val stateMachine = WorkflowStateMachine()
        assertFailsWith<IllegalArgumentException> {
            stateMachine.transition("invalid", WorkflowState.GENERATED)
        }
    }
}
