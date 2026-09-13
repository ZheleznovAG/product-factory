package productfactory.observability

import java.util.concurrent.atomic.AtomicLong

/**
 * Простые счётчики и сумма длительностей для эндпоинта /metrics (Prometheus text format).
 * Обновляются из WorkflowRunner и API.
 */
object FactoryMetrics {
    private val runDurationBucketBoundsSeconds = longArrayOf(1, 5, 30, 60, 300, 900, 7200)
    private val runDurationBucketCounts = Array(runDurationBucketBoundsSeconds.size) { AtomicLong(0) }
    private val runsTotalOk = AtomicLong(0)
    private val runsTotalRejected = AtomicLong(0)
    private val runDurationSumMs = AtomicLong(0)
    private val runDurationCount = AtomicLong(0)
    private val toolCallsTotal = AtomicLong(0)
    private val llmPlannerCallsTotal = AtomicLong(0)
    private val llmCodegenCallsTotal = AtomicLong(0)
    private val llmInputTokensTotal = AtomicLong(0)
    private val llmOutputTokensTotal = AtomicLong(0)

    fun recordRun(status: String, durationMs: Long) {
        when (status) {
            "accepted" -> runsTotalOk.incrementAndGet()
            "rejected" -> runsTotalRejected.incrementAndGet()
        }
        val safeDurationMs = durationMs.coerceAtLeast(0)
        runDurationSumMs.addAndGet(safeDurationMs)
        runDurationCount.incrementAndGet()
        val durationSeconds = (safeDurationMs + 999) / 1000
        var i = 0
        while (i < runDurationBucketBoundsSeconds.size) {
            if (durationSeconds <= runDurationBucketBoundsSeconds[i]) {
                runDurationBucketCounts[i].incrementAndGet()
            }
            i++
        }
    }

    fun recordToolCall() {
        toolCallsTotal.incrementAndGet()
    }

    fun recordLlmPlannerCall() {
        llmPlannerCallsTotal.incrementAndGet()
    }

    fun recordLlmCodegenCall() {
        llmCodegenCallsTotal.incrementAndGet()
    }

    fun recordLlmTokens(inputTokens: Long, outputTokens: Long) {
        llmInputTokensTotal.addAndGet(inputTokens)
        llmOutputTokensTotal.addAndGet(outputTokens)
    }

    fun snapshot(): FactoryMetricsSnapshot {
        val accepted = runsTotalOk.get()
        val rejected = runsTotalRejected.get()
        val totalClassified = accepted + rejected
        val successRate = if (totalClassified > 0) accepted.toDouble() / totalClassified.toDouble() else null
        val p99Seconds = estimateQuantileSeconds(0.99)
        return FactoryMetricsSnapshot(
            acceptedRuns = accepted,
            rejectedRuns = rejected,
            runDurationCount = runDurationCount.get(),
            runDurationSumMs = runDurationSumMs.get(),
            successRate = successRate,
            p99DurationSeconds = p99Seconds,
            llmInputTokensTotal = llmInputTokensTotal.get(),
            llmOutputTokensTotal = llmOutputTokensTotal.get(),
            toolCallsTotal = toolCallsTotal.get(),
            llmPlannerCallsTotal = llmPlannerCallsTotal.get(),
            llmCodegenCallsTotal = llmCodegenCallsTotal.get(),
        )
    }

    private fun estimateQuantileSeconds(quantile: Double): Double? {
        val total = runDurationCount.get()
        if (total <= 0) return null
        val target = kotlin.math.ceil(quantile.coerceIn(0.0, 1.0) * total.toDouble()).toLong().coerceAtLeast(1L)
        var i = 0
        while (i < runDurationBucketBoundsSeconds.size) {
            val bucketCount = runDurationBucketCounts[i].get()
            if (bucketCount >= target) return runDurationBucketBoundsSeconds[i].toDouble()
            i++
        }
        return Double.POSITIVE_INFINITY
    }

