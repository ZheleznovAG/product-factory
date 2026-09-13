package productfactory.api

import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import productfactory.neural.NeuralHealthStatus
import productfactory.neural.NeuralServiceHealthCheck
import productfactory.profile.PreferenceRule
import productfactory.profile.PreferenceRuleType
import productfactory.profile.ProfileConsent
import productfactory.profile.ProfileStore
import productfactory.profile.ProfileStoreConfig
import productfactory.profile.requireValidProfileId
import io.temporal.client.WorkflowClient
import productfactory.workflow.ApprovalRecord
import productfactory.workflow.ApprovalStore
import productfactory.workflow.ApprovalStatus
import productfactory.workflow.AuditLog
import productfactory.workflow.AskUserQuestion
import productfactory.workflow.AskUserStore
import productfactory.workflow.ArtifactRegistry
import productfactory.workflow.ArtifactRunRecord
import productfactory.workflow.PeriodUsageDelta
import productfactory.workflow.PeriodUsageStore
import productfactory.workflow.CostBudgetGuard
import productfactory.workflow.CostBudgetViolation
import productfactory.workflow.WorkflowStepId
import productfactory.workflow.WorkflowRunner
import productfactory.workflow.temporal.TemporalFactoryWorker
import productfactory.observability.FactoryMetrics
import productfactory.observability.FactoryMetricsSnapshot
import productfactory.intent.IntentClarifier
import productfactory.intent.IntentClarification
import productfactory.intent.IntentGenerator
import productfactory.intent.IntentCandidatesGenerator
import io.ktor.http.ContentType
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToLong
import java.util.Locale
import java.time.Instant
import productfactory.workflow.FileAuditLog
import productfactory.workflow.tools.loadToolRegistry

private const val API_VERSION_V1 = "productfactory.io/v1"
private const val DEFAULT_TENANT_ID = "default"
private const val TENANT_ID_HEADER = "X-Tenant-Id"
private val TENANT_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,62}$")
private val ALLOWED_RISK_TIERS = setOf("low", "medium", "high")
private const val DECISION_CONTEXT_EVENT_LIMIT = 300
private val DECISION_CONTEXT_EVENT_TYPES = setOf(
    "pipeline_plan",
    "planner_explanation",
    "approval_required",
    "intent_candidate_selected",
    "risk_assessment",
)
private val DECISION_JSON = Json { ignoreUnknownKeys = true }

private fun Parameters.requireRunId(): String =
    get("runId")?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("runId is required")

private fun resolveTenantId(
    call: io.ktor.server.application.ApplicationCall,
    bodyValue: String?,
): String {
    val resolved = bodyValue?.trim()
        ?.takeUnless { it.isBlank() }
        ?: call.request.headers[TENANT_ID_HEADER]?.trim()?.takeUnless { it.isBlank() }
        ?: call.request.queryParameters["tenantId"]?.trim()?.takeUnless { it.isBlank() }
        ?: call.parameters["tenantId"]?.trim()?.takeUnless { it.isBlank() }
        ?: DEFAULT_TENANT_ID
    val normalized = resolved.lowercase()
    require(TENANT_ID_REGEX.matches(normalized)) {
        "tenantId must match ${TENANT_ID_REGEX.pattern}"
    }
    return normalized
}

private fun scopedRunId(tenantId: String, runId: String): String =
    if (tenantId == DEFAULT_TENANT_ID) runId else "$tenantId::$runId"

private fun unscopedRunId(tenantId: String, scopedRunId: String): String {
    if (tenantId == DEFAULT_TENANT_ID) return scopedRunId
    val prefix = "$tenantId::"
    return if (scopedRunId.startsWith(prefix)) scopedRunId.removePrefix(prefix) else scopedRunId
}

private fun ApprovalRecord.withPublicRunId(tenantId: String): ApprovalRecord =
    copy(runId = unscopedRunId(tenantId, runId))

private fun AskUserQuestion.withPublicRunId(tenantId: String): AskUserQuestion =
    copy(runId = unscopedRunId(tenantId, runId))

@Serializable
data class ApprovalDecisionRequest(
    val tenantId: String? = null,
    val decidedBy: String? = null,
    val comment: String? = null,
)

@Serializable
data class ApprovalDecisionResponse(
    val runId: String,
    val tenantId: String,
    val status: String,
    val message: String,
    val approval: ApprovalRecord? = null,
)

@Serializable
data class AskUserAnswerRequest(
    val tenantId: String? = null,
    val choice: JsonPrimitive,
    val reason: String? = null,
    val profileId: String? = null,
    val profileStorageConsent: Boolean? = null,
    val incognito: Boolean = false,
)

@Serializable
data class AskUserAnswerResponse(
    val runId: String,
    val tenantId: String,
    val status: String,
    val message: String,
    val question: AskUserQuestion? = null,
    val approval: ApprovalRecord? = null,
)

@Serializable
data class DecisionContextResponse(
    val runId: String,
    val tenantId: String,
    val artifact: ArtifactRunRecord? = null,
    val approval: ApprovalRecord? = null,
    val pendingQuestion: AskUserQuestion? = null,
    val plan: JsonObject? = null,
    val plannerExplanation: String? = null,
    val risks: List<DecisionRiskItem> = emptyList(),
    val options: List<String> = emptyList(),
)

@Serializable
data class DecisionRiskItem(
    val source: String,
    val summary: String,
    val details: JsonObject? = null,
)

@Serializable
data class SprintPointDecisionRequest(
    val tenantId: String? = null,
    val stage: String? = null,
    val decision: String? = null,
    val note: String? = null,
    val decidedBy: String? = null,
)

@Serializable
data class SprintPointDecisionResponse(
    val runId: String,
    val tenantId: String,
    val status: String,
    val message: String,
)

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class NeuralHealthResponse(
    val neural_service: String,
    val detail: String? = null,
)

@Serializable
data class ProfileConsentRequest(
    val profileStorage: Boolean = false,
    val cloudBackup: Boolean = false,
    val source: String? = null,
)

@Serializable
data class ProfileRuleRequest(
    val type: String,
    val value: String,
    val weight: Double? = null,
)

@Serializable
data class ProfileUpsertRequest(
    val tenantId: String? = null,
    val embedding: List<Double> = emptyList(),
    val embeddingModel: String? = null,
    val rules: List<ProfileRuleRequest> = emptyList(),
    val consent: ProfileConsentRequest? = null,
)

@Serializable
data class ProfileDeleteRequest(
    val tenantId: String? = null,
    val reason: String? = null,
)

@Serializable
data class ProfileResetRequest(
    val tenantId: String? = null,
    val reason: String? = null,
)

@Serializable
data class ProfileIncognitoRequest(
    val tenantId: String? = null,
    val enabled: Boolean = true,
    val reason: String? = null,
)

@Serializable
data class IntentEstimateRequest(
    val apiVersion: String? = null,
    val tenantId: String? = null,
    val requestId: String? = null,
    val session: ApiSession? = null,
    val input: IntentEstimateInput? = null,
    val constraints: IntentEstimateConstraints? = null,
)

@Serializable
data class IntentEstimateInput(
    val query: String? = null,
    val language: String? = null,
    val references: List<String> = emptyList(),
    @SerialName("reference_ids")
    val referenceIds: List<String> = emptyList(),
)

@Serializable
data class IntentEstimateConstraints(
    val riskTier: String? = null,
    val latencyBudgetMs: Int? = null,
    val maxClarifyingQuestions: Int? = null,
)

@Serializable
data class IntentEstimateResponse(
    val apiVersion: String,
    val tenantId: String,
    val requestId: String,
    val session: ApiSession,
    val intent: IntentEstimatePayload,
    val clarifyingQuestions: List<String>,
    val evidence: IntentEvidence,
)

@Serializable
data class IntentEstimatePayload(
    val outcome: String,
    val experience: String,
    val constraints: List<String>,
    val confidence: Double? = null,
    @SerialName("reference_ids")
    val referenceIds: List<String> = emptyList(),
)

@Serializable
data class IntentEvidence(
    val usedReferences: Int,
    val model: String,
)

@Serializable
data class ExperienceGenerateRequest(
    val apiVersion: String? = null,
    val tenantId: String? = null,
    val requestId: String? = null,
    val session: ApiSession? = null,
    val intent: ExperienceInputIntent? = null,
    val generation: ExperienceGenerationSettings? = null,
    val profileId: String? = null,
    val incognito: Boolean = false,
)

@Serializable
data class ExperienceInputIntent(
    val outcome: String? = null,
    val experience: String? = null,
    val constraints: List<String> = emptyList(),
    @SerialName("reference_ids")
    val referenceIds: List<String> = emptyList(),
)

@Serializable
data class ExperienceGenerationSettings(
    val variants: Int? = null,
    val includeRationale: Boolean = false,
)

@Serializable
data class ExperienceGenerateResponse(
    val apiVersion: String,
    val tenantId: String,
    val requestId: String,
    val session: ApiSession,
    val variants: List<ExperienceVariant>,
    val audit: ExperienceAudit,
)

@Serializable
data class ExperienceVariant(
    val id: String,
    val title: String,
    val summary: String,
    val rationale: String? = null,
    val score: Double,
)

@Serializable
data class ExperienceAudit(
    val event: String,
    val tenantId: String,
    val runId: String,
    val sessionId: String,
    @SerialName("reference_ids")
    val referenceIds: List<String> = emptyList(),
)

@Serializable
data class ApiSession(
    val sessionId: String? = null,
    val createdAt: String? = null,
    val intent: JsonObject? = null,
    val preferences: JsonObject? = null,
    val consentFlags: JsonObject? = null,
    val references: ApiSessionReferences? = null,
)

@Serializable
data class ApiSessionReferences(
    val options: List<ApiReferenceOption> = emptyList(),
    @SerialName("selected_ids")
    val selectedIds: List<String> = emptyList(),
)

@Serializable
data class ApiReferenceOption(
    val id: String,
    val title: String,
    val summary: String,
)

