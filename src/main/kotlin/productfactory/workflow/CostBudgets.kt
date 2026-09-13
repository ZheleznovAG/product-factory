package productfactory.workflow

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import kotlin.io.path.exists

data class CostBudgetsConfig(
    val apiVersion: String? = null,
    val kind: String? = null,
    val periodCaps: CostBudgetPeriodCaps = CostBudgetPeriodCaps(),
)

data class CostBudgetPeriodCaps(
    val weekly: CostBudgetPeriodLimit? = null,
    val monthly: CostBudgetPeriodLimit? = null,
)

data class CostBudgetPeriodLimit(
    val llm_tokens_total: Long? = null,
    val tool_calls_total: Long? = null,
    val runs_total: Long? = null,
    val soft_threshold_ratio: Double? = null,
    val hard_threshold_ratio: Double? = null,
)

data class CostBudgetViolation(
    val statusCode: Int,
    val code: String,
    val message: String,
    val details: Map<String, String>,
)

class CostBudgetGuard(
    private val config: CostBudgetsConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun check(snapshot: PeriodUsageSnapshot): CostBudgetViolation? {
        val monthlyDate = YearMonth.now(clock).toString()
        val weeklyTotal = sumWeekly(snapshot)
        val monthlyTotal = snapshot.monthly[monthlyDate] ?: UsageCounters()

        val monthlyViolation = evaluatePeriod(
            period = "monthly",
            usage = monthlyTotal,
            limit = config.periodCaps.monthly,
        )
        if (monthlyViolation != null) return monthlyViolation

        return evaluatePeriod(
            period = "weekly",
            usage = weeklyTotal,
            limit = config.periodCaps.weekly,
        )
    }

    private fun sumWeekly(snapshot: PeriodUsageSnapshot): UsageCounters {
        val today = LocalDate.now(clock)
        val firstDay = today.minusDays(6)
        var runs = 0L
        var tokens = 0L
        var toolCalls = 0L
        for ((day, counters) in snapshot.daily) {
            val dayDate = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
            if (dayDate < firstDay || dayDate > today) continue
            runs += counters.runs
            tokens += counters.tokens
            toolCalls += counters.tool_calls
        }
        return UsageCounters(runs = runs, tokens = tokens, tool_calls = toolCalls)
    }

    private fun evaluatePeriod(
        period: String,
        usage: UsageCounters,
        limit: CostBudgetPeriodLimit?,
    ): CostBudgetViolation? {
        if (limit == null) return null
        val hard = limit.hard_threshold_ratio ?: 1.0
        val soft = limit.soft_threshold_ratio ?: 0.8
        val checks = listOf(
            Triple("tokens", usage.tokens, limit.llm_tokens_total),
            Triple("tool_calls", usage.tool_calls, limit.tool_calls_total),
            Triple("runs", usage.runs, limit.runs_total),
        )
        for ((metric, used, max) in checks) {
            if (max == null || max <= 0L) continue
            val hardLimit = threshold(max, hard)
            val softLimit = threshold(max, soft)
            if (used >= hardLimit) {
                return CostBudgetViolation(
                    statusCode = 403,
                    code = "COST_BUDGET_HARD_CAP_EXCEEDED",
                    message = "Cost hard cap exceeded for $period $metric",
                    details = mapOf(
                        "period" to period,
                        "metric" to metric,
                        "used" to used.toString(),
                        "limit" to max.toString(),
                        "threshold" to hardLimit.toString(),
                    ),
                )
            }
            if (used >= softLimit) {
                return CostBudgetViolation(
                    statusCode = 429,
                    code = "COST_BUDGET_SOFT_CAP_EXCEEDED",
                    message = "Cost soft cap reached for $period $metric",
                    details = mapOf(
                        "period" to period,
                        "metric" to metric,
                        "used" to used.toString(),
                        "limit" to max.toString(),
                        "threshold" to softLimit.toString(),
                    ),
                )
            }
        }
        return null
    }

    private fun threshold(limit: Long, ratio: Double): Long {
        val raw = (limit.toDouble() * ratio).toLong()
        return raw.coerceAtLeast(1L)
    }

    companion object {
        fun fromEnv(
            path: Path = Path.of(System.getenv("COST_BUDGETS_PATH") ?: "policies/cost-budgets.yaml"),
            clock: Clock = Clock.systemUTC(),
        ): CostBudgetGuard? {
            if (!path.exists()) return null
            val mapper = ObjectMapper(YAMLFactory())
                .registerKotlinModule()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val config = runCatching { mapper.readValue<CostBudgetsConfig>(path.toFile()) }.getOrNull() ?: return null
            return CostBudgetGuard(config = config, clock = clock)
        }
    }
}