    fun resetForTests() {
        runsTotalOk.set(0)
        runsTotalRejected.set(0)
        runDurationSumMs.set(0)
        runDurationCount.set(0)
        toolCallsTotal.set(0)
        llmPlannerCallsTotal.set(0)
        llmCodegenCallsTotal.set(0)
        llmInputTokensTotal.set(0)
        llmOutputTokensTotal.set(0)
        runDurationBucketCounts.forEach { it.set(0) }
    }

    /**
     * Prometheus exposition format (text).
     */
    fun prometheusText(): String {
        val sb = StringBuilder()
        sb.append("# HELP factory_runs_total Total factory runs by status\n")
        sb.append("# TYPE factory_runs_total counter\n")
        sb.append("factory_runs_total{status=\"accepted\"} ").append(runsTotalOk.get()).append("\n")
        sb.append("factory_runs_total{status=\"rejected\"} ").append(runsTotalRejected.get()).append("\n")
        sb.append("# HELP factory_run_duration_ms_sum Total run duration in milliseconds\n")
        sb.append("# TYPE factory_run_duration_ms_sum counter\n")
        sb.append("factory_run_duration_ms_sum ").append(runDurationSumMs.get()).append("\n")
        sb.append("# HELP factory_run_duration_ms_count Number of runs\n")
        sb.append("# TYPE factory_run_duration_ms_count counter\n")
        sb.append("factory_run_duration_ms_count ").append(runDurationCount.get()).append("\n")
        sb.append("# HELP factory_run_duration_seconds Run duration histogram in seconds\n")
        sb.append("# TYPE factory_run_duration_seconds histogram\n")
        var i = 0
        while (i < runDurationBucketBoundsSeconds.size) {
            sb.append("factory_run_duration_seconds_bucket{le=\"")
                .append(runDurationBucketBoundsSeconds[i])
                .append("\"} ")
                .append(runDurationBucketCounts[i].get())
                .append("\n")
            i++
        }
        sb.append("factory_run_duration_seconds_bucket{le=\"+Inf\"} ")
            .append(runDurationCount.get())
            .append("\n")
        sb.append("factory_run_duration_seconds_sum ")
            .append(runDurationSumMs.get().toDouble() / 1000.0)
            .append("\n")
        sb.append("factory_run_duration_seconds_count ").append(runDurationCount.get()).append("\n")
        sb.append("# HELP factory_tool_calls_total Total tool invocations\n")
        sb.append("# TYPE factory_tool_calls_total counter\n")
        sb.append("factory_tool_calls_total ").append(toolCallsTotal.get()).append("\n")
        sb.append("# HELP factory_llm_calls_total Total LLM invocations by role\n")
        sb.append("# TYPE factory_llm_calls_total counter\n")
        sb.append("factory_llm_calls_total{role=\"planner\"} ").append(llmPlannerCallsTotal.get()).append("\n")
        sb.append("factory_llm_calls_total{role=\"codegen\"} ").append(llmCodegenCallsTotal.get()).append("\n")
        sb.append("# HELP factory_llm_input_tokens_total Total LLM input tokens\n")
        sb.append("# TYPE factory_llm_input_tokens_total counter\n")
        sb.append("factory_llm_input_tokens_total ").append(llmInputTokensTotal.get()).append("\n")
        sb.append("# HELP factory_llm_output_tokens_total Total LLM output tokens\n")
        sb.append("# TYPE factory_llm_output_tokens_total counter\n")
        sb.append("factory_llm_output_tokens_total ").append(llmOutputTokensTotal.get()).append("\n")
        return sb.toString()
    }
}

data class FactoryMetricsSnapshot(
    val acceptedRuns: Long,
    val rejectedRuns: Long,
    val runDurationCount: Long,
    val runDurationSumMs: Long,
    val successRate: Double?,
    val p99DurationSeconds: Double?,
    val llmInputTokensTotal: Long,
    val llmOutputTokensTotal: Long,
    val toolCallsTotal: Long,
    val llmPlannerCallsTotal: Long,
    val llmCodegenCallsTotal: Long,
)
