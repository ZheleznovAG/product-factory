package productfactory.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.api.FactoryRunRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private fun parseFailMode(value: String?): PolicyFailMode {
    if (value?.trim()?.lowercase() == "closed") return PolicyFailMode.CLOSED
    return PolicyFailMode.OPEN
}

/** Один tool call для OPA (allowlist проверяет name; argument_keys — для политики запрета секретов в аргументах). */
@Serializable
data class PolicyToolCall(
    val name: String,
    @kotlinx.serialization.SerialName("argument_keys") val argumentKeys: List<String> = emptyList(),
)

data class PolicyInput(
    val goal: String,
    val toolCalls: List<PolicyToolCall> = emptyList(),
    val tokenUsage: Int = 0,
)

/** Режим при недоступности OPA: open = разрешить run (fallback allow), closed = запретить. */
enum class PolicyFailMode {
    OPEN,
    CLOSED,
}

@Serializable
private data class OpaInput(
    val goal: String,
    val tool_calls: List<PolicyToolCall> = emptyList(),
    val token_usage: Int = 0,
)

@Serializable
private data class OpaRequest(val input: OpaInput)

/**
 * Policy check: при заданном OPA_URL вызывает OPA (allowlist, бюджеты, risk tier);
 * иначе — в режиме OPEN fallback allow, в режиме CLOSED — deny.
 * Режим: POLICY_FAIL_MODE=closed|open (по умолчанию open).
 */
class PolicyCheck(
    private val opaBaseUrl: String? = System.getenv("OPA_URL"),
    private val failMode: PolicyFailMode = parseFailMode(System.getenv("POLICY_FAIL_MODE")),
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun check(runId: String, request: FactoryRunRequest): PolicyResult {
        return check(
            runId = runId,
            input = PolicyInput(
                goal = request.goal,
            ),
        )
    }

    fun check(@Suppress("UNUSED_PARAMETER") runId: String, input: PolicyInput): PolicyResult {
        val url = opaBaseUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url == null) {
            return fallbackResult("OPA_URL not set; fallback.")
        }
        return queryOpa(url, input)
    }

    private fun fallbackResult(reasonPrefix: String): PolicyResult {
        val allowed = failMode == PolicyFailMode.OPEN
        val reason = if (allowed) "$reasonPrefix allow." else "$reasonPrefix policy fail-closed; denied."
        return PolicyResult(
            allowed = allowed,
            requireHumanApproval = false,
            reason = reason,
            source = "fallback",
        )
    }

    private fun queryOpa(baseUrl: String, input: PolicyInput): PolicyResult {
        val opaRequest = OpaRequest(
            input = OpaInput(
                goal = input.goal,
                tool_calls = input.toolCalls,
                token_usage = input.tokenUsage,
            ),
        )
        val body = json.encodeToString(serializer<OpaRequest>(), opaRequest)
        val req = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/data/factory").normalize())
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            val response = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return fallbackResult("OPA returned ${response.statusCode()}; fallback")
            }
            parseOpaResult(response.body())
        } catch (e: Exception) {
            fallbackResult("OPA unavailable (${e.message}); fallback")
        }
    }

    private fun parseOpaResult(body: String): PolicyResult {
        val obj = json.parseToJsonElement(body).jsonObject
        val result = obj["result"]?.jsonObject ?: return fallbackResult("OPA result missing; fallback")
        val allow = result["allow"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val requireHumanApproval = result["require_human_approval"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val reason = when {
            !allow -> "denied by policy"
            requireHumanApproval -> "human approval required by policy"
            else -> "allowed"
        }
        return PolicyResult(
            allowed = allow,
            requireHumanApproval = requireHumanApproval,
            reason = reason,
            source = "opa",
            decision = result.toString(),
        )
    }
}

data class PolicyResult(
    val allowed: Boolean,
    val requireHumanApproval: Boolean,
    val reason: String,
    val source: String,
    val decision: String? = null,
)
