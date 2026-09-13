package productfactory

import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import productfactory.api.ApprovalDecisionResponse
import productfactory.policy.PolicyCheck
import productfactory.workflow.ApprovalRecord
import productfactory.workflow.ApprovalStatus
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.InMemoryApprovalStore
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FactoryApprovalApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `approval API can approve and fetch existing request`() = testApplication {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                val response = """{"result":{"allow":true,"require_human_approval":true}}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }

        try {
            val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
            val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
            val approvals = InMemoryApprovalStore()

            application {
                module(
                    auditLog = FileAuditLog(auditFile.absolutePath),
                    policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}"),
                    approvalStore = approvals,
                    artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
                )
            }
            val runId = "run-approval-api"
            approvals.upsertPending(
                runId = runId,
                reason = "human approval required",
                proposedActions = listOf("tool:create_repo_from_archetype"),
            )

            val approve = client.post("/factory/approvals/$runId/approve") {
                contentType(ContentType.Application.Json)
                setBody("""{"decidedBy":"qa-operator","comment":"approved"}""")
            }
            assertEquals(HttpStatusCode.OK, approve.status)
            val approveBody = json.decodeFromString<ApprovalDecisionResponse>(approve.body<String>())
            assertEquals("approved", approveBody.status)
            assertEquals(runId, approveBody.runId)

            val getApproval = client.get("/factory/approvals/$runId")
            assertEquals(HttpStatusCode.OK, getApproval.status)
            val approvalBody = json.decodeFromString<ApprovalRecord>(getApproval.body<String>())
            assertEquals(runId, approvalBody.runId)
            assertEquals(ApprovalStatus.APPROVED, approvalBody.status)
        } finally {
            server.stop(0)
        }
    }
}
