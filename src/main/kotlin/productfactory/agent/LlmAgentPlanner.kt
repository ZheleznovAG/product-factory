package productfactory.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import productfactory.rag.PlannerContextProvider
import productfactory.workflow.AuditLog
import java.time.Duration

private const val PLANNER_RUN_ID = "planner"

@Serializable
private data class LlmPlannerResponse(
    val pipeline_plan: PipelinePlanArtifact,
    val adr_draft: AdrDraftArtifact,
    val test_plan: TestPlanArtifact,
)

class LlmAgentPlanner(
    private val neuralClient: NeuralServiceClient,
    private val auditLog: AuditLog? = null,
    private val fallbackPlanner: AgentPlanner = StubAgentPlanner(),
    private val contextProvider: PlannerContextProvider? = null,
    private val allowedTools: Set<String> = setOf(
        "create_repo_from_archetype",
        "contract_validator",
        "quality_gate_runner",
    ),
) : AgentPlanner {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(input: AgentPlannerInput): AgentPlannerResult {
        val callStartedAtNanos = System.nanoTime()
        val ragContext = try {
            contextProvider?.contextFor(input)
        } catch (_: Exception) {
            null
        }
        val messages = listOf(
            ChatMessage(
                role = "system",
                content = buildSystemPrompt(allowedTools),
            ),
            ChatMessage(
                role = "user",
                content = buildUserPrompt(input, ragContext),
            ),
        )

        val timeoutSeconds = System.getenv("PLANNER_LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L
        val result = try {
            neuralClient.chatCompletion(
                messages = messages,
                temperature = 0.25,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        } catch (e: Exception) {
            return fallback(
                reason = "planner_llm_exception",
                callStartedAtNanos = callStartedAtNanos,
                input = input,
            )
        }

        val responseText = result.content?.trim()
        if (responseText.isNullOrEmpty()) {
            return fallback(
                reason = "planner_llm_empty",
                callStartedAtNanos = callStartedAtNanos,
                input = input,
            )
        }

        val parsed = try {
            parsePlannerArtifacts(responseText)
        } catch (e: Exception) {
            return fallback(
                reason = "planner_llm_invalid_json",
                callStartedAtNanos = callStartedAtNanos,
                input = input,
            )
        }

        if (!hasExpectedArtifactTypes(parsed)) {
            return fallback(
                reason = "planner_llm_invalid_types",
                callStartedAtNanos = callStartedAtNanos,
                input = input,
            )
        }

        if (parsed.pipelinePlan.steps.any { it.tool !in allowedTools }) {
            return fallback(
                reason = "planner_llm_invalid_tool",
                callStartedAtNanos = callStartedAtNanos,
                input = input,
            )
        }

        logPlannerCall(
            latencyMs = elapsedMillis(callStartedAtNanos),
            success = true,
            fallbackUsed = false,
            reason = null,
            rationale = plannerRationaleFrom(parsed),
        )
        val tokenUsage = result.totalTokens.toInt().coerceAtLeast(0)
        return AgentPlannerResult(
            artifacts = parsed,
            tokenUsage = tokenUsage,
            rationale = plannerRationaleFrom(parsed),
        )
    }

    private fun parsePlannerArtifacts(raw: String): AgentPlannerArtifacts {
        val extracted = extractJsonObject(raw)
        val parsed = json.decodeFromString<LlmPlannerResponse>(extracted)
        return AgentPlannerArtifacts(
            pipelinePlan = parsed.pipeline_plan,
            adrDraft = parsed.adr_draft,
            testPlan = parsed.test_plan,
        )
    }

    private fun hasExpectedArtifactTypes(artifacts: AgentPlannerArtifacts): Boolean {
        return artifacts.pipelinePlan.type == "pipeline_plan" &&
            artifacts.pipelinePlan.steps.isNotEmpty() &&
            artifacts.adrDraft.type == "adr_draft" &&
            artifacts.adrDraft.consequences.isNotEmpty() &&
            artifacts.testPlan.type == "test_plan" &&
            artifacts.testPlan.cases.isNotEmpty() &&
            artifacts.testPlan.coverage_targets.unit_percent in 0..100
    }

    private fun extractJsonObject(raw: String): String {
        val stripped = stripMarkdownJsonFence(raw.trim())
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found in LLM response" }
        val candidate = stripped.substring(start, end + 1)
        val element = json.parseToJsonElement(candidate)
        require(element is JsonObject) { "Expected top-level JSON object" }
        return candidate
    }

    /** Убирает обёртку ```json ... ``` или ``` ... ```, если есть. */
    private fun stripMarkdownJsonFence(s: String): String {
        var t = s
        for (fence in listOf("```json", "```")) {
            val idx = t.indexOf(fence)
            if (idx >= 0) {
                val afterFence = t.substring(idx + fence.length).trimStart()
                val endIdx = afterFence.indexOf("```")
                t = if (endIdx >= 0) afterFence.substring(0, endIdx).trim() else afterFence.trim()
                break
            }
        }
        return t
    }

    private fun fallback(
        reason: String,
        callStartedAtNanos: Long,
        input: AgentPlannerInput,
    ): AgentPlannerResult {
        val fallbackResult = fallbackPlanner.generate(input)
        val fallbackRationale = "LLM planner fallback was used due to '$reason'. ${fallbackResult.rationale}"
        logPlannerCall(
            latencyMs = elapsedMillis(callStartedAtNanos),
            success = false,
            fallbackUsed = true,
            reason = reason,
            rationale = fallbackRationale,
        )
        return AgentPlannerResult(artifacts = fallbackResult.artifacts, tokenUsage = 0, rationale = fallbackRationale)
    }

    private fun logPlannerCall(
        latencyMs: Long,
        success: Boolean,
        fallbackUsed: Boolean,
        reason: String?,
        rationale: String?,
    ) {
        auditLog?.log(
            runId = PLANNER_RUN_ID,
            eventType = "agent_planner_call",
            payload = buildJsonObject {
                put("latency_ms", latencyMs)
                put("success", success)
                put("fallback_used", fallbackUsed)
                reason?.let { put("reason", it) }
                rationale?.let { put("rationale", it) }
            }.toString(),
        )
    }

    private fun plannerRationaleFrom(artifacts: AgentPlannerArtifacts): String {
        val decision = artifacts.adrDraft.decision.trim()
        return if (decision.isNotEmpty()) {
            decision
        } else {
            "Planner returned structured pipeline/test artifacts."
        }
    }

    private fun buildSystemPrompt(allowedTools: Set<String>): String {
        val downstreamRoles = listOf(
            AgentPromptRole.IMPLEMENTER,
            AgentPromptRole.TESTER,
            AgentPromptRole.REVIEWER,
        )
        val downstreamRules = downstreamRoles.joinToString(separator = "\n") { role ->
            val title = AgentRolePromptRegistry.profileFor(role).title
            val rules = AgentRolePromptRegistry.profileFor(role).rules.joinToString("; ")
            "- $title: $rules"
        }
        return AgentRolePromptRegistry.renderSystem(
            role = AgentPromptRole.PLANNER,
            variables = mapOf(
                "allowed_tools" to allowedTools.sorted().joinToString(", "),
                "downstream_role_rules" to downstreamRules,
            ),
        )
    }

    private fun buildUserPrompt(input: AgentPlannerInput, ragContext: String?): String {
        val constraints = if (input.constraints.isEmpty()) "[]" else input.constraints.joinToString(prefix = "[", postfix = "]")
        val contracts = if (input.contracts.isEmpty()) "[]" else input.contracts.keys.sorted().joinToString(prefix = "[", postfix = "]")
        val contextSection = ragContext?.takeIf { it.isNotBlank() }?.let {
            "Retrieved context (RAG, optional):\n$it"
        } ?: "Retrieved context (RAG, optional): <none>"
        return AgentRolePromptRegistry.renderUser(
            role = AgentPromptRole.PLANNER,
            variables = mapOf(
                "goal" to input.goal,
                "constraints" to constraints,
                "product_spec" to (input.productSpec ?: "<none>"),
                "contracts" to contracts,
                "rag_context_section" to contextSection,
            ),
        )
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
