package productfactory.intent

/**
 * Protocol for sequential intent clarification.
 * Questions are selected and ordered by goal/constraints. After each answer the intent draft is recalculated.
 */
interface IntentClarifier {
    fun updateIntentDraft(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
    ): IntentDraft

    fun nextQuestion(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
        currentIntentDraft: IntentDraft? = null,
    ): String?
}

class IntentClarification(
    private val intentGenerator: IntentGenerator = IntentGenerator(),
) : IntentClarifier {

    override fun updateIntentDraft(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
    ): IntentDraft {
        val normalizedGoal = goal.trim()
        val normalizedConstraints = constraints.map { it.trim() }.filter { it.isNotEmpty() }
        val plan = buildQuestionPlan(normalizedGoal, normalizedConstraints)
        val baseDraft = intentGenerator.generate(normalizedGoal, normalizedConstraints)
        val progress = analyzeAnswerProgress(plan, previousAnswers)

        val mustHave = (baseDraft.constraints.mustHave + progress.additionalMustHave).distinct()
        val outcomeResult = buildOutcomeResult(baseDraft.outcome.result, progress)
        val confidence = estimateConfidence(
            goal = normalizedGoal,
            constraints = normalizedConstraints,
            answeredFocuses = progress.answeredFocuses,
        )

        return baseDraft.copy(
            outcome = baseDraft.outcome.copy(
                result = outcomeResult,
                successSignals = (baseDraft.outcome.successSignals + progress.additionalSuccessSignals).distinct(),
            ),
            experience = baseDraft.experience.copy(
                interactionStyle = "Adaptive 3-5 clarification protocol (A/B + short phrase)",
                tempo = progress.tempo ?: baseDraft.experience.tempo,
            ),
            constraints = baseDraft.constraints.copy(mustHave = mustHave),
            confidence = confidence,
        )
    }

    override fun nextQuestion(
        goal: String,
        constraints: List<String>,
        previousAnswers: List<String>,
        currentIntentDraft: IntentDraft?,
    ): String? {
        val normalizedGoal = goal.trim()
        val normalizedConstraints = constraints.map { it.trim() }.filter { it.isNotEmpty() }
        val asked = previousAnswers.size
        if (asked >= MAX_QUESTIONS) return null

        val plan = buildQuestionPlan(normalizedGoal, normalizedConstraints)
        val progress = analyzeAnswerProgress(plan, previousAnswers)
        if (progress.currentQuestionIndex >= plan.size) return null

        val draft = currentIntentDraft ?: updateIntentDraft(normalizedGoal, normalizedConstraints, previousAnswers)
        if (shouldStopEarly(draft, asked)) return null

        progress.pendingClarificationFocus?.let { focus ->
            return buildClarifyingQuestion(plan.getValueForFocus(focus).text)
        }
        progress.fallbackToAbFocus?.let { focus ->
            return buildStrictAbFallbackQuestion(plan.getValueForFocus(focus).text)
        }

        return plan.getOrNull(progress.currentQuestionIndex)?.text
    }

    private fun buildQuestionPlan(goal: String, constraints: List<String>): List<QuestionTemplate> {
        val context = buildString {
            append(goal.lowercase())
            append(' ')
            append(constraints.joinToString(" ").lowercase())
        }
        val domain = detectDomain(goal, constraints)
        val urgencySignal = context.containsAny("mvp", "fast", "asap", "urgent", "quick")
        val qualitySignal = context.containsAny("production", "secure", "safety", "compliance", "stable", "reliable", "risk")
        val underspecified = goal.isBlank() || constraints.isEmpty()

        val priority = when {
            qualitySignal -> listOf(Focus.STRICTNESS, Focus.VALIDATION, Focus.OUTCOME, Focus.RISK, Focus.TEMPO)
            urgencySignal -> listOf(Focus.OUTCOME, Focus.TEMPO, Focus.VALIDATION, Focus.STRICTNESS, Focus.RISK)
            else -> listOf(Focus.OUTCOME, Focus.VALIDATION, Focus.STRICTNESS, Focus.TEMPO, Focus.RISK)
        }

        val selected = linkedSetOf(Focus.OUTCOME, Focus.VALIDATION, Focus.STRICTNESS)
        if (underspecified || urgencySignal) selected += Focus.TEMPO
        if (underspecified || qualitySignal || !urgencySignal) selected += Focus.RISK

        val ordered = priority.filter { it in selected }.toMutableList()
        if (ordered.size < MIN_QUESTIONS) {
            priority.forEach { focus ->
                if (focus !in ordered) ordered += focus
                if (ordered.size >= MIN_QUESTIONS) return@forEach
            }
        }

        return ordered
            .take(MAX_QUESTIONS)
            .map { focus -> QuestionTemplate(focus, QUESTION_BANK_BY_DOMAIN.getValue(domain).getValue(focus)) }
    }

    private fun detectDomain(goal: String, constraints: List<String>): Domain {
        val context = buildString {
            append(goal.lowercase())
            append(' ')
            append(constraints.joinToString(" ").lowercase())
        }

        val webScore = scoreDomain(context, WEB_DOMAIN_KEYWORDS)
        val apiScore = scoreDomain(context, API_DOMAIN_KEYWORDS)
        val dataPipelineScore = scoreDomain(context, DATA_PIPELINE_DOMAIN_KEYWORDS)
        return when {
            dataPipelineScore >= 2 && dataPipelineScore >= webScore && dataPipelineScore >= apiScore -> Domain.DATA_PIPELINE
            webScore >= 2 && webScore >= apiScore -> Domain.WEB_APP
            apiScore >= 2 -> Domain.API
            webScore > apiScore && webScore > dataPipelineScore -> Domain.WEB_APP
            dataPipelineScore > apiScore && dataPipelineScore > webScore -> Domain.DATA_PIPELINE
            else -> Domain.API
        }
    }

    private fun scoreDomain(context: String, keywords: List<String>): Int =
        keywords.count { keyword -> context.contains(keyword) }

    private fun analyzeAnswerProgress(
        plan: List<QuestionTemplate>,
        previousAnswers: List<String>,
    ): AnswerProgress {
        var tempo: String? = null
        val additionalMustHave = mutableListOf<String>()
        val additionalSignals = mutableListOf<String>()
        val answeredFocuses = mutableSetOf<Focus>()
        var selectedOutcome: String? = null
        var selectedTradeoff: String? = null
        val clarificationAskedFor = mutableSetOf<Focus>()
        var pendingClarificationFocus: Focus? = null
        var fallbackToAbFocus: Focus? = null
        var currentQuestionIndex = 0

        previousAnswers.forEach { rawAnswer ->
            val question = plan.getOrNull(currentQuestionIndex) ?: return@forEach
            val choice = resolveChoice(question.focus, rawAnswer)
            if (choice == null) {
                if (question.focus in clarificationAskedFor) {
                    fallbackToAbFocus = question.focus
                    pendingClarificationFocus = null
                } else {
                    clarificationAskedFor += question.focus
                    pendingClarificationFocus = question.focus
                    fallbackToAbFocus = null
                }
                return@forEach
            }

            pendingClarificationFocus = null
            fallbackToAbFocus = null
            answeredFocuses += question.focus
            when (question.focus) {
                Focus.OUTCOME -> {
                    selectedOutcome = if (choice == Choice.A) "Deliver fast MVP first" else "Establish production baseline first"
                }
                Focus.VALIDATION -> {
                    if (choice == Choice.A) {
                        additionalMustHave += "Use lightweight checks in first iteration"
                        additionalSignals += "First draft produced with minimal validation overhead"
                    } else {
                        additionalMustHave += "Run full validation gates on each step"
                        additionalSignals += "Each stage includes validation and explainable evidence"
                    }
                }
                Focus.STRICTNESS -> {
                    if (choice == Choice.A) {
                        additionalMustHave += "Secondary constraints may be relaxed if needed"
                    } else {
                        additionalMustHave += "All listed constraints remain strict must-have"
                    }
                }
                Focus.TEMPO -> {
                    tempo = if (choice == Choice.A) "fast" else "slow"
                }
                Focus.RISK -> {
                    selectedTradeoff = if (choice == Choice.A) {
                        "Prioritize time-to-value"
                    } else {
                        "Prioritize minimizing risk and regressions"
                    }
                }
            }
            currentQuestionIndex += 1
        }

        return AnswerProgress(
            currentQuestionIndex = currentQuestionIndex,
            tempo = tempo,
            selectedOutcome = selectedOutcome,
            selectedTradeoff = selectedTradeoff,
            additionalMustHave = additionalMustHave,
            additionalSuccessSignals = additionalSignals,
            answeredFocuses = answeredFocuses,
            pendingClarificationFocus = pendingClarificationFocus,
            fallbackToAbFocus = fallbackToAbFocus,
        )
    }

    private fun buildOutcomeResult(baseResult: String, progress: AnswerProgress): String {
        val outcome = progress.selectedOutcome
        val tradeoff = progress.selectedTradeoff
        return when {
            outcome != null && tradeoff != null -> "$outcome; $tradeoff."
            outcome != null -> "$outcome."
            tradeoff != null -> "$baseResult $tradeoff."
            else -> baseResult
        }
    }

    private fun estimateConfidence(
        goal: String,
        constraints: List<String>,
        answeredFocuses: Set<Focus>,
    ): Double {
        val hasGoal = goal.isNotBlank()
        val hasConstraints = constraints.isNotEmpty()
        var confidence = if (hasGoal) 0.45 else 0.3
        if (hasConstraints) confidence += 0.1
        confidence += answeredFocuses.size * 0.1
        if (Focus.STRICTNESS in answeredFocuses || hasConstraints) confidence += 0.05
        if (Focus.OUTCOME in answeredFocuses) confidence += 0.05
        return confidence.coerceAtMost(0.95)
    }

    private fun shouldStopEarly(draft: IntentDraft, asked: Int): Boolean {
        if (asked < MIN_QUESTIONS) return false
        val hasGoal = draft.outcome.goal.isNotBlank() && draft.outcome.goal != "unspecified goal"
        val hasMustHave = draft.constraints.mustHave.isNotEmpty()
        val hasOutcome = draft.outcome.result.isNotBlank()
        val confidence = draft.confidence ?: 0.0
        return hasGoal && hasMustHave && hasOutcome && confidence >= EARLY_COMPLETION_CONFIDENCE
    }

    private fun String.containsAny(vararg parts: String): Boolean = parts.any { contains(it) }

    private fun resolveChoice(focus: Focus, answer: String): Choice? {
        parseChoice(answer)?.let { return it }
        val normalized = answer.trim().lowercase()
        if (normalized.isBlank() || normalized.length > MAX_SHORT_PHRASE_LENGTH) return null
        return inferChoiceByKeywords(focus, normalized)
    }

    private fun parseChoice(answer: String): Choice? {
        val normalized = answer.trim().lowercase()
        if (normalized.isBlank()) return null
        val match = CHOICE_TOKEN_REGEX.matchEntire(normalized) ?: return null
        return when (match.groupValues[1]) {
            "a", "а", "1" -> Choice.A
            "b", "б", "2" -> Choice.B
            else -> null
        }
    }

    private fun inferChoiceByKeywords(focus: Focus, normalized: String): Choice? {
        val keywordPair = KEYWORDS_BY_FOCUS.getValue(focus)
        val hasA = keywordPair.first.any { normalized.contains(it) }
        val hasB = keywordPair.second.any { normalized.contains(it) }
        return when {
            hasA && !hasB -> Choice.A
            hasB && !hasA -> Choice.B
            else -> null
        }
    }

    private fun buildClarifyingQuestion(baseQuestion: String): String =
        "Не до конца понял ответ. Уточни коротко или выбери A/B: $baseQuestion"

    private fun buildStrictAbFallbackQuestion(baseQuestion: String): String =
        "Нужен точный выбор A или B: $baseQuestion"

    private fun List<QuestionTemplate>.getValueForFocus(focus: Focus): QuestionTemplate =
        firstOrNull { it.focus == focus } ?: error("Question template for focus=$focus is missing")

    private data class QuestionTemplate(
        val focus: Focus,
        val text: String,
    )

    private data class AnswerProgress(
        val currentQuestionIndex: Int,
        val tempo: String?,
        val selectedOutcome: String?,
        val selectedTradeoff: String?,
        val additionalMustHave: List<String>,
        val additionalSuccessSignals: List<String>,
        val answeredFocuses: Set<Focus>,
        val pendingClarificationFocus: Focus?,
        val fallbackToAbFocus: Focus?,
    )

    private enum class Focus {
        OUTCOME,
        VALIDATION,
        STRICTNESS,
        TEMPO,
        RISK,
    }

    private enum class Domain {
        WEB_APP,
        API,
        DATA_PIPELINE,
    }

    private enum class Choice {
        A,
        B,
    }

    companion object {
        const val MIN_QUESTIONS = 3
        const val MAX_QUESTIONS = 5
        const val EARLY_COMPLETION_CONFIDENCE = 0.8
        private const val MAX_SHORT_PHRASE_LENGTH = 180
        private val CHOICE_TOKEN_REGEX = Regex("^([abаб12])(?:(?:\\s*[).:,-]\\s*)|\\s+|$)(.*)$")

        private val QUESTION_BANK_BY_DOMAIN: Map<Domain, Map<Focus, String>> = mapOf(
            Domain.API to mapOf(
                Focus.OUTCOME to "Что важнее для API на первом шаге: A) быстро отдать рабочие эндпоинты или B) сначала зафиксировать production baseline?",
                Focus.VALIDATION to "Как валидировать API: A) smoke/integration минимум или B) контрактные + нагрузочные проверки?",
                Focus.STRICTNESS to "По API-ограничениям выбираем: A) вторичные можно ослабить или B) все must-have и совместимость без компромиссов?",
                Focus.TEMPO to "Какой темп для API: A) короткие релизы эндпоинтов или B) размеренный темп со стабилизацией?",
                Focus.RISK to "Что приоритетнее для API: A) time-to-value или B) минимизация рисков/регрессий и breaking changes?",
            ),
            Domain.WEB_APP to mapOf(
                Focus.OUTCOME to "Что важнее для web-app: A) быстро показать рабочий UI flow (MVP) или B) сначала выстроить production foundation?",
                Focus.VALIDATION to "Как проверять web-app: A) быстрые smoke/manual проверки или B) e2e + accessibility + cross-browser?",
                Focus.STRICTNESS to "По web-app ограничениям выбираем: A) вторичные можно ослабить или B) все must-have (UX/perf/доступность) без компромиссов?",
                Focus.TEMPO to "Какой темп для web-app: A) быстрые UI-итерации или B) размеренный темп с полировкой качества?",
                Focus.RISK to "Что важнее для web-app: A) быстрее показать ценность пользователю или B) минимизировать риски UX-регрессий?",
            ),
            Domain.DATA_PIPELINE to mapOf(
                Focus.OUTCOME to "Что важнее для data pipeline: A) быстрее запустить поток данных end-to-end или B) сначала гарантировать качество и надежность данных?",
                Focus.VALIDATION to "Как валидировать data pipeline: A) базовые sanity-checks или B) строгие data quality gates + lineage?",
                Focus.STRICTNESS to "По ограничениям pipeline выбираем: A) вторичные можно ослабить или B) schema/quality must-have без компромиссов?",
                Focus.TEMPO to "Какой темп для pipeline: A) быстрые batch-итерации или B) размеренный темп ради стабильной эксплуатации?",
                Focus.RISK to "Что важнее для pipeline: A) быстрее получить first value from data или B) минимизировать риски потери/порчи данных?",
            ),
        )

        private val WEB_DOMAIN_KEYWORDS = listOf(
            "web",
            "web-app",
            "ui",
            "ux",
            "frontend",
            "front-end",
            "browser",
            "page",
            "react",
            "vue",
            "angular",
            "next.js",
            "landing",
            "mobile app",
            "screen",
            "form",
        )

        private val API_DOMAIN_KEYWORDS = listOf(
            "api",
            "rest",
            "graphql",
            "endpoint",
            "эндпоинт",
            "backend",
            "microservice",
            "service",
            "http",
            "grpc",
            "swagger",
            "openapi",
        )

        private val DATA_PIPELINE_DOMAIN_KEYWORDS = listOf(
            "data pipeline",
            "pipeline",
            "etl",
            "elt",
            "batch",
            "stream",
            "streaming",
            "kafka",
            "spark",
            "airflow",
            "dbt",
            "warehouse",
            "dwh",
            "lakehouse",
            "lineage",
            "dataset",
            "ingestion",
        )

        private val KEYWORDS_BY_FOCUS: Map<Focus, Pair<List<String>, List<String>>> = mapOf(
            Focus.OUTCOME to Pair(
                listOf("mvp", "быстр", "прототип", "prototype", "скорее а"),
                listOf("production", "prod", "baseline", "стабил", "надеж", "скорее б"),
            ),
            Focus.VALIDATION to Pair(
                listOf("меньше провер", "легк", "минималь", "быстрее", "черновик"),
                listOf("провер", "валидац", "тест", "evidence", "объясним", "строг"),
            ),
            Focus.STRICTNESS to Pair(
                listOf("ослаб", "гибк", "relax", "компромисс допустим"),
                listOf("must-have", "без компромисс", "строг", "жестк", "все обязательно"),
            ),
            Focus.TEMPO to Pair(
                listOf("быстр", "коротк", "итерац", "спринт"),
                listOf("медлен", "размерен", "спокойн", "стабил"),
            ),
            Focus.RISK to Pair(
                listOf("time-to-value", "скорость", "быстрее", "раньше"),
                listOf("риск", "безопас", "регресс", "надеж"),
            ),
        )
    }
}
