package productfactory

import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.FileAuditLog
import productfactory.workflow.InMemoryApprovalStore
import productfactory.workflow.InMemoryAskUserStore
import productfactory.profile.InMemoryProfileStore
import productfactory.profile.ProfileStoreConfig
import productfactory.workflow.WorkflowStepId
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

class IntentExperienceApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `POST intent estimate returns intent response and writes audit`() = testApplication {
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

        val response = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-intent-1",
                  "input": {
                    "query": "Сделай onboarding-опыт для нового пользователя SaaS",
                    "language": "ru",
                    "references": ["https://example.com/brand-guide"]
                  },
                  "constraints": {
                    "riskTier": "medium",
                    "maxClarifyingQuestions": 3
                  }
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("productfactory.io/v1", body["apiVersion"]?.jsonPrimitive?.content)
        assertEquals("req-intent-1", body["requestId"]?.jsonPrimitive?.content)
        assertNotNull(body["intent"])
        val event = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["eventType"]?.jsonPrimitive?.content == "intent_estimated" }
        assertNotNull(event)
        assertEquals("req-intent-1", event["runId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST intent estimate returns 422 for blank query`() = testApplication {
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

        val response = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-intent-blank",
                  "input": {
                    "query": "   "
                  }
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("INTENT_UNAVAILABLE", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `POST experience generate returns variants and writes audit`() = testApplication {
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

        val response = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-exp-1",
                  "intent": {
                    "outcome": "Ускорить time-to-first-value",
                    "experience": "Короткий guided flow",
                    "constraints": ["Не менять pricing"]
                  },
                  "generation": {
                    "variants": 3,
                    "includeRationale": true
                  }
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val variants = body["variants"]?.jsonArray
        assertNotNull(variants)
        assertEquals(3, variants.size)
        assertTrue(variants.all { it.jsonObject["rationale"] != null })
        val event = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
            .firstOrNull { it["eventType"]?.jsonPrimitive?.content == "experience_generated" }
        assertNotNull(event)
        assertEquals("req-exp-1", event["runId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST experience generate validates variants range`() = testApplication {
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

        val response = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "intent": {
                    "outcome": "Ускорить time-to-first-value",
                    "experience": "Короткий guided flow"
                  },
                  "generation": {
                    "variants": 2
                  }
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("VALIDATION_ERROR", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `POST experience generate uses profile for ranking unless incognito`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val profileStore = InMemoryProfileStore()

        application {
            module(
                auditLog = FileAuditLog(auditFile.absolutePath),
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
                profileStore = profileStore,
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 16,
                ),
            )
        }

        val upsert = client.put("/factory/profiles/alice") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "default",
                  "embedding": [0.1, 0.2, 0.3],
                  "rules": [{"type": "LIKE", "value": "accelerated version"}],
                  "consent": {"profileStorage": true}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, upsert.status)

        val ranked = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-exp-ranked",
                  "profileId": "alice",
                  "intent": {
                    "outcome": "Ускорить time-to-first-value",
                    "experience": "Короткий guided flow"
                  },
                  "generation": {"variants": 3}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, ranked.status)
        val rankedVariants = json.parseToJsonElement(ranked.bodyAsText()).jsonObject["variants"]?.jsonArray
        assertNotNull(rankedVariants)
        val rankedFirst = rankedVariants.first().jsonObject["summary"]?.jsonPrimitive?.content.orEmpty().lowercase()
        assertTrue(rankedFirst.contains("accelerated"))

        val incognito = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-exp-incognito",
                  "profileId": "alice",
                  "incognito": true,
                  "intent": {
                    "outcome": "Ускорить time-to-first-value",
                    "experience": "Короткий guided flow"
                  },
                  "generation": {"variants": 3}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, incognito.status)
        val incognitoVariants = json.parseToJsonElement(incognito.bodyAsText()).jsonObject["variants"]?.jsonArray
        assertNotNull(incognitoVariants)
        val incognitoFirst = incognitoVariants.first().jsonObject["summary"]?.jsonPrimitive?.content.orEmpty().lowercase()
        assertTrue(incognitoFirst.contains("conservative baseline"))
    }

    @Test
    fun `intent and experience include session and reference_ids with 6 to 2 scenario`() = testApplication {
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

        val intentResponse = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "tenantId": "acme",
                  "requestId": "req-intent-session-1",
                  "session": {
                    "sessionId": "sess-123",
                    "references": {
                      "selected_ids": ["ref-1", "ref-4"]
                    }
                  },
                  "input": {
                    "query": "Нужен onboarding для B2B SaaS"
                  }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, intentResponse.status)
        val intentBody = json.parseToJsonElement(intentResponse.bodyAsText()).jsonObject
        val options = intentBody["session"]?.jsonObject
            ?.get("references")?.jsonObject
            ?.get("options")?.jsonArray
        assertNotNull(options)
        assertEquals(6, options.size)
        val intentReferenceIds = intentBody["intent"]?.jsonObject?.get("reference_ids")?.jsonArray
        assertNotNull(intentReferenceIds)
        assertEquals(listOf("ref-1", "ref-4"), intentReferenceIds.map { it.jsonPrimitive.content })

        val experienceResponse = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "tenantId": "acme",
                  "requestId": "req-exp-session-1",
                  "session": {
                    "sessionId": "sess-123",
                    "references": {
                      "selected_ids": ["ref-1", "ref-4"],
                      "options": [
                        {"id": "ref-1", "title": "Reference card 1", "summary": "a"},
                        {"id": "ref-2", "title": "Reference card 2", "summary": "b"},
                        {"id": "ref-3", "title": "Reference card 3", "summary": "c"},
                        {"id": "ref-4", "title": "Reference card 4", "summary": "d"},
                        {"id": "ref-5", "title": "Reference card 5", "summary": "e"},
                        {"id": "ref-6", "title": "Reference card 6", "summary": "f"}
                      ]
                    }
                  },
                  "intent": {
                    "outcome": "Ускорить time-to-first-value",
                    "experience": "Короткий guided flow",
                    "reference_ids": ["ref-1", "ref-4"]
                  },
                  "generation": {
                    "variants": 6
                  }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, experienceResponse.status)
        val experienceBody = json.parseToJsonElement(experienceResponse.bodyAsText()).jsonObject
        assertEquals("sess-123", experienceBody["session"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals(
            listOf("ref-1", "ref-4"),
            experienceBody["audit"]?.jsonObject?.get("reference_ids")?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(6, experienceBody["variants"]?.jsonArray?.size)

        val auditEvents = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
        val intentAuditPayload = auditEvents
            .first { it["eventType"]?.jsonPrimitive?.content == "intent_estimated" }
            .getValue("payload").jsonPrimitive.content
            .let { json.parseToJsonElement(it).jsonObject }
        assertEquals("sess-123", intentAuditPayload["sessionId"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("ref-1", "ref-4"),
            intentAuditPayload["reference_ids"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        val expAuditPayload = auditEvents
            .first { it["eventType"]?.jsonPrimitive?.content == "experience_generated" }
            .getValue("payload").jsonPrimitive.content
            .let { json.parseToJsonElement(it).jsonObject }
        assertEquals("sess-123", expAuditPayload["sessionId"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("ref-1", "ref-4"),
            expAuditPayload["reference_ids"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `intent to run flow supports ask_user candidate answer`() = testApplication {
        val auditFile = File.createTempFile("audit", ".log").apply { deleteOnExit() }
        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val askUserStore = InMemoryAskUserStore()
        val auditLog = FileAuditLog(auditFile.absolutePath)

        val testRoot = createTempDirectory(prefix = "factory-test-").also { it.toFile().deleteOnExit() }
        val archetypesRoot = testRoot.resolve("archetypes")
        val catalogServiceDir = archetypesRoot.resolve("catalog-service")
        Files.createDirectories(catalogServiceDir)
        catalogServiceDir.resolve("README.md").writeText("# Catalog Service Archetype\n")
        val workspaceRoot = testRoot.resolve("workspace")
        Files.createDirectories(workspaceRoot)
        val executor = SandboxToolExecutor(
            auditLog = auditLog,
            schemaPath = resolveToolsSchemaPath(),
            archetypesRoot = archetypesRoot,
            workspaceRoot = workspaceRoot,
        )

        application {
            module(
                auditLog = auditLog,
                approvalStore = InMemoryApprovalStore(),
                askUserStore = askUserStore,
                artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath),
                toolExecutor = executor,
            )
        }

        val intentResponse = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-intent-run-flow",
                  "input": {
                    "query": "Сделай onboarding-опыт для нового пользователя SaaS"
                  },
                  "constraints": {
                    "maxClarifyingQuestions": 1
                  }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, intentResponse.status)
        val intentBody = json.parseToJsonElement(intentResponse.bodyAsText()).jsonObject
        val intent = intentBody["intent"]?.jsonObject
        assertNotNull(intent)
        assertTrue(intentBody["clarifyingQuestions"]?.jsonArray?.isNotEmpty() == true)
        val outcome = intent["outcome"]?.jsonPrimitive?.content.orEmpty()
        val experience = intent["experience"]?.jsonPrimitive?.content.orEmpty()
        val constraints = intent["constraints"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

        val experienceResponse = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion": "productfactory.io/v1",
                  "requestId": "req-exp-run-flow",
                  "intent": {
                    "outcome": "${escapeJson(outcome)}",
                    "experience": "${escapeJson(experience)}",
                    "constraints": [${constraints.joinToString(",") { "\"${escapeJson(it)}\"" }}]
                  },
                  "generation": {
                    "variants": 3
                  }
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, experienceResponse.status)
        val experienceBody = json.parseToJsonElement(experienceResponse.bodyAsText()).jsonObject
        val variants = experienceBody["variants"]?.jsonArray
        assertNotNull(variants)
        assertEquals(3, variants.size)
        val variantOptions = variants.map { it.jsonObject["summary"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue(variantOptions.all { it.isNotBlank() })

        val runResponse = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("goal", JsonPrimitive(outcome))
                    put(
                        "constraints",
                        buildJsonArray {
                            add(JsonPrimitive("selected_experience:${variantOptions.first()}"))
                        },
                    )
                }.toString(),
            )
        }
        assertEquals(HttpStatusCode.OK, runResponse.status)
        val runBody = json.parseToJsonElement(runResponse.bodyAsText()).jsonObject
        val runId = runBody["runId"]?.jsonPrimitive?.content
        assertNotNull(runId)
        assertEquals("accepted", runBody["status"]?.jsonPrimitive?.content)

        askUserStore.saveQuestion(
            runId = runId,
            stepId = WorkflowStepId.SELECT_INTENT_CANDIDATE,
            question = "Выбери итоговый вариант",
            options = variantOptions,
        )

        val answerResponse = client.post("/factory/runs/$runId/answer") {
            contentType(ContentType.Application.Json)
            setBody("""{"choice":"B","reason":"Лучший баланс скорости и риска"}""")
        }
        assertEquals(HttpStatusCode.OK, answerResponse.status)
        val answerBody = json.parseToJsonElement(answerResponse.bodyAsText()).jsonObject
        assertEquals("answered", answerBody["status"]?.jsonPrimitive?.content)
        assertEquals(variantOptions[1], answerBody["question"]?.jsonObject?.get("answer")?.jsonPrimitive?.content)

        val auditEvents = auditFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.parseToJsonElement(it).jsonObject }
        assertTrue(auditEvents.any { it["eventType"]?.jsonPrimitive?.content == "intent_estimated" })
        assertTrue(auditEvents.any { it["eventType"]?.jsonPrimitive?.content == "experience_generated" })
        assertTrue(auditEvents.any { it["eventType"]?.jsonPrimitive?.content == "request_received" && it["runId"]?.jsonPrimitive?.content == runId })
        val selectedEvent = auditEvents.firstOrNull { it["eventType"]?.jsonPrimitive?.content == "intent_candidate_selected" }
        assertNotNull(selectedEvent)
        val payload = json.parseToJsonElement(selectedEvent["payload"]?.jsonPrimitive?.content.orEmpty()).jsonObject
        assertEquals(1, payload["candidateIndex"]?.jsonPrimitive?.int)
        assertEquals(variantOptions[1], payload["candidate"]?.jsonPrimitive?.content)
    }

    private fun resolveToolsSchemaPath(): Path {
        val schemaResource = IntentExperienceApiTest::class.java.classLoader.getResource("contracts/tools.schema.json")
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

        error("Unable to locate contracts/tools.schema.json for IntentExperienceApiTest")
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
