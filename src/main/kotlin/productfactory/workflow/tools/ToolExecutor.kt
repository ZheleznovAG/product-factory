package productfactory.workflow.tools

import productfactory.workflow.ExecutionContext

interface ToolExecutor {
    fun execute(runId: String, request: ToolCallRequest): ToolCallResult

    fun execute(runId: String, request: ToolCallRequest, executionContext: ExecutionContext): ToolCallResult {
        return execute(runId, request)
    }
}