@Serializable
data class ApiErrorResponse(
    val apiVersion: String,
    val error: ApiErrorPayload,
)

@Serializable
data class ApiErrorPayload(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
enum class PromotionDecisionMode {
    @SerialName("auto")
    AUTO,
    @SerialName("semi_auto")
    SEMI_AUTO,
}

@Serializable
enum class PromotionDecision {
    @SerialName("allow")
    ALLOW,
    @SerialName("review")
    REVIEW,
    @SerialName("block")
    BLOCK,
}

@Serializable
data class PromotionSignalsRequest(
    val tenantId: String? = null,
    val decisionMode: PromotionDecisionMode = PromotionDecisionMode.SEMI_AUTO,
    val minSuccessRate: Double = 0.95,
    val maxP99Seconds: Double = 7200.0,
    val maxInputTokensTotal: Long? = null,
    val maxOutputTokensTotal: Long? = null,
    val minClassifiedRuns: Long = 20,
    val minRunDurationSamples: Long = 20,
    val maxArtifactAgeHours: Long? = 72,
    val requireDoneState: Boolean = true,
    val requireDoneManifest: Boolean = true,
    val requireArtifactLocation: Boolean = false,
    val requireRepoUrl: Boolean = false,
)

@Serializable
data class PromotionSignalsMetrics(
    val acceptedRuns: Long,
    val rejectedRuns: Long,
    val classifiedRuns: Long,
    val runDurationCount: Long,
    val successRate: Double?,
    val p99DurationSeconds: Double?,
    val llmInputTokensTotal: Long,
    val llmOutputTokensTotal: Long,
)

@Serializable
data class PromotionSignalsArtifact(
    val hasRecord: Boolean,
    val hasDoneManifest: Boolean,
    val doneManifestPath: String? = null,
    val ageHours: Double? = null,
)

@Serializable
data class PromotionSignalsResponse(
    val runId: String,
    val tenantId: String,
    val decisionMode: PromotionDecisionMode,
    val decision: PromotionDecision,
    val allowPromotion: Boolean,
    val requiresHumanReview: Boolean,
    val reasons: List<String>,
    val hardFailures: List<String>,
    val warnings: List<String>,
    val thresholds: PromotionSignalsRequest,
    val metrics: PromotionSignalsMetrics,
    val artifactSignals: PromotionSignalsArtifact,
    val artifact: ArtifactRunRecord? = null,
)

@Serializable
data class PolicyStatsResponse(
    val generatedAt: String,
    val auditLogPath: String,
    val tenantId: String? = null,
    val since: String? = null,
    val totals: PolicyStatsTotals,
    val byRiskTier: List<PolicyRiskTierStats>,
    val byActionType: List<PolicyActionRiskStats>,
)

@Serializable
data class PolicyStatsTotals(
    val processedEvents: Long,
    val approvalRequiredEvents: Long,
    val blockedEvents: Long,
    val executedToolCalls: Long,
    val uniqueRiskTiers: Int,
    val uniqueActionTypes: Int,
)

@Serializable
data class PolicyRiskTierStats(
    val riskTier: String,
    val approvalRequiredEvents: Long,
    val blockedEvents: Long,
    val executedToolCalls: Long,
)

@Serializable
data class PolicyActionRiskStats(
    val actionType: String,
    val riskTier: String,
    val approvalRequiredEvents: Long,
    val blockedEvents: Long,
    val executedToolCalls: Long,
)

@Suppress("UNUSED_PARAMETER")
fun Routing.factoryRoutes(
    workflowRunner: WorkflowRunner,
    auditLog: AuditLog,
    approvalStore: ApprovalStore,
    askUserStore: AskUserStore,
    artifactRegistry: ArtifactRegistry,
    periodUsageStore: PeriodUsageStore,
    profileStore: ProfileStore,
    profileStoreConfig: ProfileStoreConfig,
    costBudgetGuard: CostBudgetGuard? = null,
    neuralHealthCheck: NeuralServiceHealthCheck = NeuralServiceHealthCheck(),
    temporalClient: WorkflowClient? = null,
    intentClarifier: IntentClarifier = IntentClarification(),
    intentGenerator: IntentGenerator = IntentGenerator(),
    intentCandidatesGenerator: IntentCandidatesGenerator = IntentCandidatesGenerator(),
) {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
    }

    get("/health/ready") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ready"))
    }

    get("/health/neural") {
        val result = neuralHealthCheck.check()
        val statusStr = when (result.status) {
            NeuralHealthStatus.NotConfigured -> "not_configured"
            NeuralHealthStatus.Ok -> "ok"
            NeuralHealthStatus.Unavailable -> "unavailable"
        }
        call.respond(HttpStatusCode.OK, NeuralHealthResponse(neural_service = statusStr, detail = result.detail))
    }

    get("/metrics") {
        call.respondText(FactoryMetrics.prometheusText(), ContentType.Text.Plain)
    }

    get("/factory/policy-stats") {
        val rawTenantFilter = call.request.queryParameters["tenantId"]?.trim()?.takeIf { it.isNotBlank() }
        val tenantFilter = if (rawTenantFilter == null) {
            null
        } else {
            val normalized = rawTenantFilter.lowercase()
            if (!TENANT_ID_REGEX.matches(normalized)) {
                call.respondApiError(
                    status = HttpStatusCode.BadRequest,
                    code = "VALIDATION_ERROR",
                    message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                    details = mapOf("field" to "tenantId"),
                )
                return@get
            }
            normalized
        }

        val sinceRaw = call.request.queryParameters["since"]?.trim()?.takeIf { it.isNotBlank() }
        val since = if (sinceRaw == null) {
            null
        } else {
            val parsed = runCatching { Instant.parse(sinceRaw) }.getOrNull()
            if (parsed == null) {
                call.respondApiError(
                    status = HttpStatusCode.BadRequest,
                    code = "VALIDATION_ERROR",
                    message = "since must be an ISO-8601 instant, e.g. 2026-02-20T00:00:00Z",
                    details = mapOf("field" to "since"),
                )
                return@get
            }
            parsed
        }

        val auditPath = resolveAuditLogPath(auditLog)
        if (auditPath == null) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "AUDIT_LOG_PATH_UNAVAILABLE",
                message = "Policy stats require file-backed audit log (set AUDIT_LOG_PATH).",
            )
            return@get
        }

        val response = computePolicyStats(
            auditLogPath = auditPath,
            tenantFilter = tenantFilter,
            since = since,
        )
        call.respond(HttpStatusCode.OK, response)
    }

    get("/factory/ui") {
        call.respondText(buildMinimalDecisionUiHtml(), ContentType.Text.Html)
    }

    put("/factory/profiles/{profileId}") {
        if (!profileStoreConfig.enabled) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "PROFILE_STORE_DISABLED",
                message = "Profile store is disabled. Set PROFILE_STORE_ENABLED=true.",
            )
            return@put
        }
        val profileId = try {
            requireValidProfileId(call.parameters["profileId"] ?: "")
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "profileId is required and must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "profileId"),
            )
            return@put
        }
        val request = call.receive<ProfileUpsertRequest>()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@put
        }
        if (request.rules.size > profileStoreConfig.maxRules) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "rules size exceeds PROFILE_MAX_RULES=${profileStoreConfig.maxRules}",
                details = mapOf("field" to "rules"),
            )
            return@put
        }
        if (request.embedding.isNotEmpty() && request.embedding.size != profileStoreConfig.embeddingDim) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "embedding size must be PROFILE_EMBEDDING_DIM=${profileStoreConfig.embeddingDim}",
                details = mapOf("field" to "embedding"),
            )
            return@put
        }
        val consent = request.consent ?: ProfileConsentRequest()
        if (profileStoreConfig.requireConsent && !consent.profileStorage) {
            call.respondApiError(
                status = HttpStatusCode.Forbidden,
                code = "CONSENT_REQUIRED",
                message = "profileStorage consent is required for profile persistence.",
                details = mapOf("field" to "consent.profileStorage"),
            )
            return@put
        }
        val rules = try {
            request.rules.map { it.toDomainRule() }
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "rules must contain valid type/value pairs.",
                details = mapOf("field" to "rules"),
            )
            return@put
        }
        val profile = profileStore.upsert(
            tenantId = tenantId,
            profileId = profileId,
            embedding = request.embedding.map { it.toFloat() },
            embeddingModel = request.embeddingModel?.trim()?.takeIf { it.isNotBlank() },
            rules = rules,
            consent = ProfileConsent(
                profileStorage = consent.profileStorage,
                cloudBackup = consent.cloudBackup,
                source = consent.source?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        auditLog.log(
            runId = scopedRunId(tenantId, "profile:$profileId"),
            eventType = "profile_upserted",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("revision", profile.revision)
                put("rulesCount", profile.rules.size)
                put("embeddingSize", profile.embedding.size)
            }.toString(),
        )
        call.respond(HttpStatusCode.OK, profile)
    }

    get("/factory/profiles/{profileId}") {
        if (!profileStoreConfig.enabled) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "PROFILE_STORE_DISABLED",
                message = "Profile store is disabled. Set PROFILE_STORE_ENABLED=true.",
            )
            return@get
        }
        val profileId = try {
            requireValidProfileId(call.parameters["profileId"] ?: "")
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "profileId is required and must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "profileId"),
            )
            return@get
        }
        val tenantId = try {
            resolveTenantId(call, null)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@get
        }
        val profile = profileStore.get(tenantId, profileId)
        if (profile == null) {
            call.respondText(
                "Profile profileId=$profileId not found for tenantId=$tenantId",
                status = HttpStatusCode.NotFound,
            )
            return@get
        }
        call.respond(HttpStatusCode.OK, profile)
    }

    delete("/factory/profiles/{profileId}") {
        if (!profileStoreConfig.enabled) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "PROFILE_STORE_DISABLED",
                message = "Profile store is disabled. Set PROFILE_STORE_ENABLED=true.",
            )
            return@delete
        }
        val profileId = try {
            requireValidProfileId(call.parameters["profileId"] ?: "")
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "profileId is required and must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "profileId"),
            )
            return@delete
        }
        val request = call.receiveNullable<ProfileDeleteRequest>() ?: ProfileDeleteRequest()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@delete
        }
        val revoked = profileStore.revoke(tenantId, profileId, request.reason)
        if (revoked == null) {
            call.respondText(
                "Profile profileId=$profileId not found for tenantId=$tenantId",
                status = HttpStatusCode.NotFound,
            )
            return@delete
        }
        auditLog.log(
            runId = scopedRunId(tenantId, "profile:$profileId"),
            eventType = "profile_revoked",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("reason", request.reason ?: "consent_revoked")
            }.toString(),
        )
        call.respond(
            HttpStatusCode.OK,
            buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("status", "revoked")
                put("revokedAt", revoked.revokedAt)
                request.reason?.let { put("reason", it) }
            },
        )
    }

    post("/factory/profiles/{profileId}/reset") {
        if (!profileStoreConfig.enabled) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "PROFILE_STORE_DISABLED",
                message = "Profile store is disabled. Set PROFILE_STORE_ENABLED=true.",
            )
            return@post
        }
        val profileId = try {
            requireValidProfileId(call.parameters["profileId"] ?: "")
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "profileId is required and must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "profileId"),
            )
            return@post
        }
        val request = call.receiveNullable<ProfileResetRequest>() ?: ProfileResetRequest()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val resetProfile = profileStore.reset(tenantId, profileId)
        if (resetProfile == null) {
            call.respondText(
                "Profile profileId=$profileId not found for tenantId=$tenantId",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        auditLog.log(
            runId = scopedRunId(tenantId, "profile:$profileId"),
            eventType = "profile_reset",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("reason", request.reason ?: "manual_reset")
            }.toString(),
        )
        call.respond(HttpStatusCode.OK, resetProfile)
    }

    post("/factory/profiles/{profileId}/incognito") {
        if (!profileStoreConfig.enabled) {
            call.respondApiError(
                status = HttpStatusCode.ServiceUnavailable,
                code = "PROFILE_STORE_DISABLED",
                message = "Profile store is disabled. Set PROFILE_STORE_ENABLED=true.",
            )
            return@post
        }
        val profileId = try {
            requireValidProfileId(call.parameters["profileId"] ?: "")
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "profileId is required and must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "profileId"),
            )
            return@post
        }
        val request = call.receiveNullable<ProfileIncognitoRequest>() ?: ProfileIncognitoRequest()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val updatedProfile = profileStore.setIncognito(tenantId, profileId, request.enabled)
        if (updatedProfile == null) {
            call.respondText(
                "Profile profileId=$profileId not found for tenantId=$tenantId",
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        auditLog.log(
            runId = scopedRunId(tenantId, "profile:$profileId"),
            eventType = "profile_incognito_updated",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("enabled", request.enabled)
                put("reason", request.reason ?: "manual_toggle")
            }.toString(),
        )
        call.respond(HttpStatusCode.OK, updatedProfile)
    }

    post("/intent/estimate") {
        val request = call.receiveOrValidationError<IntentEstimateRequest>() ?: return@post
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        if (request.apiVersion != API_VERSION_V1) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "apiVersion must be $API_VERSION_V1",
                details = mapOf("field" to "apiVersion"),
            )
            return@post
        }

        val query = request.input?.query?.trim().orEmpty()
        if (query.isBlank()) {
            call.respondApiError(
                status = HttpStatusCode.UnprocessableEntity,
                code = "INTENT_UNAVAILABLE",
                message = "Field input.query is required",
                details = mapOf("field" to "input.query"),
            )
            return@post
        }
        val references = (request.input?.references.orEmpty() + request.input?.referenceIds.orEmpty())
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
            .distinct()
        val riskTier = request.constraints?.riskTier?.trim()?.lowercase()
        if (riskTier != null && riskTier !in ALLOWED_RISK_TIERS) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "constraints.riskTier must be one of: ${ALLOWED_RISK_TIERS.joinToString(", ")}",
                details = mapOf("field" to "constraints.riskTier"),
            )
            return@post
        }
        val referenceOptions = buildReferenceOptions(query)
        val selectedReferenceIds = request.session
            ?.references
            ?.selectedIds
            .orEmpty()
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
            .distinct()
        if (selectedReferenceIds.size !in setOf(0, 2)) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "session.references.selected_ids must contain exactly 2 unique values",
                details = mapOf("field" to "session.references.selected_ids"),
            )
            return@post
        }
        val allowedOptionIds = referenceOptions.map { it.id }.toSet()
        if (selectedReferenceIds.any { it !in allowedOptionIds }) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "session.references.selected_ids must reference options from the 6-card set",
                details = mapOf("field" to "session.references.selected_ids"),
            )
            return@post
        }
        val resolvedReferenceIds = if (selectedReferenceIds.isNotEmpty()) selectedReferenceIds else references.take(2)

        val generationConstraints = buildList {
            addAll(references.map { "reference:$it" })
            if (riskTier != null) add("risk-tier:$riskTier")
        }
        val draft = try {
            intentGenerator.generate(query, generationConstraints)
        } catch (e: Exception) {
            call.respondApiError(
                status = HttpStatusCode.InternalServerError,
                code = "INTERNAL_ERROR",
                message = "Intent generation failed",
            )
            return@post
        }
        if (draft.outcome.result.isBlank()) {
            call.respondApiError(
                status = HttpStatusCode.UnprocessableEntity,
                code = "INTENT_UNAVAILABLE",
                message = "Intent cannot be extracted from input query",
            )
            return@post
        }

        val maxQuestions = (request.constraints?.maxClarifyingQuestions ?: 1).coerceIn(0, IntentClarification.MAX_QUESTIONS)
        val clarifyingQuestions = if (maxQuestions > 0) {
            listOfNotNull(
                intentClarifier.nextQuestion(
                    goal = query,
                    constraints = generationConstraints,
                    previousAnswers = emptyList(),
                    currentIntentDraft = draft,
                ),
            )
        } else {
            emptyList()
        }
        val resolvedRequestId = request.requestId?.trim().takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        val resolvedSession = resolveApiSession(
            session = request.session,
            referenceOptions = referenceOptions,
            selectedReferenceIds = resolvedReferenceIds,
        )
        val response = IntentEstimateResponse(
            apiVersion = API_VERSION_V1,
            tenantId = tenantId,
            requestId = resolvedRequestId,
            session = resolvedSession,
            intent = IntentEstimatePayload(
                outcome = draft.outcome.result,
                experience = "${draft.experience.feeling}; ${draft.experience.interactionStyle}",
                constraints = draft.constraints.mustHave,
                confidence = draft.confidence,
                referenceIds = resolvedReferenceIds,
            ),
            clarifyingQuestions = clarifyingQuestions,
            evidence = IntentEvidence(
                usedReferences = references.size,
                model = "intent_generator",
            ),
        )
        val auditPayload = buildJsonObject {
            put("tenantId", tenantId)
            put("requestId", resolvedRequestId)
            put("query", query)
            put("riskTier", riskTier ?: "not_set")
            put("clarifyingQuestionsCount", clarifyingQuestions.size)
            put("usedReferences", references.size)
            put("confidence", draft.confidence)
            put("sessionId", resolvedSession.sessionId.orEmpty())
            put(
                "reference_ids",
                buildJsonArray {
                    resolvedReferenceIds.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("referenceOptionsCount", referenceOptions.size)
        }
        auditLog.log(runId = scopedRunId(tenantId, resolvedRequestId), eventType = "intent_estimated", payload = auditPayload.toString())
        call.respond(HttpStatusCode.OK, response)
    }

    post("/experience/generate") {
        val request = call.receiveOrValidationError<ExperienceGenerateRequest>() ?: return@post
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        if (request.apiVersion != API_VERSION_V1) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "apiVersion must be $API_VERSION_V1",
                details = mapOf("field" to "apiVersion"),
            )
            return@post
        }

        val inputIntent = request.intent
        val outcome = inputIntent?.outcome?.trim().orEmpty()
        val experience = inputIntent?.experience?.trim().orEmpty()
        val constraints = inputIntent?.constraints?.mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }.orEmpty()
        val intentReferenceIds = inputIntent?.referenceIds
            .orEmpty()
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
            .distinct()
        val sessionSelectedReferenceIds = request.session
            ?.references
            ?.selectedIds
            .orEmpty()
            .mapNotNull { it.trim().takeIf { v -> v.isNotEmpty() } }
            .distinct()
        val resolvedReferenceIds = when {
            intentReferenceIds.isNotEmpty() -> intentReferenceIds
            else -> sessionSelectedReferenceIds
        }
        if (resolvedReferenceIds.size !in setOf(0, 2)) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "intent.reference_ids must contain exactly 2 unique values",
                details = mapOf("field" to "intent.reference_ids"),
            )
            return@post
        }
        val sessionOptionIds = request.session?.references?.options?.map { it.id }?.toSet().orEmpty()
        if (sessionOptionIds.isNotEmpty() && resolvedReferenceIds.any { it !in sessionOptionIds }) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "intent.reference_ids must be selected from session.references.options",
                details = mapOf("field" to "intent.reference_ids"),
            )
            return@post
        }
        if (outcome.isBlank() || experience.isBlank()) {
            call.respondApiError(
                status = HttpStatusCode.UnprocessableEntity,
                code = "INTENT_INSUFFICIENT",
                message = "intent.outcome and intent.experience are required",
            )
            return@post
        }

        val requestedVariants = request.generation?.variants ?: 3
        if (requestedVariants !in 3..7) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "generation.variants must be in range 3..7",
                details = mapOf("field" to "generation.variants"),
            )
            return@post
        }
        val includeRationale = request.generation?.includeRationale ?: false
        val intentText = buildString {
            append(outcome)
            append(". ")
            append(experience)
            if (constraints.isNotEmpty()) {
                append(". Constraints: ")
                append(constraints.joinToString("; "))
            }
            if (resolvedReferenceIds.isNotEmpty()) {
                append(". Reference IDs: ")
                append(resolvedReferenceIds.joinToString(", "))
            }
        }
        val candidates = try {
            intentCandidatesGenerator.generate(intentText)
        } catch (_: Exception) {
            emptyList()
        }
        if (candidates.size < 3) {
            call.respondApiError(
                status = HttpStatusCode.UnprocessableEntity,
                code = "INTENT_INSUFFICIENT",
                message = "Intent is insufficient for generating experience variants",
            )
            return@post
        }
        val expandedCandidates = ensureCandidateCount(candidates, requestedVariants)

        val profileId = request.profileId?.trim()?.takeUnless { it.isBlank() }?.let { raw ->
            try {
                requireValidProfileId(raw)
            } catch (_: IllegalArgumentException) {
                call.respondApiError(
                    status = HttpStatusCode.BadRequest,
                    code = "VALIDATION_ERROR",
                    message = "profileId must match ${TENANT_ID_REGEX.pattern}",
                    details = mapOf("field" to "profileId"),
                )
                return@post
            }
        }
        val profile = if (
            profileStoreConfig.enabled &&
            !request.incognito &&
            profileId != null
        ) {
            profileStore.get(tenantId, profileId)
        } else {
            null
        }
        val rankedCandidates = rankCandidates(
            candidates = expandedCandidates,
            requestedVariants = requestedVariants,
            profile = profile,
            allowProfileRanking = !request.incognito,
        )
        val resolvedRequestId = request.requestId?.trim().takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        val resolvedSession = resolveApiSession(
            session = request.session,
            referenceOptions = request.session?.references?.options ?: emptyList(),
            selectedReferenceIds = resolvedReferenceIds,
        )
        val variants = rankedCandidates.mapIndexed { index, ranked ->
            val candidate = ranked.candidate
            val title = candidate.substringBefore(':').trim().ifBlank { "Experience ${index + 1}" }
            val summary = candidate.substringAfter(':', candidate).trim()
            ExperienceVariant(
                id = "exp-${index + 1}",
                title = title,
                summary = summary,
                rationale = if (includeRationale) buildVariantRationale(
                    usedProfile = ranked.profileAdjustment != 0.0,
                    incognito = request.incognito,
                ) else null,
                score = ranked.score,
            )
        }
        val auditPayload = buildJsonObject {
            put("tenantId", tenantId)
            put("requestId", resolvedRequestId)
            put("variantsRequested", requestedVariants)
            put("variantsGenerated", variants.size)
            put("includeRationale", includeRationale)
            put("profileId", profileId ?: "none")
            put("incognito", request.incognito)
            put("profileRankingUsed", profile != null && !profile.incognito && !request.incognito)
            put("sessionId", resolvedSession.sessionId.orEmpty())
            put(
                "reference_ids",
                buildJsonArray {
                    resolvedReferenceIds.forEach { add(JsonPrimitive(it)) }
                },
            )
            put("ranking", buildJsonArray {
                rankedCandidates.forEach { ranked ->
                    add(
                        buildJsonObject {
                            put("candidate", ranked.candidate)
                            put("score", ranked.score)
                            put("profileAdjustment", ranked.profileAdjustment)
                        },
                    )
                }
            })
        }
        auditLog.log(runId = scopedRunId(tenantId, resolvedRequestId), eventType = "experience_generated", payload = auditPayload.toString())
        call.respond(
            HttpStatusCode.OK,
            ExperienceGenerateResponse(
                apiVersion = API_VERSION_V1,
                tenantId = tenantId,
                requestId = resolvedRequestId,
                session = resolvedSession,
                variants = variants,
                audit = ExperienceAudit(
                    event = "experience_generated",
                    tenantId = tenantId,
                    runId = resolvedRequestId,
                    sessionId = resolvedSession.sessionId.orEmpty(),
                    referenceIds = resolvedReferenceIds,
                ),
            ),
        )
    }

    post("/factory/run") {
        val request = call.receive<FactoryRunRequest>()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val runId = UUID.randomUUID().toString()
        val scopedRunId = scopedRunId(tenantId, runId)
        val costViolation = costBudgetGuard?.check(periodUsageStore.snapshot())
        if (costViolation != null) {
            auditLog.log(
                runId = scopedRunId,
                eventType = "run_rejected_cost_budget",
                payload = buildCostBudgetRejectionPayload(costViolation).toString(),
            )
            call.respondApiError(
                status = HttpStatusCode.fromValue(costViolation.statusCode),
                code = costViolation.code,
                message = costViolation.message,
                details = costViolation.details,
            )
            return@post
        }
        if (temporalClient != null) {
            TemporalFactoryWorker.startWorkflow(temporalClient, scopedRunId, request)
            FactoryMetrics.recordRun("started", 0)
            periodUsageStore.record(PeriodUsageDelta(runs = 1))
            call.respond(
                HttpStatusCode.Accepted,
                FactoryRunResponse(
                    runId = runId,
                    tenantId = tenantId,
                    status = "started",
                    message = "Workflow started. Poll GET /factory/runs/$runId for result.",
                ),
            )
        } else {
            val startMs = System.currentTimeMillis()
            val before = FactoryMetrics.snapshot()
            val result = workflowRunner.run(scopedRunId, request)
            FactoryMetrics.recordRun(result.status, System.currentTimeMillis() - startMs)
            val after = FactoryMetrics.snapshot()
            periodUsageStore.record(after.diffForPeriod(before))
            call.respond(
                FactoryRunResponse(
                    runId = runId,
                    tenantId = tenantId,
                    status = result.status,
                    message = result.message,
                ),
            )
        }
    }

    post("/factory/runs/{runId}/retry") {
        val runId = call.parameters.requireRunId()
        val request = call.receive<FactoryRunRequest>()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val scopedRunId = scopedRunId(tenantId, runId)
        val costViolation = costBudgetGuard?.check(periodUsageStore.snapshot())
        if (costViolation != null) {
            auditLog.log(
                runId = scopedRunId,
                eventType = "run_rejected_cost_budget",
                payload = buildCostBudgetRejectionPayload(costViolation).toString(),
            )
            call.respondApiError(
                status = HttpStatusCode.fromValue(costViolation.statusCode),
                code = costViolation.code,
                message = costViolation.message,
                details = costViolation.details,
            )
            return@post
        }
        val startMs = System.currentTimeMillis()
        val before = FactoryMetrics.snapshot()
        val result = workflowRunner.run(scopedRunId, request)
        FactoryMetrics.recordRun(result.status, System.currentTimeMillis() - startMs)
        val after = FactoryMetrics.snapshot()
        periodUsageStore.record(after.diffForPeriod(before))
        call.respond(
            FactoryRunResponse(
                runId = runId,
                tenantId = tenantId,
                status = result.status,
                message = result.message,
            ),
        )
    }

    get("/factory/runs/{runId}") {
        val runId = call.parameters.requireRunId()
        val tenantId = try {
            resolveTenantId(call, null)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@get
        }
        val record = artifactRegistry.get(scopedRunId(tenantId, runId))
        if (record == null) {
            call.respondText("Run runId=$runId not found for tenantId=$tenantId", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respond(record.copy(runId = runId))
    }

    get("/factory/runs/{runId}/decision-context") {
        val runId = call.parameters.requireRunId()
        val tenantId = try {
            resolveTenantId(call, null)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@get
        }
        val scopedRunId = scopedRunId(tenantId, runId)
        val approval = approvalStore.get(scopedRunId)
        val pendingQuestion = askUserStore.getPendingQuestion(scopedRunId)
        val artifact = artifactRegistry.get(scopedRunId)?.copy(runId = runId)
        val recent = auditLog.recentEvents(scopedRunId, DECISION_CONTEXT_EVENT_LIMIT)
        val response = buildDecisionContextResponse(
            runId = runId,
            tenantId = tenantId,
            artifact = artifact,
            approval = approval?.withPublicRunId(tenantId),
            pendingQuestion = pendingQuestion?.withPublicRunId(tenantId),
            events = recent,
        )
        call.respond(HttpStatusCode.OK, response)
    }

    get("/factory/approvals/{runId}") {
        val runId = call.parameters.requireRunId()
        val tenantId = try {
            resolveTenantId(call, null)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@get
        }
        val approval = approvalStore.get(scopedRunId(tenantId, runId))
        if (approval == null) {
            call.respondText("Approval request for runId=$runId was not found for tenantId=$tenantId", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respond(approval.withPublicRunId(tenantId))
    }

    post("/factory/approvals/{runId}/approve") {
        call.handleApprovalDecisionForTenant(
            action = approvalStore::approve,
            successStatus = "approved",
            successMessage = "",
        )
    }

    post("/factory/runs/{runId}/sprint-point") {
        val runId = call.parameters.requireRunId()
        val request = call.receiveNullable<SprintPointDecisionRequest>() ?: SprintPointDecisionRequest()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val stage = request.stage?.trim().orEmpty()
        if (stage.isBlank()) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "stage is required",
                details = mapOf("field" to "stage"),
            )
            return@post
        }
        val decision = request.decision?.trim()?.lowercase().orEmpty()
        if (decision !in setOf("proceed", "hold", "reject")) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "decision must be one of: proceed, hold, reject",
                details = mapOf("field" to "decision"),
            )
            return@post
        }
        val scopedRunId = scopedRunId(tenantId, runId)
        val decidedBy = request.decidedBy?.trim().takeUnless { it.isNullOrBlank() } ?: "operator"
        val payload = buildJsonObject {
            put("runId", scopedRunId)
            put("stage", stage)
            put("decision", decision)
            put("decidedBy", decidedBy)
            request.note?.trim()?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        }
        auditLog.log(scopedRunId, "sprint_point_decision", payload.toString())
        call.respond(
            HttpStatusCode.OK,
            SprintPointDecisionResponse(
                runId = runId,
                tenantId = tenantId,
                status = decision,
                message = "Sprint point decision stored in audit log",
            ),
        )
    }

    post("/factory/approvals/{runId}/reject") {
        call.handleApprovalDecisionForTenant(
            action = approvalStore::reject,
            successStatus = "rejected",
            successMessage = "Approval rejected",
        )
    }

    post("/factory/runs/{runId}/answer") {
        val runId = call.parameters.requireRunId()
        val request = call.receive<AskUserAnswerRequest>()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val scopedRunId = scopedRunId(tenantId, runId)
        val pendingQuestion = askUserStore.getPendingQuestion(scopedRunId)
        if (pendingQuestion != null) {
            val resolvedChoice = resolveQuestionChoice(request.choice, pendingQuestion.options)
            val answered = askUserStore.submitAnswer(scopedRunId, resolvedChoice) ?: run {
                call.respond(
                    HttpStatusCode.NotFound,
                    AskUserAnswerResponse(
                        runId = runId,
                        tenantId = tenantId,
                        status = "not_found",
                        message = "Pending question for runId=$runId was not found",
                    ),
                )
                return@post
            }
            maybeLogIntentCandidateSelected(
                auditLog = auditLog,
                runId = scopedRunId,
                stepId = answered.stepId,
                selectedValue = answered.answer,
                options = answered.options,
                reason = request.reason,
            )
            maybeUpdateProfileFromCandidateSelection(
                auditLog = auditLog,
                profileStore = profileStore,
                profileStoreConfig = profileStoreConfig,
                tenantId = tenantId,
                runId = scopedRunId,
                stepId = answered.stepId,
                selectedValue = answered.answer,
                profileIdRaw = request.profileId,
                profileStorageConsent = request.profileStorageConsent,
                incognito = request.incognito,
            )
            call.respond(
                HttpStatusCode.OK,
                AskUserAnswerResponse(
                    runId = runId,
                    tenantId = tenantId,
                    status = "answered",
                    message = "Answer saved. Continue with poll/retry flow.",
                    question = answered.withPublicRunId(tenantId),
                ),
            )
            return@post
        }

        val approval = approvalStore.get(scopedRunId)
        if (approval == null) {
            call.respond(
                HttpStatusCode.NotFound,
                AskUserAnswerResponse(
                    runId = runId,
                    tenantId = tenantId,
                    status = "not_found",
                    message = "No pending question or approval request for runId=$runId",
                ),
            )
            return@post
        }
        if (approval.status != ApprovalStatus.PENDING) {
            call.respond(
                HttpStatusCode.Conflict,
                AskUserAnswerResponse(
                    runId = runId,
                    tenantId = tenantId,
                    status = "already_decided",
                    message = "Approval request for runId=$runId is already ${approval.status.name.lowercase()}",
                    approval = approval.withPublicRunId(tenantId),
                ),
            )
            return@post
        }

        val approvalDecision = resolveApprovalChoice(request.choice)
        if (approvalDecision == null) {
            throw BadRequestException("choice must map to approval decision (A/B, approve/reject, or 1/2)")
        }
        val updatedApproval = if (approvalDecision) {
            approvalStore.approve(scopedRunId, decidedBy = "operator", comment = "answered via /factory/runs/{runId}/answer")
        } else {
            approvalStore.reject(scopedRunId, decidedBy = "operator", comment = "answered via /factory/runs/{runId}/answer")
        }
        call.respond(
            HttpStatusCode.OK,
            AskUserAnswerResponse(
                runId = runId,
                tenantId = tenantId,
                status = if (approvalDecision) "approved" else "rejected",
                message = if (approvalDecision) {
                    "Approval granted. Retry workflow with POST /factory/runs/$runId/retry"
                } else {
                    "Approval rejected"
                },
                approval = updatedApproval?.withPublicRunId(tenantId),
            ),
        )
    }

    post("/factory/promotion/{runId}/evaluate") {
        val runId = call.parameters.requireRunId()
        val request = call.receiveNullable<PromotionSignalsRequest>() ?: PromotionSignalsRequest()
        val tenantId = try {
            resolveTenantId(call, request.tenantId)
        } catch (_: IllegalArgumentException) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
                details = mapOf("field" to "tenantId"),
            )
            return@post
        }
        val scopedRunId = scopedRunId(tenantId, runId)
        val metrics = FactoryMetrics.snapshot()
        val artifact = artifactRegistry.get(scopedRunId)
        val hardFailures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val classifiedRuns = metrics.acceptedRuns + metrics.rejectedRuns

        if (request.minSuccessRate !in 0.0..1.0) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "minSuccessRate must be between 0 and 1",
                details = mapOf("field" to "minSuccessRate"),
            )
            return@post
        }
        if (request.maxP99Seconds <= 0.0) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "maxP99Seconds must be > 0",
                details = mapOf("field" to "maxP99Seconds"),
            )
            return@post
        }
        if (request.minClassifiedRuns < 0) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "minClassifiedRuns must be >= 0",
                details = mapOf("field" to "minClassifiedRuns"),
            )
            return@post
        }
        if (request.minRunDurationSamples < 0) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "minRunDurationSamples must be >= 0",
                details = mapOf("field" to "minRunDurationSamples"),
            )
            return@post
        }
        if (request.maxArtifactAgeHours != null && request.maxArtifactAgeHours < 0) {
            call.respondApiError(
                status = HttpStatusCode.BadRequest,
                code = "VALIDATION_ERROR",
                message = "maxArtifactAgeHours must be >= 0",
                details = mapOf("field" to "maxArtifactAgeHours"),
            )
            return@post
        }

        val successRate = metrics.successRate
        if (successRate == null) {
            hardFailures += "No classified runs in /metrics (accepted/rejected) to compute success_rate"
        } else if (successRate < request.minSuccessRate) {
            hardFailures += "success_rate=${formatFraction(successRate)} < minSuccessRate=${formatFraction(request.minSuccessRate)}"
        }
        if (classifiedRuns < request.minClassifiedRuns) {
            val reason = "classified_runs=$classifiedRuns < minClassifiedRuns=${request.minClassifiedRuns}"
            if (request.decisionMode == PromotionDecisionMode.AUTO) {
                hardFailures += reason
            } else {
                warnings += reason
            }
        }

        val p99 = metrics.p99DurationSeconds
        if (p99 == null) {
            hardFailures += "No run duration data in /metrics to compute p99"
        } else if (p99 > request.maxP99Seconds) {
            hardFailures += "p99=${formatSeconds(p99)}s > maxP99Seconds=${formatSeconds(request.maxP99Seconds)}s"
        }
        if (metrics.runDurationCount < request.minRunDurationSamples) {
            val reason = "run_duration_count=${metrics.runDurationCount} < minRunDurationSamples=${request.minRunDurationSamples}"
            if (request.decisionMode == PromotionDecisionMode.AUTO) {
                hardFailures += reason
            } else {
                warnings += reason
            }
        }

        request.maxInputTokensTotal?.let { limit ->
            if (metrics.llmInputTokensTotal > limit) {
                hardFailures += "llm_input_tokens_total=${metrics.llmInputTokensTotal} > maxInputTokensTotal=$limit"
            }
        }
        request.maxOutputTokensTotal?.let { limit ->
            if (metrics.llmOutputTokensTotal > limit) {
                hardFailures += "llm_output_tokens_total=${metrics.llmOutputTokensTotal} > maxOutputTokensTotal=$limit"
            }
        }

        val hasDoneManifest = artifactRegistry.hasManifest(scopedRunId, productfactory.workflow.WorkflowState.DONE)
        val doneManifestPath = artifactRegistry.manifestPath(scopedRunId, productfactory.workflow.WorkflowState.DONE)
        val artifactAgeHours = artifact?.updatedAt?.let { updatedAt ->
            runCatching {
                val updated = Instant.parse(updatedAt)
                val duration = java.time.Duration.between(updated, Instant.now())
                duration.toMillis().coerceAtLeast(0L).toDouble() / 3_600_000.0
            }.getOrNull()
        }

        if (artifact == null) {
            hardFailures += "artifact_registry record not found for runId=$runId"
        } else {
            if (request.requireDoneState && artifact.workflowState != "DONE") {
                hardFailures += "artifact workflowState=${artifact.workflowState} (required DONE)"
            }
            if (request.requireArtifactLocation && artifact.artifactLocation.isNullOrBlank()) {
                hardFailures += "artifactLocation is required but missing in artifact registry"
            }
            if (request.requireRepoUrl && artifact.repoUrl.isNullOrBlank()) {
                hardFailures += "repoUrl is required but missing in artifact registry"
            }
            request.maxArtifactAgeHours?.let { limit ->
                if (artifactAgeHours == null) {
                    hardFailures += "artifact updatedAt is missing or invalid in artifact registry"
                } else if (artifactAgeHours > limit.toDouble()) {
                    hardFailures += "artifact_age_hours=${formatSeconds(artifactAgeHours)} > maxArtifactAgeHours=$limit"
                }
            }
        }
        if (request.requireDoneManifest && !hasDoneManifest) {
            hardFailures += "DONE manifest not found in artifact registry for runId=$runId"
        }

        val decision = when {
            hardFailures.isNotEmpty() -> PromotionDecision.BLOCK
            request.decisionMode == PromotionDecisionMode.SEMI_AUTO && warnings.isNotEmpty() -> PromotionDecision.REVIEW
            else -> PromotionDecision.ALLOW
        }
        val reasons = hardFailures + warnings

        val response = PromotionSignalsResponse(
            runId = runId,
            tenantId = tenantId,
            decisionMode = request.decisionMode,
            decision = decision,
            allowPromotion = decision == PromotionDecision.ALLOW,
            requiresHumanReview = decision == PromotionDecision.REVIEW,
            reasons = reasons,
            hardFailures = hardFailures,
            warnings = warnings,
            thresholds = request,
            metrics = PromotionSignalsMetrics(
                acceptedRuns = metrics.acceptedRuns,
                rejectedRuns = metrics.rejectedRuns,
                classifiedRuns = classifiedRuns,
                runDurationCount = metrics.runDurationCount,
                successRate = metrics.successRate,
                p99DurationSeconds = metrics.p99DurationSeconds?.takeIf { it.isFinite() },
                llmInputTokensTotal = metrics.llmInputTokensTotal,
                llmOutputTokensTotal = metrics.llmOutputTokensTotal,
            ),
            artifactSignals = PromotionSignalsArtifact(
                hasRecord = artifact != null,
                hasDoneManifest = hasDoneManifest,
                doneManifestPath = doneManifestPath,
                ageHours = artifactAgeHours,
            ),
            artifact = artifact?.copy(runId = runId),
        )
        auditLog.log(
            runId = scopedRunId,
            eventType = "promotion_signals_evaluated",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("allowPromotion", response.allowPromotion)
                put("decisionMode", response.decisionMode.name.lowercase())
                put("decision", response.decision.name.lowercase())
                put("reasonsCount", reasons.size)
                put("hardFailuresCount", hardFailures.size)
                put("warningsCount", warnings.size)
                put("minSuccessRate", request.minSuccessRate)
                put("maxP99Seconds", request.maxP99Seconds)
            }.toString(),
        )
        call.respond(HttpStatusCode.OK, response)
    }
}

