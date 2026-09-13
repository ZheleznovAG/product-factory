package productfactory.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import productfactory.api.FactoryRunResponse
import productfactory.workflow.ArtifactRunRecord
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

object FactoryConsumerCli {
    private const val defaultFactoryUrl = "http://localhost:9080"
    private val json = Json { ignoreUnknownKeys = true }

    private val runUsage = """
Usage:
  product-factory run --goal "<text>" [--constraint "<text>"] [--target-stack <id>] [--url <http://localhost:9080>] [--tenant <id>]
""".trimIndent()

    private val statusUsage = """
Usage:
  product-factory status <runId> [--url <http://localhost:9080>] [--tenant <id>]
""".trimIndent()

    private val intentUsage = """
Usage:
  product-factory intent --query "<text>" [--risk-tier low|medium|high] [--variants 3..7] [--pick <n>] [--run] [--constraint "<text>"] [--target-stack <id>] [--url <http://localhost:9080>] [--tenant <id>]
""".trimIndent()

    fun runCommand(args: Array<String>): Int {
        val parsed = parseRunArgs(args) ?: return 2
        val goal = parsed.goal.ifBlank { prompt("Goal: ") ?: "" }
        if (goal.isBlank()) {
            System.err.println(runUsage)
            return 2
        }
        val client = FactoryApiClient(baseUrl = parsed.baseUrl, tenantId = parsed.tenantId)
        val payload = buildJsonObject {
            put("goal", goal)
            put("constraints", buildJsonArray {
                parsed.constraints.forEach { add(JsonPrimitive(it)) }
            })
            parsed.tenantId?.let { put("tenantId", it) }
            parsed.targetStack?.let { put("target_stack", it) }
        }
        val response = client.post("/factory/run", payload.toString()) ?: return 1
        return when {
            response.statusCode() in 200..299 -> {
                val result = decodeOrNull<FactoryRunResponse>(response.body())
                if (result == null) {
                    System.err.println("Failed to parse run response")
                    1
                } else {
                    println("runId=${result.runId}")
                    println("tenantId=${result.tenantId}")
                    println("status=${result.status}")
                    println("message=${result.message}")
                    println("next: product-factory status ${result.runId} --url ${parsed.baseUrl}")
                    0
                }
            }
            else -> {
                System.err.println(formatApiError(response))
                1
            }
        }
    }

    fun statusCommand(args: Array<String>): Int {
        val parsed = parseStatusArgs(args) ?: return 2
        val encodedRunId = urlEncode(parsed.runId)
        val response = FactoryApiClient(baseUrl = parsed.baseUrl, tenantId = parsed.tenantId).get("/factory/runs/$encodedRunId") ?: return 1
        return when {
            response.statusCode() in 200..299 -> {
                val record = decodeOrNull<ArtifactRunRecord>(response.body())
                if (record == null) {
                    System.err.println("Failed to parse run status response")
                    1
                } else {
                    println("runId=${record.runId}")
                    println("workflowState=${record.workflowState}")
                    println("updatedAt=${record.updatedAt}")
                    println("repositoryVersion=${record.repositoryVersion ?: "-"}")
                    println("repoUrl=${record.repoUrl ?: "-"}")
                    println("artifactLocation=${record.artifactLocation ?: "-"}")
                    0
                }
            }
            else -> {
                System.err.println(formatApiError(response))
                1
            }
        }
    }

