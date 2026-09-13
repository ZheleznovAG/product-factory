package productfactory.workflow

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PeriodUsageStoreTest {

    @Test
    fun `file store aggregates counters for day and month`() {
        val root = createTempDirectory(prefix = "period-usage-store-test-")
        val path = root.resolve("period-usage.json")
        val store = FilePeriodUsageStore(path)

        val instant = Instant.parse("2026-02-26T10:15:30Z")
        store.record(
            delta = PeriodUsageDelta(runs = 1, tokens = 1200, toolCalls = 4),
            at = instant,
        )
        store.record(
            delta = PeriodUsageDelta(runs = 2, tokens = 300, toolCalls = 1),
            at = instant.plusSeconds(120),
        )

        val snapshot = store.snapshot()
        val dayKey = instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val monthKey = YearMonth.from(instant.atZone(ZoneOffset.UTC).toLocalDate()).toString()
        val daily = snapshot.daily[dayKey]
        val monthly = snapshot.monthly[monthKey]

        assertEquals(3, daily?.runs)
        assertEquals(1500, daily?.tokens)
        assertEquals(5, daily?.tool_calls)
        assertEquals(3, monthly?.runs)
        assertEquals(1500, monthly?.tokens)
        assertEquals(5, monthly?.tool_calls)
    }

    @Test
    fun `file store clamps negative deltas to zero`() {
        val root = createTempDirectory(prefix = "period-usage-store-test-")
        val path = root.resolve("period-usage.json")
        val store = FilePeriodUsageStore(path)

        val instant = Instant.parse("2026-02-26T10:15:30Z")
        store.record(
            delta = PeriodUsageDelta(runs = 2, tokens = 100, toolCalls = 3),
            at = instant,
        )
        store.record(
            delta = PeriodUsageDelta(runs = -5, tokens = -1000, toolCalls = -10),
            at = instant.plusSeconds(60),
        )

        val snapshot = store.snapshot()
        val dayKey = instant.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val monthKey = YearMonth.from(instant.atZone(ZoneOffset.UTC).toLocalDate()).toString()
        val daily = assertNotNull(snapshot.daily[dayKey])
        val monthly = assertNotNull(snapshot.monthly[monthKey])

        assertEquals(0, daily.runs)
        assertEquals(0, daily.tokens)
        assertEquals(0, daily.tool_calls)
        assertEquals(0, monthly.runs)
        assertEquals(0, monthly.tokens)
        assertEquals(0, monthly.tool_calls)
    }
}