private fun FactoryMetricsSnapshot.diffForPeriod(
    before: FactoryMetricsSnapshot,
): PeriodUsageDelta {
    val tokensBefore = before.llmInputTokensTotal + before.llmOutputTokensTotal
    val tokensAfter = llmInputTokensTotal + llmOutputTokensTotal
    return PeriodUsageDelta(
        runs = 1,
        tokens = (tokensAfter - tokensBefore).coerceAtLeast(0),
        toolCalls = (toolCallsTotal - before.toolCallsTotal).coerceAtLeast(0),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.handleApprovalDecisionForTenant(
    action: (runId: String, decidedBy: String, comment: String?) -> ApprovalRecord?,
    successStatus: String,
    successMessage: String,
) {
    val runId = parameters.requireRunId()
    val decision = receiveNullable<ApprovalDecisionRequest>() ?: ApprovalDecisionRequest()
    val tenantId = try {
        resolveTenantId(this, decision.tenantId)
    } catch (_: IllegalArgumentException) {
        respondApiError(
            status = HttpStatusCode.BadRequest,
            code = "VALIDATION_ERROR",
            message = "tenantId must match ${TENANT_ID_REGEX.pattern}",
            details = mapOf("field" to "tenantId"),
        )
        return
    }
    val internalRunId = scopedRunId(tenantId, runId)
    val decidedBy = decision.decidedBy?.trim().takeUnless { it.isNullOrBlank() } ?: "operator"
    val approval = action(internalRunId, decidedBy, decision.comment)
    if (approval == null) {
        respond(
            HttpStatusCode.NotFound,
            ApprovalDecisionResponse(
                runId = runId,
                tenantId = tenantId,
                status = "not_found",
                message = "Approval request for runId=$runId was not found",
            ),
        )
        return
    }
    val message = if (successStatus == "approved") {
        "Approval granted. Retry workflow with POST /factory/runs/$runId/retry"
    } else {
        successMessage
    }
    respond(
        HttpStatusCode.OK,
        ApprovalDecisionResponse(
            runId = runId,
            tenantId = tenantId,
            status = successStatus,
            message = message,
            approval = approval.withPublicRunId(tenantId),
        ),
    )
}

private fun resolveQuestionChoice(choice: JsonPrimitive, options: List<String>): String {
    if (options.isEmpty()) {
        throw BadRequestException("question options must not be empty")
    }

    if (choice.isString) {
        val textChoice = choice.content.trim()
        if (textChoice.isBlank()) {
            throw BadRequestException("choice must not be blank")
        }
        options.firstOrNull { it == textChoice }?.let { return it }
        options.firstOrNull { it.equals(textChoice, ignoreCase = true) }?.let { return it }
        letterChoiceToIndex(textChoice)?.let { idx ->
            options.getOrNull(idx)?.let { return it }
        }
        textChoice.toIntOrNull()?.let { idx ->
            resolveIndexChoice(options, idx)?.let { return it }
        }
    } else {
        choice.intOrNull?.let { idx ->
            resolveIndexChoice(options, idx)?.let { return it }
        }
    }

    throw BadRequestException("choice must reference one of options: ${options.joinToString(", ")}")
}

private fun ProfileRuleRequest.toDomainRule(): PreferenceRule {
    val normalizedType = type.trim().uppercase()
    val domainType = runCatching { PreferenceRuleType.valueOf(normalizedType) }.getOrNull()
        ?: throw IllegalArgumentException("Unsupported rule type: $type")
    val normalizedValue = value.trim()
    require(normalizedValue.isNotBlank()) { "rule value must not be blank" }
    return PreferenceRule(
        type = domainType,
        value = normalizedValue,
        weight = weight,
    )
}

private fun resolveApprovalChoice(choice: JsonPrimitive): Boolean? {
    if (choice.isString) {
        val textChoice = choice.content.trim()
        if (textChoice.isBlank()) {
            throw BadRequestException("choice must not be blank")
        }
        return when (textChoice.lowercase()) {
            "a", "approve", "approved", "yes", "y", "true" -> true
            "b", "reject", "rejected", "no", "n", "false" -> false
            else -> textChoice.toIntOrNull()?.let { idx -> resolveApprovalIndex(idx) }
        }
    }
    return choice.intOrNull?.let { idx -> resolveApprovalIndex(idx) }
}

private fun resolveApprovalIndex(index: Int): Boolean? = when (index) {
    0, 1 -> true
    2 -> false
    else -> null
}

private fun resolveIndexChoice(options: List<String>, index: Int): String? {
    if (index in 1..options.size) {
        return options[index - 1]
    }
    if (index in options.indices) {
        return options[index]
    }
    return null
}

private fun letterChoiceToIndex(choice: String): Int? {
    if (choice.length != 1) return null
    val c = choice[0].uppercaseChar()
    if (c < 'A' || c > 'Z') return null
    return c.code - 'A'.code
}

private fun formatFraction(value: Double): String = String.format(Locale.US, "%.4f", value)

private fun formatSeconds(value: Double): String {
    if (!value.isFinite()) return "inf"
    val rounded = (value * 100.0).roundToLong() / 100.0
    return String.format(Locale.US, "%.2f", rounded)
}

private fun maybeLogIntentCandidateSelected(
    auditLog: AuditLog,
    runId: String,
    stepId: String,
    selectedValue: String?,
    options: List<String>,
    reason: String?,
) {
    if (stepId != WorkflowStepId.SELECT_INTENT_CANDIDATE || selectedValue == null) {
        return
    }
    val candidateIndex = options.indexOf(selectedValue)
    if (candidateIndex < 0) return
    val normalizedReason = reason?.trim().takeUnless { it.isNullOrBlank() }
    val payload = buildJsonObject {
        put("runId", runId)
        put("candidateIndex", candidateIndex)
        put("candidate", selectedValue)
        put("explanation", normalizedReason ?: "Candidate selected via ask-user choice.")
        if (normalizedReason != null) {
            put("reason", normalizedReason)
        }
    }
    auditLog.log(runId = runId, eventType = "intent_candidate_selected", payload = payload.toString())
}

private fun maybeUpdateProfileFromCandidateSelection(
    auditLog: AuditLog,
    profileStore: ProfileStore,
    profileStoreConfig: ProfileStoreConfig,
    tenantId: String,
    runId: String,
    stepId: String,
    selectedValue: String?,
    profileIdRaw: String?,
    profileStorageConsent: Boolean?,
    incognito: Boolean,
) {
    if (!profileStoreConfig.enabled) return
    if (stepId != WorkflowStepId.SELECT_INTENT_CANDIDATE) return
    if (incognito || selectedValue == null) {
        auditLog.log(
            runId = runId,
            eventType = "profile_auto_update_skipped",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("reason", if (incognito) "incognito" else "empty_selection")
            }.toString(),
        )
        return
    }
    val profileId = try {
        requireValidProfileId(profileIdRaw?.trim().takeUnless { it.isNullOrBlank() } ?: "default")
    } catch (_: IllegalArgumentException) {
        auditLog.log(
            runId = runId,
            eventType = "profile_auto_update_skipped",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("reason", "invalid_profile_id")
            }.toString(),
        )
        return
    }

    val existing = profileStore.get(tenantId, profileId)
    if (existing?.incognito == true) {
        auditLog.log(
            runId = runId,
            eventType = "profile_auto_update_skipped",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("reason", "profile_incognito")
            }.toString(),
        )
        return
    }
    val hasConsent = existing?.consent?.profileStorage == true || profileStorageConsent == true || !profileStoreConfig.requireConsent
    if (!hasConsent) {
        auditLog.log(
            runId = runId,
            eventType = "profile_auto_update_skipped",
            payload = buildJsonObject {
                put("tenantId", tenantId)
                put("profileId", profileId)
                put("reason", "consent_required")
            }.toString(),
        )
        return
    }

    val candidateRule = PreferenceRule(
        type = PreferenceRuleType.LIKE,
        value = candidatePreferenceValue(selectedValue),
        weight = 1.0,
    )
    val mergedRules = (existing?.rules.orEmpty() + candidateRule)
        .distinctBy { "${it.type}:${it.value.lowercase()}" }
        .takeLast(profileStoreConfig.maxRules)
    val consent = existing?.consent ?: ProfileConsent(
        profileStorage = true,
        source = "auto_candidate_selection",
    )
    val updated = profileStore.upsert(
        tenantId = tenantId,
        profileId = profileId,
        embedding = existing?.embedding ?: emptyList(),
        embeddingModel = existing?.embeddingModel,
        rules = mergedRules,
        consent = consent,
    )
    auditLog.log(
        runId = runId,
        eventType = "profile_auto_updated_from_candidate",
        payload = buildJsonObject {
            put("tenantId", tenantId)
            put("profileId", profileId)
            put("selectedCandidate", selectedValue)
            put("revision", updated.revision)
            put("rulesCount", updated.rules.size)
        }.toString(),
    )
}

