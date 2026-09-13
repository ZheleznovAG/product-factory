package productfactory.eval

import com.sun.net.httpserver.HttpServer
import io.opentelemetry.api.GlobalOpenTelemetry
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import productfactory.agent.AgentCodegen
import productfactory.agent.AgentCodegenInput
import productfactory.agent.AgentCodegenResult
import productfactory.agent.CodegenPatchSetArtifact
import productfactory.agent.CodegenProposal
import productfactory.agent.StubAgentCodegen
import productfactory.api.FactoryRunRequest
import productfactory.policy.PolicyCheck
import productfactory.workflow.ApprovalStore
import productfactory.workflow.ArtifactRegistry
import productfactory.workflow.FileArtifactRegistry
import productfactory.workflow.InMemoryApprovalStore
import productfactory.workflow.RunResult
import productfactory.workflow.WorkflowRunner
import productfactory.workflow.tools.ToolCallRequest
import productfactory.workflow.tools.ToolCallResult
import productfactory.workflow.tools.ToolExecutor
import productfactory.workflow.AuditLog

class GoldenScenariosTest {

    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun `golden scenarios produce expected outputs`() {
        val scenarioFiles = Files.list(resolveGoldenScenarioDirectory())
            .filter { it.fileName.toString().endsWith(".json") }
            .sorted()
            .toList()
        assertTrue(scenarioFiles.isNotEmpty(), "No golden scenarios found")

        for (file in scenarioFiles) {
            val scenario = json.decodeFromString<GoldenScenario>(file.readText())
            runScenario(scenario)
        }
    }

    private fun runScenario(scenario: GoldenScenario) {
        val auditLog = InMemoryAuditLog()
        val workspaceRoot = createTempDirectory("golden-workspace-").also { it.toFile().deleteOnExit() }
        val archetypesRoot = createTempDirectory("golden-archetypes-").also { it.toFile().deleteOnExit() }
        createArchetype(archetypesRoot, "catalog-service")
        createArchetype(archetypesRoot, "web-app")

        val artifactRegistryDir = createTempDirectory("golden-artifacts-").also { it.toFile().deleteOnExit() }
        val artifactRegistry: ArtifactRegistry = FileArtifactRegistry(artifactRegistryDir.toString())
        val toolExecutor = GoldenToolExecutor(
            auditLog = auditLog,
            workspaceRoot = workspaceRoot,
            archetypesRoot = archetypesRoot,
            simulateRollbackOnPatchFailure = scenario.input.simulateRollbackOnPatchFailure,
        )
        val agentCodegen = if (scenario.input.injectCodegenPatch) {
            PatchingAgentCodegen()
        } else {
            StubAgentCodegen()
        }

        withPolicyCheck(scenario.input.policy) { policyCheck ->
            val runner = WorkflowRunner(
                auditLog = auditLog,
                policyCheck = policyCheck,
                approvalStore = InMemoryApprovalStore(),
                toolExecutor = toolExecutor,
                artifactRegistry = artifactRegistry,
                agentCodegen = agentCodegen,
                tracer = GlobalOpenTelemetry.getTracer("golden-test"),
            )

            val result = runner.run(
                runId = scenario.input.runId,
                request = FactoryRunRequest(
                    goal = scenario.input.goal,
                    constraints = scenario.input.constraints,
                    targetStack = scenario.input.targetStack,
                ),
            )

            assertScenarioResult(scenario, result, auditLog, toolExecutor, artifactRegistry)
        }
    }

