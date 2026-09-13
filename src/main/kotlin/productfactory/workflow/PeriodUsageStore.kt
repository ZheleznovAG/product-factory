package productfactory.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class UsageCounters(
    val runs: Long = 0,
    val tokens: Long = 0,
    val tool_calls: Long = 0,
)

@Serializable
data class PeriodUsageSnapshot(
    val daily: Map<String, UsageCounters> = emptyMap(),
    val monthly: Map<String, UsageCounters> = emptyMap(),
    val updated_at: String? = null,
)

data class PeriodUsageDelta(
    val runs: Long = 0,
    val tokens: Long = 0,
    val toolCalls: Long = 0,
)

interface PeriodUsageStore {
    fun record(delta: PeriodUsageDelta, at: Instant = Instant.now())
    fun snapshot(): PeriodUsageSnapshot
}

object NoopPeriodUsageStore : PeriodUsageStore {
    override fun record(delta: PeriodUsageDelta, at: Instant) = Unit
    override fun snapshot(): PeriodUsageSnapshot = PeriodUsageSnapshot()
}

class InMemoryPeriodUsageStore : PeriodUsageStore {
    private val lock = Any()
    private var state = PeriodUsageSnapshot()

    override fun record(delta: PeriodUsageDelta, at: Instant) {
        if (delta.runs == 0L && delta.tokens == 0L && delta.toolCalls == 0L) return
        synchronized(lock) {
            state = state.recorded(delta = delta, at = at)
        }
    }

    override fun snapshot(): PeriodUsageSnapshot = synchronized(lock) { state }
}

class FilePeriodUsageStore(
    filePath: Path = Path.of(System.getenv("PERIOD_USAGE_STORE_PATH") ?: "data/period-usage-counters.json"),
) : PeriodUsageStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val path = filePath.toAbsolutePath().normalize()
    private val root = path.parent?.toAbsolutePath()?.normalize()
    private val lock = Any()

    init {
        root?.createDirectories()
    }

    override fun record(delta: PeriodUsageDelta, at: Instant) {
        if (delta.runs == 0L && delta.tokens == 0L && delta.toolCalls == 0L) return
        synchronized(lock) {
            val current = readSnapshot()
            val updated = current.recorded(delta = delta, at = at)
            writeSnapshot(updated)
        }
    }

    override fun snapshot(): PeriodUsageSnapshot = synchronized(lock) { readSnapshot() }

    private fun readSnapshot(): PeriodUsageSnapshot {
        if (!path.exists()) return PeriodUsageSnapshot()
        return runCatching {
            json.decodeFromString(PeriodUsageSnapshot.serializer(), path.readText())
        }.getOrElse { PeriodUsageSnapshot() }
    }

    private fun writeSnapshot(snapshot: PeriodUsageSnapshot) {
        root?.createDirectories()
        val serialized = json.encodeToString(snapshot)
        val tmpPath = path.resolveSibling("${path.fileName}.tmp")
        tmpPath.writeText(serialized)
        runCatching {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun PeriodUsageSnapshot.recorded(delta: PeriodUsageDelta, at: Instant): PeriodUsageSnapshot {
    val dayKey = LocalDate.ofInstant(at, ZoneOffset.UTC).toString()
    val monthKey = YearMonth.from(LocalDate.ofInstant(at, ZoneOffset.UTC)).toString()
    return copy(
        daily = daily.withDelta(dayKey, delta),
        monthly = monthly.withDelta(monthKey, delta),
        updated_at = at.toString(),
    )
}

private fun Map<String, UsageCounters>.withDelta(
    key: String,
    delta: PeriodUsageDelta,
): Map<String, UsageCounters> {
    val current = this[key] ?: UsageCounters()
    val updated = current.copy(
        runs = (current.runs + delta.runs).coerceAtLeast(0),
        tokens = (current.tokens + delta.tokens).coerceAtLeast(0),
        tool_calls = (current.tool_calls + delta.toolCalls).coerceAtLeast(0),
    )
    return this + (key to updated)
}