private fun candidatePreferenceValue(candidate: String): String {
    val summary = candidate.substringAfter(':', candidate)
    val normalized = summary.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return "candidate_selection"
    val words = normalized.split(" ")
        .filter { it.length >= 4 }
        .distinct()
        .take(6)
    return words.joinToString(" ").ifBlank { normalized.take(80) }
}

private fun buildDecisionContextResponse(
    runId: String,
    tenantId: String,
    artifact: ArtifactRunRecord?,
    approval: ApprovalRecord?,
    pendingQuestion: AskUserQuestion?,
    events: List<productfactory.workflow.AuditEvent>,
): DecisionContextResponse {
    val filteredEvents = events.filter { it.eventType in DECISION_CONTEXT_EVENT_TYPES }
    val latestPlan = filteredEvents.lastOrNull { it.eventType == "pipeline_plan" }?.payload?.parseJsonObjectOrNull()
    val explanation = filteredEvents.lastOrNull { it.eventType == "planner_explanation" }
        ?.payload
        ?.parseJsonObjectOrNull()
        ?.get("explanation")
        ?.jsonPrimitive
        ?.content
        ?.takeIf { it.isNotBlank() }

    val risks = mutableListOf<DecisionRiskItem>()
    approval?.let {
        risks += DecisionRiskItem(
            source = "approval",
            summary = it.reason,
            details = buildJsonObject {
                put("status", it.status.name)
                put("timestamp", it.timestamp)
                put("proposed_actions", buildJsonArray { it.proposedActions.forEach { action -> add(JsonPrimitive(action)) } })
            },
        )
    }
    filteredEvents.filter { it.eventType == "approval_required" }.forEach { event ->
        val payload = event.payload.parseJsonObjectOrNull()
        val reason = payload?.get("reason")?.jsonPrimitive?.contentOrNull ?: "Approval required by policy"
        risks += DecisionRiskItem(
            source = "policy",
            summary = reason,
            details = payload,
        )
    }
    filteredEvents.filter { it.eventType == "risk_assessment" }.forEach { event ->
        val payload = event.payload.parseJsonObjectOrNull()
        val summary = payload?.get("summary")?.jsonPrimitive?.contentOrNull ?: "Risk assessment event"
        risks += DecisionRiskItem(
            source = "risk_assessment",
            summary = summary,
            details = payload,
        )
    }

    val options = when {
        pendingQuestion != null -> pendingQuestion.options
        approval?.status == ApprovalStatus.PENDING -> listOf("approve", "reject")
        else -> emptyList()
    }

    return DecisionContextResponse(
        runId = runId,
        tenantId = tenantId,
        artifact = artifact,
        approval = approval,
        pendingQuestion = pendingQuestion,
        plan = latestPlan,
        plannerExplanation = explanation,
        risks = risks.distinctBy { "${it.source}:${it.summary}" },
        options = options,
    )
}

