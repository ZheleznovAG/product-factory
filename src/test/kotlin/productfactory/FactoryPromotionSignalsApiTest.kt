package productfactory

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import productfactory.api.PromotionSignalsResponse
import productfactory.api.PromotionDecision
import productfactory.observability.FactoryMetrics
import productfactory.policy.PolicyCheck
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.WorkflowState
import productfactory.workflow.artifactRecordForState
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactoryPromotionSignalsApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun cleanupMetrics() {
        FactoryMetrics.resetForTests()
    }

    @Test
    fun `promotion evaluate allows run when thresholds and artifact record are valid`() = testApplication {
        FactoryMetrics.resetForTests()
        FactoryMetrics.recordRun("accepted", 1_000)
        FactoryMetrics.recordRun("accepted", 2_000)
        FactoryMetrics.recordLlmTokens(inputTokens = 120, outputTokens = 80)

        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath)
        val runId = "run-promotion-ok"
        artifactRegistry.upsert(
            artifactRecordForState(
                runId = runId,
                state = WorkflowState.DONE,
                repoUrl = "https://github.com/example/repo",
                artifactLocation = "s3://bucket/release.tgz",
            ),
        )
        artifactRegistry.upsertManifest(runId, WorkflowState.DONE, """{"manifest":{"runId":"$runId","workflowState":"DONE"}}""")

        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = artifactRegistry,
            )
        }

        val response = client.post("/factory/promotion/$runId/evaluate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "decisionMode": "auto",
                  "minSuccessRate": 0.95,
                  "maxP99Seconds": 7200,
                  "minClassifiedRuns": 2,
                  "minRunDurationSamples": 2,
                  "maxInputTokensTotal": 500,
                  "maxOutputTokensTotal": 500,
                  "maxArtifactAgeHours": 24,
                  "requireDoneState": true,
                  "requireDoneManifest": true,
                  "requireArtifactLocation": true,
                  "requireRepoUrl": true
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<PromotionSignalsResponse>(response.body<String>())
        assertTrue(payload.allowPromotion, payload.reasons.joinToString("; "))
        assertTrue(payload.reasons.isEmpty())
        assertEquals(PromotionDecision.ALLOW, payload.decision)
        assertTrue(payload.artifactSignals.hasDoneManifest)
        assertEquals(runId, payload.runId)
    }

    @Test
    fun `promotion evaluate blocks run when metrics or registry checks fail`() = testApplication {
        FactoryMetrics.resetForTests()
        FactoryMetrics.recordRun("accepted", 1_000)
        FactoryMetrics.recordRun("rejected", 7_500_000)
        FactoryMetrics.recordLlmTokens(inputTokens = 700, outputTokens = 10)

        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath)
        val runId = "run-promotion-block"
        artifactRegistry.upsert(
            artifactRecordForState(
                runId = runId,
                state = WorkflowState.STAGED,
                repoUrl = null,
                artifactLocation = null,
            ),
        )

        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = artifactRegistry,
            )
        }

        val response = client.post("/factory/promotion/$runId/evaluate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "decisionMode": "auto",
                  "minSuccessRate": 0.95,
                  "maxP99Seconds": 7200,
                  "minClassifiedRuns": 2,
                  "minRunDurationSamples": 2,
                  "maxInputTokensTotal": 100,
                  "maxArtifactAgeHours": 24,
                  "requireDoneState": true,
                  "requireDoneManifest": true,
                  "requireArtifactLocation": true,
                  "requireRepoUrl": true
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<PromotionSignalsResponse>(response.body<String>())
        assertFalse(payload.allowPromotion)
        assertTrue(payload.reasons.any { it.contains("success_rate=") })
        assertTrue(payload.reasons.any { it.contains("p99=") })
        assertTrue(payload.reasons.any { it.contains("llm_input_tokens_total=") })
        assertTrue(payload.reasons.any { it.contains("workflowState=STAGED") })
        assertTrue(payload.reasons.any { it.contains("DONE manifest not found") })
        assertTrue(payload.reasons.any { it.contains("artifactLocation is required") })
        assertTrue(payload.reasons.any { it.contains("repoUrl is required") })
    }

    @Test
    fun `promotion evaluate returns review in semi-auto mode for low sample size only`() = testApplication {
        FactoryMetrics.resetForTests()
        FactoryMetrics.recordRun("accepted", 2_000)
        FactoryMetrics.recordLlmTokens(inputTokens = 20, outputTokens = 10)

        val artifactDirectory = createTempDirectory(prefix = "artifact-registry-").toFile().also { it.deleteOnExit() }
        val artifactRegistry = FileArtifactRegistry(artifactDirectory.absolutePath)
        val runId = "run-promotion-review"
        artifactRegistry.upsert(
            artifactRecordForState(
                runId = runId,
                state = WorkflowState.DONE,
                repoUrl = "https://github.com/example/repo",
                artifactLocation = "s3://bucket/release.tgz",
            ),
        )
        artifactRegistry.upsertManifest(runId, WorkflowState.DONE, """{"manifest":{"runId":"$runId","workflowState":"DONE"}}""")

        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                artifactRegistry = artifactRegistry,
            )
        }

        val response = client.post("/factory/promotion/$runId/evaluate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "decisionMode": "semi_auto",
                  "minSuccessRate": 0.95,
                  "maxP99Seconds": 7200,
                  "minClassifiedRuns": 3,
                  "minRunDurationSamples": 3,
                  "maxInputTokensTotal": 500,
                  "requireDoneState": true,
                  "requireDoneManifest": true,
                  "requireArtifactLocation": true,
                  "requireRepoUrl": true
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = json.decodeFromString<PromotionSignalsResponse>(response.body<String>())
        assertFalse(payload.allowPromotion)
        assertTrue(payload.requiresHumanReview)
        assertEquals(PromotionDecision.REVIEW, payload.decision)
        assertTrue(payload.hardFailures.isEmpty())
        assertTrue(payload.warnings.any { it.contains("classified_runs=") })
        assertTrue(payload.warnings.any { it.contains("run_duration_count=") })
    }
}
