package productfactory.workflow

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import productfactory.agent.AgentCodegen
import productfactory.agent.AgentCodegenInput
import productfactory.agent.AgentPlanner
import productfactory.agent.AgentPlannerInput
import productfactory.agent.toJson
import productfactory.api.FactoryRunRequest
import productfactory.config.FactoryEnvironment
import productfactory.contracts.ContractValidator
import productfactory.policy.PolicyCheck
import productfactory.policy.PolicyInput
import productfactory.policy.PolicyToolCall
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolExecutor
import productfactory.observability.FactoryMetrics
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer

/**
 * Выполнение отдельных шагов pipeline. Используется и в WorkflowRunner (in-process),
 * и в Temporal activities для durable execution (persist, retry).
 * Не управляет state machine — только бизнес-логика шага и audit.
 */
class FactoryWorkflowExecution(
    private val auditLog: AuditLog,
    private val policyCheck: PolicyCheck,
    private val approvalStore: ApprovalStore,
    private val toolExecutor: ToolExecutor,
    private val agentPlanner: AgentPlanner,
    private val agentCodegen: AgentCodegen,
    private val artifactRegistry: ArtifactRegistry,
    private val contractValidator: ContractValidator?,
    private val contractsDirectory: Path?,
    private val tracer: Tracer = GlobalOpenTelemetry.getTracer("productfactory.workflow"),
    private val environment: FactoryEnvironment? = null,
    private val environmentProvider: EnvironmentProvider = StubEnvironmentProvider(),
    private val supplyChainVersionResolver: SupplyChainVersionResolver = SupplyChainVersionResolver(),
    private val toolRegistryVersion: String? = null,
) {
    private val highRiskTools = setOf("create_github_repo", "push_repo_to_github")

    /** Логирует приём запроса (вызывать при старте run, в т.ч. из Temporal activity). */
    fun logRequestReceived(runId: String, request: FactoryRunRequest) {
        val environmentContext = environment?.let {
            environmentProvider.provide(runId, FactoryEnvironmentTypeMapper.toEnvironmentType(it))
        }
        auditLog.log(
            runId,
            "request_received",
            buildJsonObject {
                put("goal", request.goal)
                environment?.let { put("environment", it.envName) }
                environmentContext?.let { context ->
                    put("execution_environment_type", context.environmentType.value)
                    put("execution_mode", context.executionMode)
                    context.workingDirectory?.let { put("working_directory", it) }
                    if (context.parameters.isNotEmpty()) {
                        put("environment_parameters", context.parameters.toJsonObject())
                    }
                }
            }.toString(),
        )
    }

    fun executePlan(runId: String, request: FactoryRunRequest): PlanStepResult {
        val contractError = withSpan("validation", runId) {
            validateContractsIfConfigured(runId)
        }
        if (contractError != null) {
            throw WorkflowRejectedException(contractError)
        }
        val policyResult = withSpan("policy_check", runId) {
            policyCheck.check(
                runId = runId,
                input = PolicyInput(
                    goal = request.goal,
                    toolCalls = emptyList(),
                    tokenUsage = 0,
                ),
            )
        }
        auditLog.log(
            runId,
            "policy_check",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.PLAN_WORKFLOW))
                put("allowed", policyResult.allowed)
                put("requireHumanApproval", policyResult.requireHumanApproval)
                put("source", policyResult.source)
                put("reason", policyResult.reason)
                policyResult.decision?.let { put("decision", it) }
            }.toString(),
        )
        if (!policyResult.allowed) {
            throw WorkflowRejectedException(policyResult.reason)
        }
        if (policyResult.requireHumanApproval) {
            requireApprovalOrThrow(runId, policyResult.reason, policyResult.source, listOf(WorkflowStepId.PLAN_WORKFLOW))
        }
        return PlanStepResult(tokenUsage = 0)
    }

    fun executeGenerate(runId: String, request: FactoryRunRequest, planResult: PlanStepResult): GenerateStepResult {
        var tokenUsage = planResult.tokenUsage
        val planningResult = withSpan("agent_planner", runId) {
            FactoryMetrics.recordLlmPlannerCall()
            agentPlanner.generate(
                AgentPlannerInput(
                    goal = request.goal,
                    constraints = request.constraints,
                    productSpec = request.productSpec,
                    contracts = request.contracts,
                ),
            )
        }
        tokenUsage += planningResult.tokenUsage
        val planningArtifacts = planningResult.artifacts
        auditLog.log(runId, "pipeline_plan", planningArtifacts.pipelinePlanJson())
        auditLog.log(
            runId,
            "planner_explanation",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.PLAN_WORKFLOW))
                put("explanation", planningResult.rationale)
            }.toString(),
        )
        auditLog.log(runId, "adr_draft", planningArtifacts.adrDraftJson())
        auditLog.log(runId, "test_plan", planningArtifacts.testPlanJson())

        val codegenPolicyResult = withSpan("policy_check_codegen", runId) {
            policyCheck.check(
                runId = runId,
                input = PolicyInput(goal = request.goal, toolCalls = emptyList(), tokenUsage = tokenUsage),
            )
        }
        auditLog.log(
            runId,
            "policy_check_codegen",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.GENERATE_ARTIFACTS))
                put("allowed", codegenPolicyResult.allowed)
                put("requireHumanApproval", codegenPolicyResult.requireHumanApproval)
                put("source", codegenPolicyResult.source)
                put("reason", codegenPolicyResult.reason)
            }.toString(),
        )
        if (!codegenPolicyResult.allowed) {
            throw WorkflowRejectedException("Codegen proposal denied by policy: ${codegenPolicyResult.reason}")
        }
        if (codegenPolicyResult.requireHumanApproval) {
            requireApprovalOrThrow(
                runId,
                codegenPolicyResult.reason,
                codegenPolicyResult.source,
                listOf("${WorkflowStepId.GENERATE_ARTIFACTS}:codegen"),
            )
        }

        val codegenResult = withSpan("agent_codegen", runId) {
            FactoryMetrics.recordLlmCodegenCall()
            agentCodegen.generate(
                AgentCodegenInput(
                    goal = request.goal,
                    constraints = request.constraints,
                    plannerArtifacts = planningArtifacts,
                    productSpec = request.productSpec,
                    contracts = request.contracts,
                ),
            )
        }
        tokenUsage += codegenResult.tokenUsage
        val codegenProposals = codegenResult.artifact
        auditLog.log(runId, "codegen_patch_set", codegenProposals.toJson())
        auditLog.log(
            runId,
            "proposal_recorded",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.GENERATE_ARTIFACTS))
                put("runId", runId)
                put("proposal_type", "codegen")
                put("file_count", codegenProposals.proposals.size)
            }.toString(),
        )

        withSpan("agent_spec", runId) {
            val specJson = mockAgentSpecJson(request)
            auditLog.log(runId, "agent_spec", specJson.toString())
        }

        val plannedToolCalls = planToolCalls(runId, request)
        val repoName = "pf-${runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        val patchContent = codegenProposals.proposals.mapNotNull { it.patch }.joinToString("\n")
        val toolCallsToExecute = mutableListOf<ToolCallRequest>()
        toolCallsToExecute.addAll(plannedToolCalls)
        if (patchContent.isNotBlank()) {
            toolCallsToExecute.add(
                ToolCallRequest(
                    toolName = "apply_patch",
                    idempotencyKey = "$runId:apply_patch",
                    arguments = buildJsonObject {
                        put("repo_name", repoName)
                        put("patch_content", patchContent)
                    },
                ),
            )
        }
        return GenerateStepResult(tokenUsage = tokenUsage, toolCallsToExecute = toolCallsToExecute)
    }

    fun executeTools(
        runId: String,
        request: FactoryRunRequest,
        generateResult: GenerateStepResult,
    ): ToolStepResult {
        if (request.dryRun) {
            return buildDryRunToolPlan(runId, request, generateResult)
        }
        val environmentContext = resolveEnvironmentContext(runId)
        val executionContext = environmentContext?.executionContext ?: HostExecutionContext
        var repoUrl: String? = null
        var artifactLocation: String? = null
        var toolSbomVersion: String? = null
        var toolSignatureVersion: String? = null
        val executedToolCallIds = mutableListOf<String>()
        val workspaceRoot = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace")
        val defaultRepoName = "pf-${runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        var repoName = defaultRepoName
        for (toolCall in generateResult.toolCallsToExecute) {
            val policyResult = withSpan("policy_check_tool", runId) {
                policyCheck.check(
                    runId = runId,
                    input = PolicyInput(
                        goal = request.goal,
                        toolCalls = listOf(
                            PolicyToolCall(name = toolCall.toolName, argumentKeys = toolCall.arguments.keys.toList()),
                        ),
                        tokenUsage = generateResult.tokenUsage,
                    ),
                )
            }
            if (!policyResult.allowed) {
                auditLog.log(
                    runId,
                    "tool_call_denied",
                    buildJsonObject {
                        put("toolName", toolCall.toolName)
                        put("reason", policyResult.reason)
                    }.toString(),
                )
                throw WorkflowRejectedException("Tool call denied by policy: ${toolCall.toolName}")
            }
            if (policyResult.requireHumanApproval) {
                val approvalReason = if (toolCall.toolName in highRiskTools) {
                    "manual approval required for high-risk tool ${toolCall.toolName}"
                } else {
                    "${policyResult.reason} for tool ${toolCall.toolName}"
                }
                val proposedActions = if (toolCall.toolName in highRiskTools) {
                    listOf("risk_tier:high", "tool:${toolCall.toolName}")
                } else {
                    listOf("tool:${toolCall.toolName}")
                }
                requireApprovalOrThrow(
                    runId,
                    approvalReason,
                    policyResult.source,
                    proposedActions,
                )
            }
            val result = withSpan("tool_execute_${toolCall.toolName}", runId) {
                FactoryMetrics.recordToolCall()
                toolExecutor.execute(runId = runId, request = toolCall, executionContext = executionContext)
            }
            if (!result.success) {
                throw WorkflowRejectedException(result.message ?: "Tool call failed: ${toolCall.toolName}")
            }
            executedToolCallIds += "${toolCall.toolName}:${toolCall.idempotencyKey}"
            when (toolCall.toolName) {
                "create_github_repo" -> result.result["repo_url"]?.jsonPrimitive?.content?.let { repoUrl = it }
                "create_repo_from_archetype" -> {
                    result.result["artifact_location"]?.jsonPrimitive?.content?.let { artifactLocation = it }
                    result.result["repo_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { repoName = it }
                }
                else -> { }
            }
            result.result["sbom_version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { toolSbomVersion = it }
            result.result["signature_version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { toolSignatureVersion = it }
            result.result["sbomVersion"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { toolSbomVersion = it }
            result.result["signatureVersion"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { toolSignatureVersion = it }
        }
        val versions = supplyChainVersionResolver.resolve(
            workspaceRoot = workspaceRoot,
            repoName = repoName,
            toolSbomVersion = toolSbomVersion,
            toolSignatureVersion = toolSignatureVersion,
        )
        return ToolStepResult(
            repoUrl = repoUrl,
            artifactLocation = artifactLocation,
            sbomVersion = versions.sbomVersion,
            signatureVersion = versions.signatureVersion,
            executedToolCallIds = executedToolCallIds.toList(),
        )
    }

    private fun buildDryRunToolPlan(
        runId: String,
        request: FactoryRunRequest,
        generateResult: GenerateStepResult,
    ): ToolStepResult {
        val dryRunPlan = mutableListOf<DryRunToolCallPlanItem>()
        for (toolCall in generateResult.toolCallsToExecute) {
            val policyResult = withSpan("policy_check_tool", runId) {
                policyCheck.check(
                    runId = runId,
                    input = PolicyInput(
                        goal = request.goal,
                        toolCalls = listOf(
                            PolicyToolCall(name = toolCall.toolName, argumentKeys = toolCall.arguments.keys.toList()),
                        ),
                        tokenUsage = generateResult.tokenUsage,
                    ),
                )
            }
            val approval = when {
                !policyResult.requireHumanApproval -> DryRunApprovalOutcome(status = "not_required")
                approvalStore.isApproved(runId) -> DryRunApprovalOutcome(status = "approved")
                approvalStore.isRejected(runId) -> DryRunApprovalOutcome(status = "rejected")
                else -> DryRunApprovalOutcome(status = "pending", reason = "Human approval required by policy")
            }
            val wouldExecute = policyResult.allowed && (approval.status == "not_required" || approval.status == "approved")
            dryRunPlan += DryRunToolCallPlanItem(
                toolName = toolCall.toolName,
                idempotencyKey = toolCall.idempotencyKey,
                arguments = toolCall.arguments,
                policy = DryRunPolicyOutcome(
                    allowed = policyResult.allowed,
                    requireHumanApproval = policyResult.requireHumanApproval,
                    source = policyResult.source,
                    reason = policyResult.reason,
                    decision = policyResult.decision,
                ),
                approval = approval,
                wouldExecute = wouldExecute,
            )
        }

        auditLog.log(
            runId,
            "tool_call_dry_run_plan",
            buildJsonObject {
                put("runId", runId)
                put("role", roleAuditValue(WorkflowStepId.GENERATE_ARTIFACTS))
                put("dry_run", true)
                put(
                    "steps",
                    kotlinx.serialization.json.Json.encodeToJsonElement(
                        ListSerializer(DryRunToolCallPlanItem.serializer()),
                        dryRunPlan,
                    ),
                )
            }.toString(),
        )

        return ToolStepResult(
            dryRun = true,
            dryRunPlan = dryRunPlan,
        )
    }

    fun executeTests(runId: String): GateVerdict {
        val environmentContext = resolveEnvironmentContext(runId)
        val workspaceRoot = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace")
        val repoName = "pf-${runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        val executionContext = environmentContext?.executionContext ?: HostExecutionContext
        val testResult = TestRunner.run(workspaceRoot, repoName, executionContext)
        val verdict = testResult.toVerdict()
        auditLog.log(
            runId,
            if (testResult.skipped) "tests_skipped" else if (testResult.passed) "tests_passed" else "tests_failed",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.RUN_TESTS))
                put("verdict", buildJsonObject {
                    put("status", verdict.status.name)
                    put("reasonCode", verdict.reasonCode)
                    put("message", verdict.message)
                    if (verdict.artifactRefs.isNotEmpty()) put("artifactRefs", buildJsonArray { verdict.artifactRefs.forEach { add(JsonPrimitive(it)) } })
                })
                put("skipped", testResult.skipped)
                put("passed", testResult.passed)
                testResult.exitCode?.let { put("exitCode", it) }
                if (testResult.error.isNotBlank()) put("error", testResult.error.take(2000))
            }.toString(),
        )
        if (verdict.isFailure()) {
            throw WorkflowRejectedException(
                "Tests failed (exit ${testResult.exitCode}): ${testResult.error.take(500)}",
            )
        }
        return verdict
    }

    fun executeSecurity(runId: String): GateVerdict {
        val workspaceRoot = Path.of(System.getenv("FACTORY_WORKSPACE_DIR") ?: "workspace")
        val repoName = "pf-${runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        val secResult = SecurityRunner.run(workspaceRoot, repoName)
        val verdict = secResult.toVerdict()
        auditLog.log(
            runId,
            if (secResult.skipped) "security_checks_skipped" else if (secResult.passed) "security_checks_passed" else "security_checks_failed",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.RUN_SECURITY_CHECKS))
                put("verdict", buildJsonObject {
                    put("status", verdict.status.name)
                    put("reasonCode", verdict.reasonCode)
                    put("message", verdict.message)
                    if (verdict.artifactRefs.isNotEmpty()) put("artifactRefs", buildJsonArray { verdict.artifactRefs.forEach { add(JsonPrimitive(it)) } })
                })
                put("skipped", secResult.skipped)
                put("passed", secResult.passed)
                secResult.gitleaksExitCode?.let { put("gitleaks_exit_code", it) }
                secResult.trivyExitCode?.let { put("trivy_exit_code", it) }
                if (secResult.error.isNotBlank()) put("error", secResult.error.take(2000))
            }.toString(),
        )
        if (verdict.isFailure()) {
            throw WorkflowRejectedException("Security checks failed: ${secResult.error.take(500)}")
        }
        return verdict
    }

    fun executeStage(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult) {
        auditLog.log(
            runId,
            "staging_prepared",
            buildJsonObject {
                put("role", roleAuditValue(WorkflowStepId.STAGE_ARTIFACTS))
                put("result", "ok")
            }.toString(),
        )
        artifactRegistry.upsert(
            artifactRecordForState(runId, WorkflowState.STAGED, toolResult.repoUrl, toolResult.artifactLocation, toolResult.sbomVersion, toolResult.signatureVersion),
        )
        auditLog.log(
            runId,
            "artifact_registry_updated",
            buildJsonObject { put("runId", runId); put("state", WorkflowState.STAGED.name) }.toString(),
        )
        writeManifest(runId, request, WorkflowState.STAGED, toolResult)
    }

    fun executeSloGate(runId: String, request: FactoryRunRequest, generationTimeMs: Long): GateVerdict {
        val evaluation = SloGate(auditLog = auditLog).evaluate(request = request, generationTimeMs = generationTimeMs)
        val verdict = evaluation.verdict
        auditLog.log(
            runId,
            "slo_gate_evaluated",
            buildJsonObject {
                put("role", WorkflowAgentRole.REVIEWER.auditValue)
                put("enabled", evaluation.enabled)
                put("verdict", buildJsonObject {
                    put("status", verdict.status.name)
                    put("reasonCode", verdict.reasonCode)
                    put("message", verdict.message)
                })
                evaluation.thresholds?.let { thresholds ->
                    put("thresholds", buildJsonObject {
                        put("maxGenerationTimeSeconds", thresholds.maxGenerationTimeSeconds)
                        put("maxPolicyDenySharePercent", thresholds.maxPolicyDenySharePercent)
                        put("minSuccessRatePercent", thresholds.minSuccessRatePercent)
                        put("lookbackRuns", thresholds.lookbackRuns)
                    })
                }
                evaluation.metrics?.let { metrics ->
                    put("metrics", buildJsonObject {
                        put("generationTimeSeconds", metrics.generationTimeSeconds)
                        put("policyDenySharePercent", metrics.policyDenySharePercent)
                        put("successRatePercent", metrics.successRatePercent)
                        put("lookbackRunsEvaluated", metrics.lookbackRunsEvaluated)
                    })
                }
            }.toString(),
        )
        if (verdict.isFailure()) {
            throw WorkflowRejectedException(verdict.message)
        }
        return verdict
    }

    fun executeFinish(runId: String, request: FactoryRunRequest, toolResult: ToolStepResult) {
        artifactRegistry.upsert(
            artifactRecordForState(runId, WorkflowState.DONE, toolResult.repoUrl, toolResult.artifactLocation, toolResult.sbomVersion, toolResult.signatureVersion),
        )
        auditLog.log(
            runId,
            "artifact_registry_updated",
            buildJsonObject { put("runId", runId); put("state", WorkflowState.DONE.name) }.toString(),
        )
        writeManifest(runId, request, WorkflowState.DONE, toolResult)
    }

    private fun writeManifest(runId: String, request: FactoryRunRequest, state: WorkflowState, toolResult: ToolStepResult) {
        val auditPath = System.getenv("AUDIT_LOG_PATH")?.trim()?.takeIf { it.isNotBlank() } ?: "audit.log"
        val registryRecordPath = artifactRegistry.recordPath(runId) ?: "artifact-registry/$runId.json"
        val manifest = buildArtifactManifest(
            runId = runId,
            request = request,
            state = state,
            toolResult = toolResult,
            auditLogPath = auditPath,
            artifactRegistryRecordPath = registryRecordPath,
            toolRegistryVersion = toolRegistryVersion,
        )
        val manifestJson = manifest.toJsonString()
        val manifestPath = artifactRegistry.upsertManifest(runId, state, manifestJson)
        auditLog.log(
            runId,
            "artifact_manifest_written",
            buildJsonObject {
                put("runId", runId)
                put("state", state.name)
                manifestPath?.let { put("manifestPath", it) }
                put("manifest", kotlinx.serialization.json.Json.parseToJsonElement(manifestJson))
            }.toString(),
        )
    }

    private fun validateContractsIfConfigured(runId: String): String? {
        val validator = contractValidator ?: return null
        val directory = contractsDirectory ?: return null
        val result = validator.validateDirectory(directory)
        auditLog.log(
            runId,
            "contracts_validation",
            buildJsonObject { put("valid", result.valid); put("errors", result.errors.size) }.toString(),
        )
        if (result.valid) return null
        val firstError = result.errors.first()
        return buildString {
            append("Contracts validation failed: ")
            append("contract=").append(firstError.contractFile)
            append(", field=").append(firstError.field)
            append(", schema=").append(firstError.schema)
            append(", message=").append(firstError.message)
            append(", totalErrors=").append(result.errors.size)
        }
    }

    private fun resolveEnvironmentContext(runId: String): EnvironmentContext? {
        val configuredEnvironment = environment ?: return null
        return environmentProvider.provide(runId, FactoryEnvironmentTypeMapper.toEnvironmentType(configuredEnvironment))
    }

    private fun requireApprovalOrThrow(
        runId: String,
        reason: String,
        source: String,
        proposedActions: List<String>,
    ) {
        if (approvalStore.isApproved(runId)) {
            auditLog.log(
                runId,
                "approval_granted",
                buildJsonObject {
                    put("runId", runId)
                    put("reason", reason)
                    put("source", source)
                }.toString(),
            )
            return
        }
        if (approvalStore.isRejected(runId)) {
            throw WorkflowRejectedException("Human approval rejected for runId=$runId")
        }
        val approval = approvalStore.upsertPending(runId = runId, reason = reason, proposedActions = proposedActions)
        auditLog.log(
            runId,
            "approval_required",
            buildJsonObject {
                put("runId", runId)
                put("timestamp", approval.timestamp)
                put("reason", approval.reason)
                put("source", source)
                put("proposed_actions", buildJsonArray { approval.proposedActions.forEach { add(JsonPrimitive(it)) } })
            }.toString(),
        )
        throw WorkflowRejectedException("Human approval required by policy")
    }

    private fun planToolCalls(runId: String, request: FactoryRunRequest): List<ToolCallRequest> {
        val normalizedGoal = request.goal.trim()
        if (normalizedGoal.isEmpty()) return emptyList()
        val archetypeId = resolveArchetypeId(request)
        val repoName = "pf-${runId.lowercase().replace(Regex("[^a-z0-9-]"), "-")}"
        val createRepo = ToolCallRequest(
            toolName = "create_repo_from_archetype",
            idempotencyKey = "$runId:create_repo_from_archetype",
            arguments = buildJsonObject {
                put("repo_name", repoName)
                put("archetype_id", archetypeId)
            },
        )
        val githubToken = System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
        val githubOwner = System.getenv("GITHUB_OWNER")?.trim()?.takeIf { it.isNotBlank() } ?: ""
        val createGitHubRepo = if (githubToken != null) {
            ToolCallRequest(
                toolName = "create_github_repo",
                idempotencyKey = "$runId:create_github_repo",
                arguments = buildJsonObject {
                    put("repo_name", repoName)
                    put("owner", githubOwner)
                    put("private", false)
                },
            )
        } else null
        val pushRepo = if (githubToken != null) {
            ToolCallRequest(
                toolName = "push_repo_to_github",
                idempotencyKey = "$runId:push_repo_to_github",
                arguments = buildJsonObject {
                    put("repo_name", repoName)
                    put("owner", githubOwner)
                },
            )
        } else null
        return when {
            pushRepo != null -> listOf(createRepo, createGitHubRepo!!, pushRepo)
            createGitHubRepo != null -> listOf(createRepo, createGitHubRepo)
            else -> listOf(createRepo)
        }
    }

    private fun resolveArchetypeId(request: FactoryRunRequest): String {
        val ts = request.targetStack?.trim()?.lowercase()
        if (ts != null && ts.isNotEmpty()) {
            if (ts == "web-app" || ts == "webapp" || ts == "web") return "web-app"
            if (ts == "catalog-service" || ts == "api-service" || ts == "api") return "catalog-service"
        }
        val goalLower = request.goal.trim().lowercase()
        if (goalLower.contains("сайт") || goalLower.contains("website") || goalLower.contains("веб") ||
            goalLower.contains("лендинг") || goalLower.contains("landing") || goalLower.contains("страниц")
        ) return "web-app"
        return "catalog-service"
    }

    private fun mockAgentSpecJson(request: FactoryRunRequest) = buildJsonObject {
        put("type", "product_spec")
        put("goal", request.goal)
        put("summary", "Mock spec for MVP")
        put("components", buildJsonArray { })
    }

    private fun roleAuditValue(stepId: String): String = WorkflowStepRoleMapping.roleForStep(stepId)?.auditValue.orEmpty()

    private inline fun <T> withSpan(name: String, runId: String, block: (Span) -> T): T {
        val span = tracer.spanBuilder(name).setAttribute("factory.run_id", runId).startSpan()
        return try {
            span.makeCurrent().use { block(span) }
        } catch (t: Throwable) {
            span.recordException(t)
            span.setStatus(StatusCode.ERROR)
            throw t
        } finally {
            span.end()
        }
    }
}

private fun Map<String, String>.toJsonObject(): JsonObject {
    return buildJsonObject {
        forEach { (key, value) -> put(key, value) }
    }
}

class WorkflowRejectedException(message: String) : RuntimeException(message)
