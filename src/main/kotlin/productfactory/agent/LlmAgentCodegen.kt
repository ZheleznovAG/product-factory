package productfactory.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import productfactory.workflow.AuditLog
import java.time.Duration

private const val CODEGEN_RUN_ID = "codegen"

/**
 * Codegen через нейросервис (Codex/LLM). При ошибке или невалидном JSON — fallback на [fallbackCodegen].
 */
class LlmAgentCodegen(
    private val neuralClient: NeuralServiceClient,
    private val auditLog: AuditLog? = null,
    private val fallbackCodegen: AgentCodegen = StubAgentCodegen(),
) : AgentCodegen {

    private val json = Json { ignoreUnknownKeys = true }

    override fun generate(input: AgentCodegenInput): AgentCodegenResult {
        val callStartedAtNanos = System.nanoTime()
        val messages = listOf(
            ChatMessage(role = "system", content = buildSystemPrompt()),
            ChatMessage(role = "user", content = buildUserPrompt(input)),
        )

        val timeoutSeconds = System.getenv("CODEGEN_LLM_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L
        val result = try {
            neuralClient.chatCompletion(
                messages = messages,
                temperature = 0.25,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        } catch (e: Exception) {
            return fallback("codegen_llm_exception", callStartedAtNanos, input)
        }

        val responseText = result.content?.trim()
        if (responseText.isNullOrEmpty()) {
            return fallback("codegen_llm_empty", callStartedAtNanos, input)
        }

        val parsed = try {
            parseCodegenResponse(responseText)
        } catch (e: Exception) {
            return fallback("codegen_llm_invalid_json", callStartedAtNanos, input)
        }

        if (parsed.proposals.isEmpty()) {
            return fallback("codegen_llm_no_proposals", callStartedAtNanos, input)
        }

        logCodegenCall(elapsedMillis(callStartedAtNanos), success = true, fallbackUsed = false, null)
        val tokenUsage = result.totalTokens.toInt().coerceAtLeast(0)
        return AgentCodegenResult(artifact = parsed, tokenUsage = tokenUsage)
    }

    private fun parseCodegenResponse(raw: String): CodegenPatchSetArtifact {
        val stripped = stripMarkdownJsonFence(raw.trim())
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object in codegen response" }
        val tree = json.parseToJsonElement(stripped.substring(start, end + 1)).jsonObject
        val proposalsArray = tree["proposals"]?.jsonArray ?: return CodegenPatchSetArtifact(proposals = emptyList())
        val proposals = mutableListOf<CodegenProposal>()
        for (i in 0 until proposalsArray.size) {
            val obj = proposalsArray[i].jsonObject
            val filePath = obj["filePath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: continue
            val summary = obj["summary"]?.jsonPrimitive?.content?.trim() ?: ""
            val patch = obj["patch"]?.jsonPrimitive?.content
            val structuredObj = obj["structuredPatch"]?.jsonObject
            val structuredPatch = if (structuredObj != null) {
                val path = structuredObj["path"]?.jsonPrimitive?.content ?: filePath
                val oldContent = structuredObj["old_content"]?.jsonPrimitive?.content
                val newContent = structuredObj["new_content"]?.jsonPrimitive?.content ?: ""
                StructuredCodePatch(path = path, old_content = oldContent, new_content = newContent)
            } else null
            val hasPatch = !patch.isNullOrBlank()
            val hasStructured = structuredPatch != null && structuredPatch.new_content.isNotBlank()
            val effectivePatch = if (hasPatch) patch else null
            val effectiveStructured = when {
                hasStructured -> structuredPatch
                hasPatch -> null
                else -> StructuredCodePatch(path = filePath, old_content = null, new_content = "// generated")
            }
            proposals.add(
                CodegenProposal(
                    filePath = filePath,
                    summary = summary,
                    patch = effectivePatch,
                    structuredPatch = effectiveStructured,
                ),
            )
        }
        return CodegenPatchSetArtifact(proposals = proposals)
    }

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

    private fun fallback(reason: String, callStartedAtNanos: Long, input: AgentCodegenInput): AgentCodegenResult {
        logCodegenCall(elapsedMillis(callStartedAtNanos), success = false, fallbackUsed = true, reason)
        return AgentCodegenResult(artifact = fallbackCodegen.generate(input).artifact, tokenUsage = 0)
    }

    private fun logCodegenCall(latencyMs: Long, success: Boolean, fallbackUsed: Boolean, reason: String?) {
        auditLog?.log(
            runId = CODEGEN_RUN_ID,
            eventType = "agent_codegen_call",
            payload = buildJsonObject {
                put("latency_ms", latencyMs)
                put("success", success)
                put("fallback_used", fallbackUsed)
                reason?.let { put("reason", it) }
            }.toString(),
        )
    }

    private fun buildSystemPrompt(): String {
        val collaborationRoles = listOf(
            AgentPromptRole.IMPLEMENTER,
            AgentPromptRole.TESTER,
            AgentPromptRole.REVIEWER,
        )
        val collaborationRules = collaborationRoles.joinToString(separator = "\n") { role ->
            val profile = AgentRolePromptRegistry.profileFor(role)
            "- ${profile.title}: ${profile.rules.joinToString("; ")}"
        }
        return AgentRolePromptRegistry.renderSystem(
            role = AgentPromptRole.CODEGEN,
            variables = mapOf("collaboration_role_rules" to collaborationRules),
        )
    }

    private fun buildUserPrompt(input: AgentCodegenInput): String {
        val steps = input.plannerArtifacts.pipelinePlan.steps.joinToString(", ") { "${it.id} (${it.tool})" }
        val constraints = input.constraints.joinToString(", ").ifBlank { "none" }
        return AgentRolePromptRegistry.renderUser(
            role = AgentPromptRole.CODEGEN,
            variables = mapOf(
                "goal" to input.goal,
                "constraints" to constraints,
                "planner_steps" to steps,
            ),
        )
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