private fun String.parseJsonObjectOrNull(): JsonObject? =
    runCatching { DECISION_JSON.parseToJsonElement(this).jsonObject }.getOrNull()

private data class MutablePolicyStatCounter(
    var approvalRequired: Long = 0L,
    var blocked: Long = 0L,
    var executed: Long = 0L,
)

private data class PolicyBucketKey(
    val actionType: String,
    val riskTier: String,
)

private fun resolveAuditLogPath(auditLog: AuditLog): String? {
    if (auditLog is FileAuditLog) return auditLog.path
    return System.getenv("AUDIT_LOG_PATH")?.trim()?.takeIf { it.isNotBlank() }
}

private fun computePolicyStats(
    auditLogPath: String,
    tenantFilter: String?,
    since: Instant?,
): PolicyStatsResponse {
    val registryPath = System.getenv("FACTORY_TOOLS_REGISTRY_PATH")?.trim()?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: Path.of("contracts", "tools.registry.json")
    val toolRegistry = loadToolRegistry(registryPath)
    val riskTierByTool = toolRegistry?.entries?.associate { it.name to it.risk_tier }.orEmpty()

    val perActionRisk = linkedMapOf<PolicyBucketKey, MutablePolicyStatCounter>()
    val perRiskTier = linkedMapOf<String, MutablePolicyStatCounter>()

    var processedEvents = 0L
    var approvalEvents = 0L
    var blockedEvents = 0L
    var executedEvents = 0L

    val file = File(auditLogPath)
    if (file.isFile) {
        file.useLines { lines ->
            lines.forEach { line ->
                val event = runCatching { DECISION_JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                val runId = event["runId"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (!runIdMatchesTenant(runId, tenantFilter)) return@forEach
                val timestamp = event["timestamp"]?.jsonPrimitive?.contentOrNull
                if (since != null) {
                    val eventTime = timestamp?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@forEach
                    if (eventTime.isBefore(since)) return@forEach
                }

                val eventType = event["eventType"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val payload = event["payload"]?.jsonPrimitive?.contentOrNull?.parseJsonObjectOrNull()
                processedEvents += 1

                when (eventType) {
                    "tool_call_executed" -> {
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val riskTier = riskTierByTool[toolName] ?: "unknown"
                        val key = PolicyBucketKey(actionType = toolName, riskTier = riskTier)
                        perActionRisk.getOrPut(key) { MutablePolicyStatCounter() }.executed += 1
                        perRiskTier.getOrPut(riskTier) { MutablePolicyStatCounter() }.executed += 1
                        executedEvents += 1
                    }

                    "tool_call_denied" -> {
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val riskTier = riskTierByTool[toolName] ?: "unknown"
                        val key = PolicyBucketKey(actionType = toolName, riskTier = riskTier)
                        perActionRisk.getOrPut(key) { MutablePolicyStatCounter() }.blocked += 1
                        perRiskTier.getOrPut(riskTier) { MutablePolicyStatCounter() }.blocked += 1
                        blockedEvents += 1
                    }

                    "approval_required" -> {
                        val actions = payload?.get("proposed_actions")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                        val toolName = actions.firstNotNullOfOrNull { action ->
                            if (action.startsWith("tool:")) action.removePrefix("tool:").takeIf { it.isNotBlank() } else null
                        } ?: return@forEach
                        val riskTier = actions.firstNotNullOfOrNull { action ->
                            if (action.startsWith("risk_tier:")) action.removePrefix("risk_tier:").takeIf { it.isNotBlank() } else null
                        } ?: riskTierByTool[toolName] ?: "unknown"
                        val key = PolicyBucketKey(actionType = toolName, riskTier = riskTier)
                        perActionRisk.getOrPut(key) { MutablePolicyStatCounter() }.approvalRequired += 1
                        perRiskTier.getOrPut(riskTier) { MutablePolicyStatCounter() }.approvalRequired += 1
                        approvalEvents += 1
                    }
                }
            }
        }
    }

    val byActionType = perActionRisk.entries
        .sortedWith(
            compareByDescending<Map.Entry<PolicyBucketKey, MutablePolicyStatCounter>> {
                it.value.approvalRequired + it.value.blocked + it.value.executed
            }
                .thenBy { it.key.actionType }
                .thenBy { it.key.riskTier },
        )
        .map { (key, value) ->
            PolicyActionRiskStats(
                actionType = key.actionType,
                riskTier = key.riskTier,
                approvalRequiredEvents = value.approvalRequired,
                blockedEvents = value.blocked,
                executedToolCalls = value.executed,
            )
        }

    val byRiskTier = perRiskTier.entries
        .sortedWith(compareByDescending<Map.Entry<String, MutablePolicyStatCounter>> { it.value.approvalRequired + it.value.blocked + it.value.executed }.thenBy { it.key })
        .map { (riskTier, value) ->
            PolicyRiskTierStats(
                riskTier = riskTier,
                approvalRequiredEvents = value.approvalRequired,
                blockedEvents = value.blocked,
                executedToolCalls = value.executed,
            )
        }

    return PolicyStatsResponse(
        generatedAt = Instant.now().toString(),
        auditLogPath = auditLogPath,
        tenantId = tenantFilter,
        since = since?.toString(),
        totals = PolicyStatsTotals(
            processedEvents = processedEvents,
            approvalRequiredEvents = approvalEvents,
            blockedEvents = blockedEvents,
            executedToolCalls = executedEvents,
            uniqueRiskTiers = byRiskTier.size,
            uniqueActionTypes = byActionType.map { it.actionType }.toSet().size,
        ),
        byRiskTier = byRiskTier,
        byActionType = byActionType,
    )
}

private fun runIdMatchesTenant(runId: String, tenantFilter: String?): Boolean {
    if (tenantFilter == null) return true
    if (tenantFilter == DEFAULT_TENANT_ID) return !runId.contains("::")
    return runId.startsWith("$tenantFilter::")
}

private fun buildMinimalDecisionUiHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Product Factory Decision UI</title>
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 24px; background: #f5f7fb; color: #102036; }
    h1 { margin: 0 0 16px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
    .card { background: #fff; border-radius: 10px; border: 1px solid #d9e1ef; padding: 12px; margin-bottom: 12px; }
    input, select, textarea, button { font: inherit; padding: 8px 10px; border-radius: 8px; border: 1px solid #bfcbe0; }
    button { cursor: pointer; background: #0f4fa8; border-color: #0f4fa8; color: #fff; }
    button.secondary { background: #fff; color: #0f4fa8; }
    pre { background: #081223; color: #d6e6ff; padding: 10px; border-radius: 8px; overflow: auto; max-height: 320px; }
    .muted { color: #576883; font-size: 14px; }
  </style>
</head>
<body>
  <h1>Product Factory: Decisions</h1>
  <div class="card">
    <div class="row">
      <input id="runId" placeholder="runId" />
      <input id="tenantId" placeholder="tenantId (default)" value="default" />
      <button id="loadBtn">Load</button>
    </div>
    <div id="meta" class="muted"></div>
  </div>
  <div class="card">
    <h3>Plan</h3>
    <pre id="plan">{}</pre>
  </div>
  <div class="card">
    <h3>Risks</h3>
    <pre id="risks">[]</pre>
  </div>
  <div class="card">
    <h3>Options</h3>
    <div id="options" class="row"></div>
    <div class="row">
      <input id="choice" placeholder="manual choice (A/B, 1/2, value)" />
      <input id="reason" placeholder="reason (optional)" />
      <button id="answerBtn">Send answer</button>
    </div>
  </div>
  <div class="card">
    <h3>Approvals</h3>
    <div class="row">
      <button id="approveBtn">Approve</button>
      <button id="rejectBtn" class="secondary">Reject</button>
    </div>
  </div>
  <div class="card">
    <h3>Sprint Point</h3>
    <div class="row">
      <input id="stage" placeholder="stage (e.g. ws3-v1)" />
      <select id="decision">
        <option value="proceed">proceed</option>
        <option value="hold">hold</option>
        <option value="reject">reject</option>
      </select>
      <input id="note" placeholder="note (optional)" />
      <button id="sprintPointBtn">Record</button>
    </div>
  </div>
  <div class="card">
    <h3>Last response</h3>
    <pre id="out">Ready.</pre>
  </div>
  <script>
    let current = null;
    const el = (id) => document.getElementById(id);
    const asJson = (v) => JSON.stringify(v ?? {}, null, 2);
    const runId = () => el("runId").value.trim();
    const tenantId = () => el("tenantId").value.trim() || "default";
    const setOut = (v) => el("out").textContent = typeof v === "string" ? v : asJson(v);

    async function loadContext() {
      if (!runId()) { setOut("runId is required"); return; }
      const resp = await fetch('/factory/runs/' + encodeURIComponent(runId()) + '/decision-context?tenantId=' + encodeURIComponent(tenantId()));
      const body = await resp.json();
      if (!resp.ok) { setOut(body); return; }
      current = body;
      el("meta").textContent = "runId=" + body.runId + ", tenantId=" + body.tenantId + ", state=" + ((body.artifact && body.artifact.workflowState) || "n/a");
      el("plan").textContent = asJson(body.plan || {});
      el("risks").textContent = asJson(body.risks || []);
      renderOptions(body.options || []);
      setOut(body);
    }

    function renderOptions(options) {
      const root = el("options");
      root.innerHTML = "";
      options.forEach((opt, idx) => {
        const b = document.createElement("button");
        b.className = "secondary";
        b.textContent = (idx + 1) + ". " + opt;
        b.onclick = () => { el("choice").value = opt; };
        root.appendChild(b);
      });
    }

    async function postJson(path, payload) {
      const resp = await fetch(path, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const body = await resp.json().catch(() => ({}));
      setOut(body);
      await loadContext();
    }

    el("loadBtn").onclick = loadContext;
    el("answerBtn").onclick = async () => {
      if (!runId()) { setOut("runId is required"); return; }
      const choice = el("choice").value.trim();
      if (!choice) { setOut("choice is required"); return; }
      await postJson('/factory/runs/' + encodeURIComponent(runId()) + '/answer', {
        tenantId: tenantId(),
        choice: choice,
        reason: el("reason").value.trim() || undefined,
      });
    };
    el("approveBtn").onclick = async () => {
      if (!runId()) { setOut("runId is required"); return; }
      await postJson('/factory/approvals/' + encodeURIComponent(runId()) + '/approve', { tenantId: tenantId() });
    };
    el("rejectBtn").onclick = async () => {
      if (!runId()) { setOut("runId is required"); return; }
      await postJson('/factory/approvals/' + encodeURIComponent(runId()) + '/reject', { tenantId: tenantId() });
    };
    el("sprintPointBtn").onclick = async () => {
      if (!runId()) { setOut("runId is required"); return; }
      await postJson('/factory/runs/' + encodeURIComponent(runId()) + '/sprint-point', {
        tenantId: tenantId(),
        stage: el("stage").value.trim(),
        decision: el("decision").value,
        note: el("note").value.trim() || undefined,
      });
    };
  </script>
</body>
</html>
""".trimIndent()

private fun buildReferenceOptions(query: String): List<ApiReferenceOption> {
    val normalizedQuery = query.trim().ifBlank { "intent" }
    return (1..6).map { index ->
        ApiReferenceOption(
            id = "ref-$index",
            title = "Reference card $index",
            summary = "Style option $index for \"$normalizedQuery\"",
        )
    }
}

private fun resolveApiSession(
    session: ApiSession?,
    referenceOptions: List<ApiReferenceOption>,
    selectedReferenceIds: List<String>,
): ApiSession {
    val existingSessionId = session?.sessionId?.trim().takeUnless { it.isNullOrBlank() }
    val createdAt = session?.createdAt?.trim().takeUnless { it.isNullOrBlank() } ?: Instant.now().toString()
    val selectedIds = selectedReferenceIds.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }.distinct()
    val options = if (referenceOptions.isNotEmpty()) {
        referenceOptions
    } else {
        session?.references?.options.orEmpty()
    }
    return ApiSession(
        sessionId = existingSessionId ?: "sess-${UUID.randomUUID()}",
        createdAt = createdAt,
        intent = session?.intent,
        preferences = session?.preferences,
        consentFlags = session?.consentFlags,
        references = ApiSessionReferences(
            options = options,
            selectedIds = selectedIds,
        ),
    )
}

private data class RankedCandidate(
    val candidate: String,
    val score: Double,
    val profileAdjustment: Double,
)

private fun ensureCandidateCount(candidates: List<String>, requestedVariants: Int): List<String> {
    if (candidates.size >= requestedVariants) return candidates
    val result = candidates.toMutableList()
    var index = result.size + 1
    while (result.size < requestedVariants) {
        val base = candidates[(index - 1) % candidates.size]
        val normalized = base.substringAfter(':', base).trim()
        result += "Candidate $index: exploratory variant $index for \"$normalized\" with distinct trade-offs."
        index += 1
    }
    return result
}

private fun rankCandidates(
    candidates: List<String>,
    requestedVariants: Int,
    profile: productfactory.profile.PreferenceProfile?,
    allowProfileRanking: Boolean,
): List<RankedCandidate> {
    val profileRules = profile?.rules.orEmpty()
    val canUseProfile = allowProfileRanking && profile != null && !profile.incognito && profileRules.isNotEmpty()
    return candidates.mapIndexed { index, candidate ->
        val baseScore = (0.79 - (index * 0.05)).coerceAtLeast(0.5)
        val profileAdjustment = if (canUseProfile) profileScoreAdjustment(candidate, profileRules) else 0.0
        RankedCandidate(
            candidate = candidate,
            score = (baseScore + profileAdjustment).coerceIn(0.05, 0.99),
            profileAdjustment = profileAdjustment,
        )
    }
        .sortedByDescending { it.score }
        .take(requestedVariants)
}

private fun profileScoreAdjustment(candidate: String, rules: List<PreferenceRule>): Double {
    val candidateTokens = tokenizeForRanking(candidate)
    var adjustment = 0.0
    rules.forEach { rule ->
        val tokens = tokenizeForRanking(rule.value)
        val matchStrength = when {
            tokens.isEmpty() -> 0.0
            else -> tokens.count { candidateTokens.contains(it) }.toDouble() / tokens.size.toDouble()
        }
        if (matchStrength <= 0.0) {
            if (rule.type == PreferenceRuleType.REQUIRE) {
                adjustment -= 0.06 * ((rule.weight ?: 1.0).coerceIn(0.1, 2.0))
            }
            return@forEach
        }
        val weight = (rule.weight ?: 1.0).coerceIn(0.1, 2.0)
        val delta = 0.12 * weight * matchStrength
        adjustment += when (rule.type) {
            PreferenceRuleType.LIKE, PreferenceRuleType.REQUIRE -> delta
            PreferenceRuleType.DISLIKE, PreferenceRuleType.AVOID -> -delta
        }
    }
    return adjustment.coerceIn(-0.25, 0.25)
}

private fun tokenizeForRanking(text: String): Set<String> = text.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .split(" ")
    .filter { it.length >= 3 }
    .toSet()

private fun buildVariantRationale(usedProfile: Boolean, incognito: Boolean): String {
    if (incognito) return "Generated in incognito mode without profile-based ranking."
    if (usedProfile) return "Generated from intent and re-ranked by profile preferences."
    return "Generated from provided outcome, experience and constraints."
}

private fun buildCostBudgetRejectionPayload(violation: CostBudgetViolation) = buildJsonObject {
    put("statusCode", violation.statusCode)
    put("code", violation.code)
    put("message", violation.message)
    violation.details["period"]?.let { put("period", it) }
    violation.details["metric"]?.let { put("metric", it) }
    violation.details["used"]?.let { put("used", it) }
    violation.details["limit"]?.let { put("limit", it) }
    violation.details["threshold"]?.let { put("threshold", it) }
}

private suspend inline fun <reified T : Any> io.ktor.server.application.ApplicationCall.receiveOrValidationError(): T? = try {
    receive<T>()
} catch (_: BadRequestException) {
    respondApiError(
        status = HttpStatusCode.BadRequest,
        code = "VALIDATION_ERROR",
        message = "Invalid JSON payload",
    )
    null
} catch (_: SerializationException) {
    respondApiError(
        status = HttpStatusCode.BadRequest,
        code = "VALIDATION_ERROR",
        message = "Invalid JSON payload",
    )
    null
} catch (_: Exception) {
    respondApiError(
        status = HttpStatusCode.BadRequest,
        code = "VALIDATION_ERROR",
        message = "Invalid JSON payload",
    )
    null
}

private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: Map<String, String> = emptyMap(),
) {
    respond(
        status,
        ApiErrorResponse(
            apiVersion = API_VERSION_V1,
            error = ApiErrorPayload(
                code = code,
                message = message,
                details = details,
            ),
        ),
    )
}
