package productfactory

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import productfactory.api.AskUserAnswerResponse
import productfactory.profile.InMemoryProfileStore
import productfactory.profile.ProfileStoreConfig
import productfactory.workflow.ApprovalStatus
import productfactory.workflow.AskUserQuestionStatus
import productfactory.workflow.FileAskUserStore
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.InMemoryApprovalStore
import productfactory.workflow.InMemoryAskUserStore
import productfactory.workflow.WorkflowStepId
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactoryAnswerApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `answer API persists ask-user answer and keeps it available on next store request`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val askUserDir = createTempDirectory(prefix = "ask-user-answer-")
        val approvals = InMemoryApprovalStore()
        val askUserStore = FileAskUserStore(askUserDir)
        val runId = "run-ask-user-persisted-answer"
        askUserStore.saveQuestion(
            runId = runId,
            question = "Select profile",
            options = listOf("safe", "fast"),
        )

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"B"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AskUserAnswerResponse>(response.body<String>())
        assertEquals("answered", body.status)
        assertEquals("fast", body.question?.answer)

        val reloadedStore = FileAskUserStore(askUserDir)
        assertNull(reloadedStore.getPendingQuestion(runId))
        val savedAnswer = reloadedStore.submitAnswer(runId, "safe")
        assertNotNull(savedAnswer)
        assertEquals(AskUserQuestionStatus.ANSWERED, savedAnswer.status)
        assertEquals("fast", savedAnswer.answer)
    }

    @Test
    fun `answer API stores ask-user answer by letter choice`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val approvals = InMemoryApprovalStore()
        val askUserStore = InMemoryAskUserStore()
        val runId = "run-ask-user-answer"
        askUserStore.saveQuestion(
            runId = runId,
            question = "Select profile",
            options = listOf("safe", "fast"),
        )

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"B"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AskUserAnswerResponse>(response.body<String>())
        assertEquals("answered", body.status)
        assertEquals(runId, body.runId)
        assertEquals("fast", body.question?.answer)
    }

    @Test
    fun `answer API stores approval decision by index choice`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val approvals = InMemoryApprovalStore()
        val askUserStore = InMemoryAskUserStore()
        val runId = "run-approval-answer"
        approvals.upsertPending(
            runId = runId,
            reason = "human approval required",
            proposedActions = listOf("tool:push_repo_to_github"),
        )

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":2}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.decodeFromString<AskUserAnswerResponse>(response.body<String>())
        assertEquals("rejected", body.status)
        assertEquals(runId, body.runId)
        assertEquals(ApprovalStatus.REJECTED, body.approval?.status)
        assertNotNull(approvals.get(runId))
        assertEquals(ApprovalStatus.REJECTED, approvals.get(runId)?.status)
    }

    @Test
    fun `answer API logs intent candidate selection event to audit`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val approvals = InMemoryApprovalStore()
        val askUserStore = InMemoryAskUserStore()
        val runId = "run-intent-candidate"
        askUserStore.saveQuestion(
            runId = runId,
            stepId = WorkflowStepId.SELECT_INTENT_CANDIDATE,
            question = "Выбери кандидат",
            options = listOf("Candidate A", "Candidate B", "Candidate C"),
        )

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }

        val response = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"B","reason":"Closer to expected UX"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val selectedEvent = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["eventType"]?.jsonPrimitive?.content == "intent_candidate_selected" }
        assertNotNull(selectedEvent)
        assertEquals(runId, selectedEvent["runId"]?.jsonPrimitive?.content)
        val payload = json.parseToJsonElement(selectedEvent["payload"]?.jsonPrimitive?.content.orEmpty()).jsonObject
        assertEquals(1, payload["candidateIndex"]?.jsonPrimitive?.int)
        assertEquals("Closer to expected UX", payload["reason"]?.jsonPrimitive?.content)
        assertEquals("Closer to expected UX", payload["explanation"]?.jsonPrimitive?.content)
    }

    @Test
    fun `answer API auto-updates profile from selected candidate`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val approvals = InMemoryApprovalStore()
        val askUserStore = InMemoryAskUserStore()
        val profileStore = InMemoryProfileStore()
        val runId = "run-intent-candidate-profile"
        askUserStore.saveQuestion(
            runId = runId,
            stepId = WorkflowStepId.SELECT_INTENT_CANDIDATE,
            question = "Выбери кандидат",
            options = listOf("Candidate A", "Candidate B", "Candidate C"),
        )

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = approvals,
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
                profileStore = profileStore,
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = false,
                    embeddingDim = 3,
                    maxRules = 16,
                ),
            )
        }

        val answerResponse = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"B","profileId":"alice"}""")
        }
        assertEquals(HttpStatusCode.OK, answerResponse.status)

        val profileResponse = client.get("/factory/profiles/alice?tenantId=default")
        assertEquals(HttpStatusCode.OK, profileResponse.status)
        val profilePayload = json.parseToJsonElement(profileResponse.body<String>()).jsonObject
        val rules = profilePayload["rules"]?.jsonArray
        assertNotNull(rules)
        assertTrue(rules.isNotEmpty())

        val autoUpdateEvent = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["eventType"]?.jsonPrimitive?.content == "profile_auto_updated_from_candidate" }
        assertNotNull(autoUpdateEvent)
    }
}
