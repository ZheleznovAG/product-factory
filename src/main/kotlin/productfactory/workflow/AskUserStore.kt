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
enum class AskUserQuestionStatus {
    PENDING,
    ANSWERED,
}

@Serializable
data class AskUserQuestion(
    val runId: String,
    val stepId: String,
    val question: String,
    val options: List<String>,
    val status: AskUserQuestionStatus,
    val createdAt: String,
    val answer: String? = null,
    val answeredAt: String? = null,
)

interface AskUserStore {
    fun saveQuestion(runId: String, question: String, options: List<String>): AskUserQuestion =
        saveQuestion(runId = runId, stepId = WorkflowStepId.PLAN_WORKFLOW, question = question, options = options)

    fun saveQuestion(runId: String, stepId: String, question: String, options: List<String>): AskUserQuestion

    fun getPendingQuestion(runId: String): AskUserQuestion?

    fun submitAnswer(runId: String, choice: String): AskUserQuestion?
}

class InMemoryAskUserStore : AskUserStore {
    private val records = ConcurrentHashMap<String, AskUserQuestion>()

    override fun saveQuestion(runId: String, stepId: String, question: String, options: List<String>): AskUserQuestion {
        require(question.isNotBlank()) { "question must not be blank" }
        require(options.isNotEmpty()) { "options must not be empty" }
        require(options.all { it.isNotBlank() }) { "options must not contain blank values" }
        val record = AskUserQuestion(
            runId = runId,
            stepId = stepId,
            question = question.trim(),
            options = options.map { it.trim() },
            status = AskUserQuestionStatus.PENDING,
            createdAt = Instant.now().toString(),
        )
        records[runId] = record
        return record
    }

    override fun getPendingQuestion(runId: String): AskUserQuestion? =
        records[runId]?.takeIf { it.status == AskUserQuestionStatus.PENDING }

    override fun submitAnswer(runId: String, choice: String): AskUserQuestion? {
        val existing = records[runId] ?: return null
        if (existing.status != AskUserQuestionStatus.PENDING) {
            return existing
        }
        val normalizedChoice = choice.trim()
        require(existing.options.contains(normalizedChoice)) { "choice must be one of question options" }
        val updated = existing.copy(
            status = AskUserQuestionStatus.ANSWERED,
            answer = normalizedChoice,
            answeredAt = Instant.now().toString(),
        )
        records[runId] = updated
        return updated
    }
}

class FileAskUserStore(
    directory: Path = Path.of(System.getenv("ASK_USER_DIR") ?: "ask-user"),
) : AskUserStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root = directory.toAbsolutePath().normalize()
    private val lock = Any()

    init {
        root.createDirectories()
    }

    override fun saveQuestion(runId: String, stepId: String, question: String, options: List<String>): AskUserQuestion {
        require(question.isNotBlank()) { "question must not be blank" }
        require(options.isNotEmpty()) { "options must not be empty" }
        require(options.all { it.isNotBlank() }) { "options must not contain blank values" }
        synchronized(lock) {
            val updated = AskUserQuestion(
                runId = runId,
                stepId = stepId,
                question = question.trim(),
                options = options.map { it.trim() },
                status = AskUserQuestionStatus.PENDING,
                createdAt = Instant.now().toString(),
            )
            writeRecord(runId, updated)
            return updated
        }
    }

    override fun getPendingQuestion(runId: String): AskUserQuestion? {
        synchronized(lock) {
            return readRecord(runId)?.takeIf { it.status == AskUserQuestionStatus.PENDING }
        }
    }

    override fun submitAnswer(runId: String, choice: String): AskUserQuestion? {
        synchronized(lock) {
            val existing = readRecord(runId) ?: return null
            if (existing.status != AskUserQuestionStatus.PENDING) {
                return existing
            }
            val normalizedChoice = choice.trim()
            require(existing.options.contains(normalizedChoice)) { "choice must be one of question options" }
            val updated = existing.copy(
                status = AskUserQuestionStatus.ANSWERED,
                answer = normalizedChoice,
                answeredAt = Instant.now().toString(),
            )
            writeRecord(runId, updated)
            return updated
        }
    }

    private fun readRecord(runId: String): AskUserQuestion? {
        val file = pathFor(runId)
        if (!file.exists()) {
            return null
        }
        return json.decodeFromString(AskUserQuestion.serializer(), file.readText())
    }

    private fun writeRecord(runId: String, record: AskUserQuestion) {
        val serialized = json.encodeToString(record)
        pathFor(runId).writeText(serialized)
    }

    private fun pathFor(runId: String): Path {
        val normalizedRunId = runId.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        val path = root.resolve("$normalizedRunId.json").normalize()
        require(path.startsWith(root)) { "Ask user file path escapes ask-user directory" }
        Files.createDirectories(root)
        return path
    }
}