    private fun assertScenarioResult(
        scenario: GoldenScenario,
        result: RunResult,
        auditLog: InMemoryAuditLog,
        toolExecutor: GoldenToolExecutor,
        artifactRegistry: ArtifactRegistry,
    ) {
        val scenarioLabel = "scenario=${scenario.id}"
        assertEquals(scenario.expected.status, result.status, "$scenarioLabel status")
        scenario.expected.messageContains?.let {
            assertTrue(result.message.contains(it), "$scenarioLabel message. actual='${result.message}'")
        }

        val observedStates = auditLog.events
            .filter { it.eventType == "state_changed" }
            .mapNotNull { event ->
                parsePayload(event.payload)["newState"]?.jsonPrimitive?.content
            }
        assertEquals(scenario.expected.stateSequence, observedStates, "$scenarioLabel state sequence")

        val observedEventTypes = auditLog.events.map { it.eventType }.toSet()
        for (required in scenario.expected.requiredEventTypes) {
            assertTrue(required in observedEventTypes, "$scenarioLabel missing event type '$required'")
        }
        for (forbidden in scenario.expected.forbiddenEventTypes) {
            assertTrue(forbidden !in observedEventTypes, "$scenarioLabel forbidden event type '$forbidden'")
        }

        for (expectedToolCall in scenario.expected.expectedToolCalls) {
            val found = toolExecutor.observedToolCalls.any { observed ->
                observed.toolName == expectedToolCall.toolName &&
                    (expectedToolCall.archetypeId == null || observed.archetypeId == expectedToolCall.archetypeId)
            }
            assertTrue(found, "$scenarioLabel missing expected tool call $expectedToolCall")
        }

        scenario.expected.finalRegistryState?.let { expectedState ->
            val record = artifactRegistry.get(scenario.input.runId)
            assertNotNull(record, "$scenarioLabel artifact registry record is missing")
            assertEquals(expectedState, record.workflowState, "$scenarioLabel artifact registry final state")
        }
    }

    private fun resolveGoldenScenarioDirectory(): Path {
        var cursor: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (cursor != null) {
            val candidate = cursor.resolve("eval").resolve("golden")
            if (candidate.exists() && candidate.isDirectory()) {
                return candidate
            }
            cursor = cursor.parent
        }
        error("Unable to locate eval/golden directory")
    }