    fun intentCommand(args: Array<String>): Int {
        val parsed = parseIntentArgs(args) ?: return 2
        val query = parsed.query.ifBlank { prompt("Intent query: ") ?: "" }
        if (query.isBlank()) {
            System.err.println(intentUsage)
            return 2
        }

        val client = FactoryApiClient(baseUrl = parsed.baseUrl, tenantId = parsed.tenantId)
        val estimatePayload = buildJsonObject {
            put("apiVersion", "productfactory.io/v1")
            put("requestId", UUID.randomUUID().toString())
            parsed.tenantId?.let { put("tenantId", it) }
            put("input", buildJsonObject {
                put("query", query)
                put("language", "ru")
            })
            put("constraints", buildJsonObject {
                parsed.riskTier?.let { put("riskTier", it) }
                put("maxClarifyingQuestions", 1)
            })
        }
        val estimateResponse = client.post("/intent/estimate", estimatePayload.toString()) ?: return 1
        if (estimateResponse.statusCode() !in 200..299) {
            System.err.println(formatApiError(estimateResponse))
            return 1
        }
        val estimate = decodeOrNull<IntentEstimateResponse>(estimateResponse.body())
        if (estimate == null) {
            System.err.println("Failed to parse intent estimate response")
            return 1
        }

        println("Intent:")
        println("  outcome: ${estimate.intent.outcome}")
        println("  experience: ${estimate.intent.experience}")
        println("  constraints: ${estimate.intent.constraints.joinToString(", ").ifBlank { "-" }}")
        if (estimate.clarifyingQuestions.isNotEmpty()) {
            println("  clarifyingQuestion: ${estimate.clarifyingQuestions.first()}")
        }

        val variantsPayload = buildJsonObject {
            put("apiVersion", "productfactory.io/v1")
            put("requestId", UUID.randomUUID().toString())
            parsed.tenantId?.let { put("tenantId", it) }
            put("intent", buildJsonObject {
                put("outcome", estimate.intent.outcome)
                put("experience", estimate.intent.experience)
                put("constraints", buildJsonArray {
                    estimate.intent.constraints.forEach { add(JsonPrimitive(it)) }
                })
            })
            put("generation", buildJsonObject {
                put("variants", parsed.variants)
                put("includeRationale", false)
            })
        }
        val variantsResponse = client.post("/experience/generate", variantsPayload.toString()) ?: return 1
        if (variantsResponse.statusCode() !in 200..299) {
            System.err.println(formatApiError(variantsResponse))
            return 1
        }
        val variants = decodeOrNull<ExperienceGenerateResponse>(variantsResponse.body())
        if (variants == null || variants.variants.isEmpty()) {
            System.err.println("Failed to parse experience variants response")
            return 1
        }

        println("Candidates:")
        variants.variants.forEachIndexed { index, variant ->
            val number = index + 1
            println("  $number. ${variant.title}: ${variant.summary}")
        }

        val selectedIndex = resolvePickIndex(parsed.pick, variants.variants.size)
            ?: promptPick(variants.variants.size)
            ?: return 0
        val selected = variants.variants[selectedIndex]
        println("Selected: ${selected.title}")

        val shouldRun = parsed.runNow || promptYesNo("Start run now with selected intent? [y/N]: ")
        if (!shouldRun) {
            return 0
        }
        val runGoal = "Implement ${selected.title}: ${selected.summary}"
        val runConstraints = (estimate.intent.constraints + parsed.additionalConstraints).distinct()
        val runPayload = buildJsonObject {
            put("goal", runGoal)
            put("constraints", buildJsonArray { runConstraints.forEach { add(JsonPrimitive(it)) } })
            parsed.targetStack?.let { put("target_stack", it) }
            parsed.tenantId?.let { put("tenantId", it) }
        }
        val runResponse = client.post("/factory/run", runPayload.toString()) ?: return 1
        if (runResponse.statusCode() !in 200..299) {
            System.err.println(formatApiError(runResponse))
            return 1
        }
        val run = decodeOrNull<FactoryRunResponse>(runResponse.body())
        if (run == null) {
            System.err.println("Failed to parse run response")
            return 1
        }
        println("runId=${run.runId}")
        println("status=${run.status}")
        println("message=${run.message}")
        println("next: product-factory status ${run.runId} --url ${parsed.baseUrl}")
        return 0
    }

