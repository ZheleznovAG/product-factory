package productfactory

import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import productfactory.api.FactoryRunResponse
import productfactory.policy.PolicyCheck
import productfactory.workflow.InMemoryPeriodUsageStore
import java.time.ZoneOffset
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FactoryPeriodUsageApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `post factory run updates daily and monthly counters`() = testApplication {
        val periodUsageStore = InMemoryPeriodUsageStore()
        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                periodUsageStore = periodUsageStore,
            )
        }

        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"","constraints":[]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNotNull(json.decodeFromString<FactoryRunResponse>(response.body<String>()).runId)

        val now = java.time.Instant.now().atZone(ZoneOffset.UTC).toLocalDate()
        val dayKey = now.toString()
        val monthKey = YearMonth.from(now).toString()
        val snapshot = periodUsageStore.snapshot()
        val dayCounters = snapshot.daily[dayKey]
        val monthCounters = snapshot.monthly[monthKey]

        assertNotNull(dayCounters)
        assertNotNull(monthCounters)
        assertEquals(1, dayCounters.runs)
        assertEquals(1, monthCounters.runs)
        assertTrue(dayCounters.tokens >= 0)
        assertTrue(dayCounters.tool_calls >= 0)
    }
}