    private fun withPolicyCheck(policy: GoldenPolicyInput, block: (PolicyCheck) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                val response = buildPolicyResponse(policy)
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }
        try {
            block(PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    private fun buildPolicyResponse(policy: GoldenPolicyInput): String {
        val reasonField = policy.reason?.let { ""","reason":"$it"""" } ?: ""
        return """{"result":{"allow":${policy.allow},"require_human_approval":${policy.requireHumanApproval}$reasonField}}"""
    }

    private fun parsePayload(payload: String): JsonObject {
        return Json.parseToJsonElement(payload).jsonObject
    }

    private fun createArchetype(archetypesRoot: Path, archetypeId: String) {
        val dir = archetypesRoot.resolve(archetypeId)
        dir.createDirectories()
        dir.resolve("README.md").writeText("# $archetypeId archetype\n")
    }
}

private class PatchingAgentCodegen : AgentCodegen {
    override fun generate(input: AgentCodegenInput): AgentCodegenResult {
        return AgentCodegenResult(
            artifact = CodegenPatchSetArtifact(
                proposals = listOf(
                    CodegenProposal(
                        filePath = "README.md",
                        summary = "Add generated note",
                        patch = """
                            diff --git a/README.md b/README.md
                            index 0000000..1111111 100644
                            --- a/README.md
                            +++ b/README.md
                            @@ -1 +1,2 @@
                             # Generated
                            +Goal: ${input.goal}
                        """.trimIndent(),
                    ),
                ),
            ),
            tokenUsage = 0,
        )
    }
}

private class GoldenToolExecutor(
    private val auditLog: AuditLog,
    private val workspaceRoot: Path,
    private val archetypesRoot: Path,
    private val simulateRollbackOnPatchFailure: Boolean,
) : ToolExecutor {
    val observedToolCalls: MutableList<ObservedToolCall> = mutableListOf()

    override fun execute(runId: String, request: ToolCallRequest): ToolCallResult {
        val result = when (request.toolName) {
            "create_repo_from_archetype" -> createRepoFromArchetype(request)
            "apply_patch" -> applyPatch(runId)
            else -> ToolCallResult(success = true, message = "ok")
        }
        logToolCall(runId, request.toolName, result.success)
        return result
    }

    private fun createRepoFromArchetype(request: ToolCallRequest): ToolCallResult {
        val archetypeId = request.arguments["archetype_id"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "archetype_id is required")
        val repoName = request.arguments["repo_name"]?.jsonPrimitive?.content
            ?: return ToolCallResult(success = false, message = "repo_name is required")
        observedToolCalls += ObservedToolCall(toolName = request.toolName, archetypeId = archetypeId)

        val source = archetypesRoot.resolve(archetypeId)
        if (!source.exists() || !source.isDirectory()) {
            return ToolCallResult(success = false, message = "Archetype not found: $archetypeId")
        }
        val target = workspaceRoot.resolve(repoName)
        target.createDirectories()
        target.resolve("README.md").writeText("# Generated\n")

        return ToolCallResult(
            success = true,
            result = buildJsonObject {
                put("repo_name", repoName)
                put("repo_path", target.toAbsolutePath().toString())
                put("archetype_id", archetypeId)
                put("artifact_location", target.toAbsolutePath().toString())
            },
            message = "Repository created",
        )
    }

    private fun applyPatch(runId: String): ToolCallResult {
        observedToolCalls += ObservedToolCall(toolName = "apply_patch")
        if (simulateRollbackOnPatchFailure) {
            auditLog.log(
                runId,
                "rollback_executed",
                buildJsonObject {
                    put("runId", runId)
                    put("reason", "apply_patch_failed")
                }.toString(),
            )
            return ToolCallResult(success = false, message = "Tool call failed: apply_patch")
        }
        return ToolCallResult(success = true, message = "Patch applied")
    }

    private fun logToolCall(runId: String, toolName: String, success: Boolean) {
        auditLog.log(
            runId,
            "tool_call_executed",
            buildJsonObject {
                put("runId", runId)
                put("toolName", toolName)
                put("success", success)
            }.toString(),
        )
    }
}

private data class ObservedToolCall(
    val toolName: String,
    val archetypeId: String? = null,
)

private class InMemoryAuditLog : AuditLog {
    val events = mutableListOf<LoggedEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += LoggedEvent(runId = runId, eventType = eventType, payload = payload)
    }
}

private data class LoggedEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)

@Serializable
private data class GoldenScenario(
    val id: String,
    val description: String,
    val input: GoldenScenarioInput,
    val expected: GoldenScenarioExpected,
)

@Serializable
private data class GoldenScenarioInput(
    @SerialName("run_id")
    val runId: String,
    val goal: String,
    val constraints: List<String> = emptyList(),
    @SerialName("target_stack")
    val targetStack: String? = null,
    val policy: GoldenPolicyInput,
    @SerialName("inject_codegen_patch")
    val injectCodegenPatch: Boolean = false,
    @SerialName("simulate_rollback_on_patch_failure")
    val simulateRollbackOnPatchFailure: Boolean = false,
)

@Serializable
private data class GoldenPolicyInput(
    val allow: Boolean,
    @SerialName("require_human_approval")
    val requireHumanApproval: Boolean,
    val reason: String? = null,
)

@Serializable
private data class GoldenScenarioExpected(
    val status: String,
    @SerialName("message_contains")
    val messageContains: String? = null,
    @SerialName("state_sequence")
    val stateSequence: List<String>,
    @SerialName("required_event_types")
    val requiredEventTypes: List<String> = emptyList(),
    @SerialName("forbidden_event_types")
    val forbiddenEventTypes: List<String> = emptyList(),
    @SerialName("expected_tool_calls")
    val expectedToolCalls: List<ExpectedToolCall> = emptyList(),
    @SerialName("final_registry_state")
    val finalRegistryState: String? = null,
)

@Serializable
private data class ExpectedToolCall(
    @SerialName("tool_name")
    val toolName: String,
    @SerialName("archetype_id")
    val archetypeId: String? = null,
)