    private fun parseRunArgs(args: Array<String>): RunArgs? {
        var goal: String? = null
        var targetStack: String? = null
        var baseUrl = defaultFactoryUrl
        var tenantId: String? = null
        val constraints = mutableListOf<String>()

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--goal" -> goal = args.valueAt(++i, runUsage) ?: return null
                "--constraint" -> constraints += args.valueAt(++i, runUsage) ?: return null
                "--target-stack" -> targetStack = args.valueAt(++i, runUsage) ?: return null
                "--url" -> baseUrl = args.valueAt(++i, runUsage) ?: return null
                "--tenant" -> tenantId = args.valueAt(++i, runUsage) ?: return null
                "--help", "-h" -> {
                    System.err.println(runUsage)
                    return null
                }
                else -> {
                    System.err.println("Unknown argument: ${args[i]}")
                    System.err.println(runUsage)
                    return null
                }
            }
            i++
        }
        return RunArgs(
            goal = goal?.trim().orEmpty(),
            constraints = constraints.filter { it.isNotBlank() },
            targetStack = targetStack?.trim()?.takeIf { it.isNotBlank() },
            baseUrl = normalizeBaseUrl(baseUrl),
            tenantId = tenantId?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseStatusArgs(args: Array<String>): StatusArgs? {
        if (args.size < 2) {
            System.err.println(statusUsage)
            return null
        }
        var runId: String? = null
        var baseUrl = defaultFactoryUrl
        var tenantId: String? = null
        var i = 1
        while (i < args.size) {
            val arg = args[i]
            when {
                !arg.startsWith("-") && runId == null -> runId = arg
                arg == "--url" -> baseUrl = args.valueAt(++i, statusUsage) ?: return null
                arg == "--tenant" -> tenantId = args.valueAt(++i, statusUsage) ?: return null
                arg == "--help" || arg == "-h" -> {
                    System.err.println(statusUsage)
                    return null
                }
                else -> {
                    System.err.println("Unknown argument: $arg")
                    System.err.println(statusUsage)
                    return null
                }
            }
            i++
        }
        if (runId.isNullOrBlank()) {
            System.err.println(statusUsage)
            return null
        }
        return StatusArgs(
            runId = runId,
            baseUrl = normalizeBaseUrl(baseUrl),
            tenantId = tenantId?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseIntentArgs(args: Array<String>): IntentArgs? {
        var query: String? = null
        var riskTier: String? = null
        var variants = 3
        var pick: Int? = null
        var runNow = false
        var targetStack: String? = null
        var baseUrl = defaultFactoryUrl
        var tenantId: String? = null
        val additionalConstraints = mutableListOf<String>()

        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--query" -> query = args.valueAt(++i, intentUsage) ?: return null
                "--risk-tier" -> riskTier = args.valueAt(++i, intentUsage) ?: return null
                "--variants" -> {
                    val raw = args.valueAt(++i, intentUsage) ?: return null
                    variants = raw.toIntOrNull() ?: run {
                        System.err.println("--variants must be an integer")
                        return null
                    }
                }
                "--pick" -> {
                    val raw = args.valueAt(++i, intentUsage) ?: return null
                    pick = raw.toIntOrNull() ?: run {
                        System.err.println("--pick must be an integer")
                        return null
                    }
                }
                "--run" -> runNow = true
                "--constraint" -> additionalConstraints += args.valueAt(++i, intentUsage) ?: return null
                "--target-stack" -> targetStack = args.valueAt(++i, intentUsage) ?: return null
                "--url" -> baseUrl = args.valueAt(++i, intentUsage) ?: return null
                "--tenant" -> tenantId = args.valueAt(++i, intentUsage) ?: return null
                "--help", "-h" -> {
                    System.err.println(intentUsage)
                    return null
                }
                else -> {
                    System.err.println("Unknown argument: ${args[i]}")
                    System.err.println(intentUsage)
                    return null
                }
            }
            i++
        }
        if (variants !in 3..7) {
            System.err.println("--variants must be in range 3..7")
            return null
        }
        riskTier?.let {
            val normalized = it.lowercase()
            if (normalized !in setOf("low", "medium", "high")) {
                System.err.println("--risk-tier must be one of low|medium|high")
                return null
            }
            riskTier = normalized
        }
        return IntentArgs(
            query = query?.trim().orEmpty(),
            riskTier = riskTier,
            variants = variants,
            pick = pick,
            runNow = runNow,
            additionalConstraints = additionalConstraints.filter { it.isNotBlank() },
            targetStack = targetStack?.trim()?.takeIf { it.isNotBlank() },
            baseUrl = normalizeBaseUrl(baseUrl),
            tenantId = tenantId?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun decodeApiErrorOrNull(body: String): ApiErrorResponse? {
        return runCatching { json.decodeFromString<ApiErrorResponse>(body) }.getOrNull()
    }

    private inline fun <reified T> decodeOrNull(body: String): T? {
        return runCatching { json.decodeFromString<T>(body) }.getOrNull()
    }

    private fun formatApiError(response: HttpResponse<String>): String {
        val err = decodeApiErrorOrNull(response.body())
        return if (err?.error?.message != null) {
            "HTTP ${response.statusCode()}: ${err.error.message}"
        } else {
            "HTTP ${response.statusCode()}: ${response.body()}"
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/').ifBlank { defaultFactoryUrl }

    private fun Array<String>.valueAt(index: Int, usage: String): String? {
        val value = getOrNull(index)
        if (value == null) {
            System.err.println(usage)
        }
        return value
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun prompt(text: String): String? {
        val console = System.console() ?: return null
        return console.readLine(text)?.trim()
    }

    private fun promptYesNo(text: String): Boolean {
        val console = System.console() ?: return false
        val answer = console.readLine(text)?.trim()?.lowercase()
        return answer == "y" || answer == "yes" || answer == "1"
    }

    private fun resolvePickIndex(pick: Int?, count: Int): Int? {
        if (pick == null) return null
        val index = pick - 1
        if (index !in 0 until count) {
            System.err.println("--pick must be in range 1..$count")
            return null
        }
        return index
    }

    private fun promptPick(count: Int): Int? {
        val console = System.console() ?: return null
        val answer = console.readLine("Pick candidate 1..$count (enter to skip): ")?.trim().orEmpty()
        if (answer.isBlank()) return null
        val pick = answer.toIntOrNull()
        if (pick == null || pick !in 1..count) {
            System.err.println("Invalid choice: $answer")
            return null
        }
        return pick - 1
    }

    private data class RunArgs(
        val goal: String,
        val constraints: List<String>,
        val targetStack: String?,
        val baseUrl: String,
        val tenantId: String?,
    )

    private data class StatusArgs(
        val runId: String,
        val baseUrl: String,
        val tenantId: String?,
    )

    private data class IntentArgs(
        val query: String,
        val riskTier: String?,
        val variants: Int,
        val pick: Int?,
        val runNow: Boolean,
        val additionalConstraints: List<String>,
        val targetStack: String?,
        val baseUrl: String,
        val tenantId: String?,
    )
}

private class FactoryApiClient(
    private val baseUrl: String,
    private val tenantId: String?,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun get(path: String): HttpResponse<String>? {
        val request = requestBuilder(path)
            .GET()
            .build()
        return execute(request)
    }

    fun post(path: String, body: String): HttpResponse<String>? {
        val request = requestBuilder(path)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build()
        return execute(request)
    }

    private fun requestBuilder(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
        tenantId?.let { builder.header("X-Tenant-Id", it) }
        return builder
    }

    private fun execute(request: HttpRequest): HttpResponse<String>? {
        return runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onFailure { System.err.println("HTTP call failed: ${it.message}") }
            .getOrNull()
    }
}

@Serializable
private data class IntentEstimateResponse(
    val intent: IntentEstimatePayload,
    val clarifyingQuestions: List<String> = emptyList(),
)

@Serializable
private data class IntentEstimatePayload(
    val outcome: String,
    val experience: String,
    val constraints: List<String> = emptyList(),
)

@Serializable
private data class ExperienceGenerateResponse(
    val variants: List<ExperienceVariant> = emptyList(),
)

@Serializable
private data class ExperienceVariant(
    val id: String,
    val title: String,
    val summary: String,
)

@Serializable
private data class ApiErrorResponse(
    val error: ApiErrorPayload? = null,
)

@Serializable
private data class ApiErrorPayload(
    val message: String? = null,
)
