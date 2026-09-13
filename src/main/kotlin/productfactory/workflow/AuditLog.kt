package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.time.Instant

internal fun digestPayload(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Audit event: all tool calls and key decisions must be logged.
 */
@Serializable
data class AuditEvent(
    val timestamp: String,
    val runId: String,
    val eventType: String,
    val payload: String,
)

interface AuditLog {
    fun log(runId: String, eventType: String, payload: String)

    /**
     * Optional read path for UI/debug endpoints. Implementations may return empty list when unsupported.
     */
    fun recentEvents(runId: String, limit: Int = 200): List<AuditEvent> = emptyList()
}

fun AuditLog.logStateChange(
    runId: String,
    stepId: String,
    newState: WorkflowState,
    role: WorkflowAgentRole? = null,
    roleAuditValue: String? = null,
    inputDigest: String? = null,
    outputDigest: String? = null,
) {
    val inDigest = inputDigest ?: digestPayload("$runId:$stepId")
    val outDigest = outputDigest ?: digestPayload("${newState.name}:$stepId")
    val payload = buildJsonObject {
        put("runId", runId)
        put("stepId", stepId)
        val resolvedRole = roleAuditValue ?: role?.auditValue
        if (resolvedRole != null) {
            put("role", resolvedRole)
        }
        put("newState", newState.name)
        put("inputDigest", inDigest)
        put("outputDigest", outDigest)
    }
    log(runId = runId, eventType = "state_changed", payload = payload.toString())
}

/**
 * File-based audit log (JSONL). Path can be configured via env AUDIT_LOG_PATH.
 */
class FileAuditLog(val path: String = System.getenv("AUDIT_LOG_PATH") ?: "audit.log") : AuditLog {
    private val file = File(path)

    override fun log(runId: String, eventType: String, payload: String) {
        val event = AuditEvent(
            timestamp = Instant.now().toString(),
            runId = runId,
            eventType = eventType,
            payload = payload,
        )
        file.appendText(Json.encodeToString(event) + "\n")
    }

    override fun recentEvents(runId: String, limit: Int): List<AuditEvent> {
        if (!file.isFile || limit <= 0) return emptyList()
        return file.readLines()
            .asReversed()
            .asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { Json.decodeFromString<AuditEvent>(line) }.getOrNull() }
            .filter { event -> event.runId == runId }
            .take(limit)
            .toList()
            .asReversed()
    }
}
