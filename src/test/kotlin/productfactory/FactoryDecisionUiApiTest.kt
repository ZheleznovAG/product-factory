package productfactory

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.InMemoryApprovalStore
import productfactory.workflow.InMemoryAskUserStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class FactoryDecisionUiApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `factory ui endpoint returns html page`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.get("/factory/ui")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]?.contains("text/html") == true)
        assertTrue(response.bodyAsText().contains("Product Factory: Decisions"))
    }

    @Test
    fun `decision context returns plan risks and options`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val approvals = InMemoryApprovalStore()
        val askUserStore = InMemoryAskUserStore()
        val runId = "run-decision-context"
        val auditLog = FileAuditLog(auditFile.absolutePath)
        auditLog.log(
            runId = runId,
            eventType = "pipeline_plan",
            payload = """{"type":"pipeline_plan","steps":[{"id":"s1","description":"build"}]}""",
        )
        auditLog.log(
            runId = runId,
            eventType = "approval_required",
            payload = """{"reason":"manual approval required","source":"policy"}""",
        )
        approvals.upsertPending(
            runId = runId,
            reason = "manual approval required",
            proposedActions = listOf("risk_tier:high"),
        )
        askUserStore.saveQuestion(
            runId = runId,
            question = "Choose variant",
            options = listOf("A", "B"),
        )

        application {
            module(
                auditLog = auditLog,
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.get("/factory/runs/$runId/decision-context")
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(runId, payload.getValue("runId").jsonPrimitive.content)
        assertTrue(payload.getValue("plan").toString().contains("pipeline_plan"))
        assertTrue(payload.getValue("risks").toString().contains("manual approval required"))
        assertTrue(payload.getValue("options").toString().contains("A"))
    }

    @Test
    fun `sprint point endpoint writes audit event`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val runId = "run-sprint-point"
        val response = client.post("/factory/runs/$runId/sprint-point") {
            contentType(ContentType.Application.Json)
            setBody("""{"stage":"ws3-v1","decision":"hold","note":"Need security check"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val lines = auditFile.readLines()
        assertTrue(lines.any { it.contains("sprint_point_decision") && it.contains("ws3-v1") && it.contains("hold") })
    }

    @Test
    fun `policy stats endpoint aggregates approvals blocks and tool calls`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val auditLog = FileAuditLog(auditFile.absolutePath)
        val runId = "run-policy-stats"

        auditLog.log(
            runId = runId,
            eventType = "approval_required",
            payload = """{"runId":"$runId","proposed_actions":["risk_tier:high","tool:create_github_repo"]}""",
        )
        auditLog.log(
            runId = runId,
            eventType = "tool_call_denied",
            payload = """{"toolName":"push_repo_to_github","reason":"denied"}""",
        )
        auditLog.log(
            runId = runId,
            eventType = "tool_call_executed",
            payload = """{"toolName":"create_repo_from_archetype","idempotencyKey":"idem-1","result":{"success":true}}""",
        )

        application {
            module(
                auditLog = auditLog,
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.get("/factory/policy-stats")
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1L, payload.getValue("totals").jsonObject.getValue("approvalRequiredEvents").jsonPrimitive.content.toLong())
        assertEquals(1L, payload.getValue("totals").jsonObject.getValue("blockedEvents").jsonPrimitive.content.toLong())
        assertEquals(1L, payload.getValue("totals").jsonObject.getValue("executedToolCalls").jsonPrimitive.content.toLong())

        val byAction = payload.getValue("byActionType").jsonArray
        assertTrue(byAction.any { entry ->
            val obj = entry.jsonObject
            obj["actionType"]?.jsonPrimitive?.content == "create_github_repo" &&
                obj["riskTier"]?.jsonPrimitive?.content == "high" &&
                obj["approvalRequiredEvents"]?.jsonPrimitive?.content == "1"
        })
        assertTrue(byAction.any { entry ->
            val obj = entry.jsonObject
            obj["actionType"]?.jsonPrimitive?.content == "push_repo_to_github" &&
                obj["blockedEvents"]?.jsonPrimitive?.content == "1"
        })
        assertTrue(byAction.any { entry ->
            val obj = entry.jsonObject
            obj["actionType"]?.jsonPrimitive?.content == "create_repo_from_archetype" &&
                obj["executedToolCalls"]?.jsonPrimitive?.content == "1"
        })
    }
}
