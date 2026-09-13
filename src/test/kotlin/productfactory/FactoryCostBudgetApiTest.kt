package productfactory

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.policy.PolicyCheck
import productfactory.workflow.CostBudgetGuard
import productfactory.workflow.CostBudgetPeriodCaps
import productfactory.workflow.CostBudgetPeriodLimit
import productfactory.workflow.CostBudgetsConfig
import productfactory.workflow.InMemoryPeriodUsageStore
import productfactory.workflow.PeriodUsageDelta
import kotlin.test.Test
import kotlin.test.assertEquals

class FactoryCostBudgetApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `post factory run returns 429 when soft period cap reached`() = testApplication {
        val periodUsageStore = InMemoryPeriodUsageStore()
        periodUsageStore.record(PeriodUsageDelta(runs = 8))
        val guard = CostBudgetGuard(
            config = CostBudgetsConfig(
                periodCaps = CostBudgetPeriodCaps(
                    monthly = CostBudgetPeriodLimit(
                        runs_total = 10,
                        soft_threshold_ratio = 0.8,
                        hard_threshold_ratio = 1.0,
                    ),
                ),
            ),
        )

        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                periodUsageStore = periodUsageStore,
                costBudgetGuard = guard,
            )
        }

        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"","constraints":[]}""")
        }

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("COST_BUDGET_SOFT_CAP_EXCEEDED", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `post factory run returns 403 when hard period cap exceeded`() = testApplication {
        val periodUsageStore = InMemoryPeriodUsageStore()
        periodUsageStore.record(PeriodUsageDelta(runs = 10))
        val guard = CostBudgetGuard(
            config = CostBudgetsConfig(
                periodCaps = CostBudgetPeriodCaps(
                    monthly = CostBudgetPeriodLimit(
                        runs_total = 10,
                        soft_threshold_ratio = 0.8,
                        hard_threshold_ratio = 1.0,
                    ),
                ),
            ),
        )

        application {
            module(
                policyCheck = PolicyCheck(opaBaseUrl = null),
                periodUsageStore = periodUsageStore,
                costBudgetGuard = guard,
            )
        }

        val response = client.post("/factory/run") {
            contentType(ContentType.Application.Json)
            setBody("""{"goal":"","constraints":[]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("COST_BUDGET_HARD_CAP_EXCEEDED", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }
}
