package productfactory.workflow

import com.sun.net.httpserver.HttpServer
import io.opentelemetry.api.GlobalOpenTelemetry
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowRunnerPolicyTest {

    @Test
    fun `workflow is rejected when policy requires human approval`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                val response = """{"result":{"allow":true,"require_human_approval":true}}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }

        try {
            val auditLog = InMemoryAuditLog()
            val runner = WorkflowRunner(
                auditLog = auditLog,
                policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}"),
                approvalStore = InMemoryApprovalStore(),
                toolExecutor = DenyIfCalledToolExecutor(),
                tracer = GlobalOpenTelemetry.getTracer("test"),
            )

            val result = runner.run(
                runId = "run-approval",
                request = FactoryRunRequest(goal = "deploy to staging"),
            )

            assertEquals("rejected", result.status)
            assertEquals("Human approval required by policy", result.message)
            assertTrue(auditLog.events.any { it.eventType == "policy_check" && it.payload.contains(""""requireHumanApproval":true""") })
            assertTrue(auditLog.events.any { it.eventType == "approval_required" })
            assertTrue(auditLog.events.any { it.eventType == "state_changed" && it.payload.contains(""""newState":"FAILED"""") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `workflow is rejected when OPA returns allow false`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                val response = """{"result":{"allow":false,"require_human_approval":false,"reason":"goal not in allowlist"}}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }

        try {
            val auditLog = InMemoryAuditLog()
            val runner = WorkflowRunner(
                auditLog = auditLog,
                policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}"),
                approvalStore = InMemoryApprovalStore(),
                toolExecutor = DenyIfCalledToolExecutor(),
                tracer = GlobalOpenTelemetry.getTracer("test"),
            )

            val result = runner.run(
                runId = "run-deny",
                request = FactoryRunRequest(goal = "forbidden goal"),
            )

            assertEquals("rejected", result.status)
            assertTrue(result.message.isNotBlank())
            assertTrue(auditLog.events.any { it.eventType == "policy_check" && it.payload.contains(""""allowed":false""") })
            assertTrue(auditLog.events.any { it.eventType == "state_changed" && it.payload.contains(""""newState":"FAILED"""") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `approved runId can be retried and completed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                val response = """{"result":{"allow":true,"require_human_approval":true}}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }

        try {
            val approvals = InMemoryApprovalStore()
            val runner = WorkflowRunner(
                auditLog = InMemoryAuditLog(),
                policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}"),
                approvalStore = approvals,
                toolExecutor = AlwaysAllowToolExecutor(),
                tracer = GlobalOpenTelemetry.getTracer("test"),
            )
            val request = FactoryRunRequest(goal = "deploy to staging")

            val first = runner.run(runId = "run-retry", request = request)
            assertEquals("rejected", first.status)
            assertEquals(ApprovalStatus.PENDING, approvals.get("run-retry")?.status)

            approvals.approve(runId = "run-retry", decidedBy = "operator", comment = "safe to proceed")
            val second = runner.run(runId = "run-retry", request = request)
            assertEquals("accepted", second.status)
        } finally {
            server.stop(0)
        }
    }
}

private class DenyIfCalledToolExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(success = false, message = "Tool executor should not be called in this scenario")
    }
}

private class AlwaysAllowToolExecutor : ToolExecutor {
    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        return ToolCallResult(success = true, message = "ok")
    }
}

private class InMemoryAuditLog : AuditLog {
    val events = mutableListOf<LoggedEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedEvent(runId, eventType, payload)
    }
}

private data class LoggedEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)
