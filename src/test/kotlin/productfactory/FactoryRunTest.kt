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
import productfactory.api.FactoryRunResponse
import productfactory.policy.PolicyCheck
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.tools.SandboxToolExecutor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Интеграционный тест: POST /factory/run без OPA (fallback allow) возвращает 200 и accepted.
 * Сборка и тесты выполняются в Docker (Dockerfile: gradle build installDist).
 */
class FactoryRunTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `POST factory run returns 200 and accepted when policy allows`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val testRoot = createTempDirectory(prefix = "factory-test-").also { it.toFile().deleteOnExit() }
        val archetypesRoot = testRoot.resolve("archetypes")
        val catalogServiceDir = archetypesRoot.resolve("catalog-service")
        java.nio.file.Files.createDirectories(catalogServiceDir)
        catalogServiceDir.resolve("README.md").writeText("# Catalog Service Archetype\n")
        val workspaceRoot = testRoot.resolve("workspace")
        java.nio.file.Files.createDirectories(workspaceRoot)
        val schemaPath = resolveToolsSchemaPath()
        val executor = SandboxToolExecutor(
            auditLog = FileAuditLog(auditFile.absolutePath),
            schemaPath = schemaPath,
            archetypesRoot = archetypesRoot,
            workspaceRoot = workspaceRoot,
        )
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
                toolExecutor = executor,
            )
        }
        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"API service for X","constraints":[]}""")
        }
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        val parsed = json.decodeFromString<FactoryRunResponse>(body)
        assertNotNull(parsed.runId)
        assertEquals("accepted", parsed.status)
        val auditContent = auditFile.readText()
        assertAuditContains(auditContent, """"eventType":"state_changed"""", "state_changed")
        assertAuditContains(auditContent, """"eventType":"tool_call_executed"""", "tool_call_executed")
        assertAuditContains(auditContent, """"toolName":"create_repo_from_archetype"""", "create_repo_from_archetype")
        assertAuditContains(auditContent, """"eventType":"artifact_registry_updated"""", "artifact_registry_updated")
        assertAuditContains(auditContent, """"state":"STAGED"""", "STAGED")
        assertAuditContains(auditContent, """"state":"DONE"""", "DONE")
        assertStateTransitionOrder(
            auditContent,
            listOf("PLANNED", "GENERATED", "TESTED", "SECURED", "STAGED", "DONE"),
        )

        val runRecordFile = File(artifactDirectory, "${parsed.runId}.json")
        assertTrue(runRecordFile.exists(), "run record file missing: ${artifactDirectory.absolutePath} ${parsed.runId}")
        val runRecord = runRecordFile.readText()
        assertTrue(runRecord.contains(parsed.runId), "runRecord missing runId ${parsed.runId}: $runRecord")
        assertTrue(runRecord.contains("DONE"), "runRecord missing DONE: $runRecord")
        assertTrue(runRecord.contains("repo:product-factory/pf-"), "runRecord missing repositoryVersion: $runRecord")
        assertTrue(runRecord.contains("image:ghcr.io/product-factory/pf-"), "runRecord missing imageVersion: $runRecord")
        assertTrue(runRecord.contains("sbom:cyclonedx:placeholder-v1"), "runRecord missing sbomVersion: $runRecord")
        assertTrue(runRecord.contains("signature:cosign:placeholder-v1"), "runRecord missing signatureVersion: $runRecord")
    }

    @Test
    fun `GET health returns 200 and ok status`() = testApplication {
        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
            )
        }
        val response = client.get("/health")
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        assertEquals("""{"status":"ok"}""", body)
    }

    @Test
    fun `GET health neural returns 200 and not_configured when NEURAL_SERVICE_URL unset`() = testApplication {
        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
            )
        }
        val response = client.get("/health/neural")
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        assertTrue(body.contains("not_configured"), "body: $body")
    }

    @Test
    fun `POST factory run with empty goal returns 200`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }
        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"","constraints":[]}""")
        }
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        val parsed = json.decodeFromString<FactoryRunResponse>(body)
        assertNotNull(parsed.runId)
    }

    @Test
    fun `POST factory run accepts optional target stack and budget`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }
        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "goal":"",
                  "constraints":["ktor"],
                  "target_stack":"jvm-ktor",
                  "budget":{
                    "token_budget":120000,
                    "tool_calls_budget":20,
                    "wall_clock_seconds":900
                  }
                }
                """.trimIndent(),
            )
        }
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        val parsed = json.decodeFromString<FactoryRunResponse>(body)
        assertNotNull(parsed.runId)
    }

    @Test
    fun `POST factory run dry_run returns accepted and writes dry-run tool plan to audit`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
            )
        }
        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"API service for X","constraints":[],"dry_run":true}""")
        }
        val body = response.body<String>()
        assertEquals(HttpStatusCode.OK, response.status, "body: $body")
        val parsed = json.decodeFromString<FactoryRunResponse>(body)
        assertEquals("accepted", parsed.status)
        assertTrue(parsed.message.contains("Dry-run completed"), "message: ${parsed.message}")

        val auditContent = auditFile.readText()
        assertAuditContains(auditContent, """"eventType":"tool_call_dry_run_plan"""", "tool_call_dry_run_plan")
        assertAuditContains(auditContent, """"toolName":"create_repo_from_archetype"""", "create_repo_from_archetype in dry-run plan")
        assertTrue(
            indexOfRawOrEscaped(auditContent, """"eventType":"tool_call_executed"""", 0) < 0,
            "dry-run must not execute tools: $auditContent",
        )
    }

    private fun assertStateTransitionOrder(auditContent: String, states: List<String>) {
        var cursor = -1
        for (state in states) {
            val token = """"newState":"$state""""
            val next = indexOfRawOrEscaped(auditContent, token, cursor + 1)
            assertTrue(next >= 0, "State $state was not found in audit log")
            cursor = next
        }
    }

    private fun assertAuditContains(auditContent: String, token: String, label: String) {
        assertTrue(
            indexOfRawOrEscaped(auditContent, token, 0) >= 0,
            "audit missing $label: $auditContent",
        )
    }

    private fun indexOfRawOrEscaped(auditContent: String, token: String, startIndex: Int): Int {
        val rawIndex = auditContent.indexOf(token, startIndex = startIndex)
        if (rawIndex >= 0) return rawIndex
        val escapedToken = token.replace("\"", "\\\"")
        return auditContent.indexOf(escapedToken, startIndex = startIndex)
    }

    private fun resolveToolsSchemaPath(): Path {
        val schemaResource = FactoryRunTest::class.java.classLoader.getResource("contracts/tools.schema.json")
        if (schemaResource != null) {
            return Path.of(schemaResource.toURI())
        }

        var cursor: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (cursor != null) {
            val candidate = cursor.resolve("contracts").resolve("tools.schema.json")
            if (Files.exists(candidate)) {
                return candidate
            }
            cursor = cursor.parent
        }

        error("Unable to locate contracts/tools.schema.json for FactoryRunTest")
    }
}
