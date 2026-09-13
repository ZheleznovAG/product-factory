package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

@Serializable
data class ApprovalRecord(
    val runId: String,
    val timestamp: String,
    val reason: String,
    val proposedActions: List<String>,
    val status: ApprovalStatus,
    val decidedAt: String? = null,
    val decidedBy: String? = null,
    val comment: String? = null,
)

interface ApprovalStore {
    fun upsertPending(runId: String, reason: String, proposedActions: List<String>): ApprovalRecord
    fun get(runId: String): ApprovalRecord?
    fun approve(runId: String, decidedBy: String, comment: String? = null): ApprovalRecord?
    fun reject(runId: String, decidedBy: String, comment: String? = null): ApprovalRecord?

    fun isApproved(runId: String): Boolean = get(runId)?.status == ApprovalStatus.APPROVED
    fun isRejected(runId: String): Boolean = get(runId)?.status == ApprovalStatus.REJECTED
}

class InMemoryApprovalStore : ApprovalStore {
    private val records = ConcurrentHashMap<String, ApprovalRecord>()

    override fun upsertPending(runId: String, reason: String, proposedActions: List<String>): ApprovalRecord {
        val existing = records[runId]
        if (existing != null && existing.status != ApprovalStatus.PENDING) {
            return existing
        }
        val updated = ApprovalRecord(
            runId = runId,
            timestamp = Instant.now().toString(),
            reason = reason,
            proposedActions = proposedActions,
            status = ApprovalStatus.PENDING,
        )
        records[runId] = updated
        return updated
    }

    override fun get(runId: String): ApprovalRecord? = records[runId]

    override fun approve(runId: String, decidedBy: String, comment: String?): ApprovalRecord? {
        val existing = records[runId] ?: return null
        val updated = existing.copy(
            status = ApprovalStatus.APPROVED,
            decidedAt = Instant.now().toString(),
            decidedBy = decidedBy,
            comment = comment,
        )
        records[runId] = updated
        return updated
    }

    override fun reject(runId: String, decidedBy: String, comment: String?): ApprovalRecord? {
        val existing = records[runId] ?: return null
        val updated = existing.copy(
            status = ApprovalStatus.REJECTED,
            decidedAt = Instant.now().toString(),
            decidedBy = decidedBy,
            comment = comment,
        )
        records[runId] = updated
        return updated
    }
}

class FileApprovalStore(
    approvalsDirectory: Path = Path.of(System.getenv("APPROVALS_DIR") ?: "approvals"),
) : ApprovalStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root = approvalsDirectory.toAbsolutePath().normalize()
    private val lock = Any()

    init {
        root.createDirectories()
    }

    override fun upsertPending(runId: String, reason: String, proposedActions: List<String>): ApprovalRecord {
        synchronized(lock) {
            val existing = readRecord(runId)
            if (existing != null && existing.status != ApprovalStatus.PENDING) {
                return existing
            }
            val updated = ApprovalRecord(
                runId = runId,
                timestamp = Instant.now().toString(),
                reason = reason,
                proposedActions = proposedActions,
                status = ApprovalStatus.PENDING,
            )
            writeRecord(runId, updated)
            return updated
        }
    }

    override fun get(runId: String): ApprovalRecord? {
        synchronized(lock) {
            return readRecord(runId)
        }
    }

    override fun approve(runId: String, decidedBy: String, comment: String?): ApprovalRecord? {
        synchronized(lock) {
            val existing = readRecord(runId) ?: return null
            val updated = existing.copy(
                status = ApprovalStatus.APPROVED,
                decidedAt = Instant.now().toString(),
                decidedBy = decidedBy,
                comment = comment,
            )
            writeRecord(runId, updated)
            return updated
        }
    }

    override fun reject(runId: String, decidedBy: String, comment: String?): ApprovalRecord? {
        synchronized(lock) {
            val existing = readRecord(runId) ?: return null
            val updated = existing.copy(
                status = ApprovalStatus.REJECTED,
                decidedAt = Instant.now().toString(),
                decidedBy = decidedBy,
                comment = comment,
            )
            writeRecord(runId, updated)
            return updated
        }
    }

    private fun readRecord(runId: String): ApprovalRecord? {
        val file = pathFor(runId)
        if (!file.exists()) {
            return null
        }
        return json.decodeFromString(ApprovalRecord.serializer(), file.readText())
    }

    private fun writeRecord(runId: String, record: ApprovalRecord) {
        val serialized = json.encodeToString(record)
        pathFor(runId).writeText(serialized)
    }

    private fun pathFor(runId: String): Path {
        val normalizedRunId = runId.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        val path = root.resolve("$normalizedRunId.json").normalize()
        require(path.startsWith(root)) { "Approval file path escapes approvals directory" }
        Files.createDirectories(root)
        return path
    }
}
