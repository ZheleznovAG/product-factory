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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.workflow.ArtifactRunRecord
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TenantIsolationApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `GET factory run is isolated by tenantId`() = testApplication {
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val registry = FileArtifactRegistry(artifactDirectory.absolutePath)
        registry.upsert(
            ArtifactRunRecord(
                runId = "team-a::run-1",
                workflowState = "DONE",
                updatedAt = "2026-02-26T12:00:00Z",
                repositoryVersion = "repo:product-factory/pf-team-a-run-1:v1",
            ),
        )
        application {
            module(artifactRegistry = registry)
        }

        val ok = client.get("/factory/runs/run-1?tenantId=team-a")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("run-1", json.parseToJsonElement(ok.bodyAsText()).jsonObject["runId"]?.jsonPrimitive?.content)

        val forbiddenTenant = client.get("/factory/runs/run-1?tenantId=team-b")
        assertEquals(HttpStatusCode.NotFound, forbiddenTenant.status)
    }

    @Test
    fun `intent audit runId is scoped by tenantId`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        application {
            module(auditLog = FileAuditLog(auditFile.absolutePath))
        }

        val response = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "tenantId": "acme",
                  "requestId": "req-tenant-1",
                  "input": { "query": "Generate product intent" }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val event = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["eventType"]?.jsonPrimitive?.content == "intent_estimated" }
        assertNotNull(event)
        assertEquals("acme::req-tenant-1", event["runId"]?.jsonPrimitive?.content)
    }
}
