package productfactory.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.neural.ChatCompletionResult
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import productfactory.rag.PlannerContextProvider
import productfactory.workflow.AuditLog
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LlmAgentPlannerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `returns planner artifacts for valid llm json`() {
        val neuralClient = RecordingNeuralServiceClient(
            response = """
            {
              "pipeline_plan": {
                "type": "pipeline_plan",
                "steps": [
                  {
                    "id": "validate",
                    "description": "Validate contracts",
                    "tool": "contract_validator",
                    "depends_on": []
                  }
                ]
              },
              "adr_draft": {
                "type": "adr_draft",
                "context": "Context",
                "decision": "Decision",
                "consequences": ["One"]
              },
              "test_plan": {
                "type": "test_plan",
                "scope": "Scope",
                "cases": [
                  {
                    "id": "t1",
                    "description": "Test",
                    "type": "unit"
                  }
                ],
                "coverage_targets": {
                  "unit_percent": 75,
                  "integration_required": true,
                  "security_required": true
                }
              }
            }
            """.trimIndent(),
        )
        val auditLog = InMemoryPlannerAuditLog()
        val planner = LlmAgentPlanner(
            neuralClient = neuralClient,
            auditLog = auditLog,
        )

        val result = planner.generate(AgentPlannerInput(goal = "test"))
        val artifacts = result.artifacts

        assertNotNull(artifacts)
        assertEquals("pipeline_plan", artifacts.pipelinePlan.type)
        assertTrue(artifacts.pipelinePlan.steps.isNotEmpty())
        assertEquals("adr_draft", artifacts.adrDraft.type)
        assertEquals("test_plan", artifacts.testPlan.type)
        assertEquals("contract_validator", artifacts.pipelinePlan.steps.first().tool)
        assertEquals(0.25, neuralClient.lastTemperature)
        assertEquals(Duration.ofSeconds(120), neuralClient.lastTimeout)
        assertTrue(neuralClient.lastMessages.first().content.contains("Role prompt template: planner/v2"))
        assertTrue(neuralClient.lastMessages.first().content.contains("Output must be strictly JSON only"))
        assertTrue(neuralClient.lastMessages.first().content.contains("Downstream role contract:"))
        assertTrue(neuralClient.lastMessages.first().content.contains("Implementer:"))
        val callEvent = auditLog.events.last()
        assertEquals("agent_planner_call", callEvent.eventType)
        val payload = json.parseToJsonElement(callEvent.payload).jsonObject
        assertTrue(payload.getValue("latency_ms").jsonPrimitive.content.toLong() >= 0)
        assertTrue(payload.getValue("success").jsonPrimitive.boolean)
        assertTrue(!payload.getValue("fallback_used").jsonPrimitive.boolean)
        assertTrue(payload.getValue("rationale").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `falls back on invalid json`() {
        val neuralClient = RecordingNeuralServiceClient(response = "not a json")
        val auditLog = InMemoryPlannerAuditLog()
        val planner = LlmAgentPlanner(
            neuralClient = neuralClient,
            auditLog = auditLog,
        )

        val result = planner.generate(AgentPlannerInput(goal = "Goal"))

        assertEquals("pipeline_plan", result.artifacts.pipelinePlan.type)
        assertTrue(result.artifacts.pipelinePlan.steps.any { it.tool == "contract_validator" })
        val callEvent = auditLog.events.last()
        val payload = json.parseToJsonElement(callEvent.payload).jsonObject
        assertTrue(!payload.getValue("success").jsonPrimitive.boolean)
        assertTrue(payload.getValue("fallback_used").jsonPrimitive.boolean)
        assertTrue(payload.getValue("rationale").jsonPrimitive.content.contains("fallback"))
    }

    @Test
    fun `falls back on null response`() {
        val neuralClient = RecordingNeuralServiceClient(response = null)
        val planner = LlmAgentPlanner(neuralClient = neuralClient)

        val result = planner.generate(AgentPlannerInput(goal = "Goal"))
        val artifacts = result.artifacts

        assertEquals("pipeline_plan", artifacts.pipelinePlan.type)
        assertTrue(artifacts.pipelinePlan.steps.any { it.id == "validate_contracts" })
        assertEquals("adr_draft", artifacts.adrDraft.type)
        assertEquals("test_plan", artifacts.testPlan.type)
    }

    @Test
    fun `falls back when llm returns disallowed tool`() {
        val neuralClient = RecordingNeuralServiceClient(
            response = """
            {
              "pipeline_plan": {
                "type": "pipeline_plan",
                "steps": [
                  {
                    "id": "x",
                    "description": "Try dangerous tool",
                    "tool": "dangerous_tool",
                    "depends_on": []
                  }
                ]
              },
              "adr_draft": {
                "type": "adr_draft",
                "context": "Context",
                "decision": "Decision",
                "consequences": ["One"]
              },
              "test_plan": {
                "type": "test_plan",
                "scope": "Scope",
                "cases": [
                  {"id": "t1", "description": "Test", "type": "unit"}
                ],
                "coverage_targets": {
                  "unit_percent": 70,
                  "integration_required": true,
                  "security_required": true
                }
              }
            }
            """.trimIndent(),
        )
        val planner = LlmAgentPlanner(neuralClient = neuralClient)

        val result = planner.generate(AgentPlannerInput(goal = "Goal"))
        val artifacts = result.artifacts

        assertTrue(artifacts.pipelinePlan.steps.none { it.tool == "dangerous_tool" })
        assertTrue(artifacts.pipelinePlan.steps.any { it.tool == "create_repo_from_archetype" })
    }

    @Test
    fun `falls back and logs audit when llm throws`() {
        val auditLog = InMemoryPlannerAuditLog()
        val planner = LlmAgentPlanner(
            neuralClient = ThrowingNeuralServiceClient(),
            auditLog = auditLog,
        )

        val result = planner.generate(AgentPlannerInput(goal = "Goal"))

        assertEquals("pipeline_plan", result.artifacts.pipelinePlan.type)
        assertTrue(result.artifacts.pipelinePlan.steps.any { it.tool == "contract_validator" })
        val callEvent = auditLog.events.last()
        val payload = json.parseToJsonElement(callEvent.payload).jsonObject
        assertTrue(!payload.getValue("success").jsonPrimitive.boolean)
        assertTrue(payload.getValue("fallback_used").jsonPrimitive.boolean)
        assertTrue(payload.getValue("rationale").jsonPrimitive.content.contains("fallback"))
    }

    @Test
    fun `includes optional rag context in user prompt when provider is set`() {
        val neuralClient = RecordingNeuralServiceClient(
            response = """
            {
              "pipeline_plan": {"type": "pipeline_plan","steps": [{"id": "validate","description": "Validate","tool": "contract_validator","depends_on": []}]},
              "adr_draft": {"type": "adr_draft","context": "Context","decision": "Decision","consequences": ["One"]},
              "test_plan": {"type": "test_plan","scope": "Scope","cases": [{"id": "t1","description": "Test","type": "unit"}],"coverage_targets": {"unit_percent": 70,"integration_required": true,"security_required": true}}
            }
            """.trimIndent(),
        )
        val planner = LlmAgentPlanner(
            neuralClient = neuralClient,
            contextProvider = StaticPlannerContextProvider("1. [docs/runbook.md] RAG snippet"),
        )

        planner.generate(AgentPlannerInput(goal = "Goal"))

        val userPrompt = neuralClient.lastMessages.last().content
        assertTrue(userPrompt.contains("Retrieved context (RAG, optional):"))
        assertTrue(userPrompt.contains("docs/runbook.md"))
        assertTrue(userPrompt.contains("RAG snippet"))
    }
}

private class InMemoryPlannerAuditLog : AuditLog {
    val events = mutableListOf<PlannerAuditEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += PlannerAuditEvent(runId = runId, eventType = eventType, payload = payload)
    }
}

private data class PlannerAuditEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)

private class RecordingNeuralServiceClient(
    private val response: String?,
) : NeuralServiceClient {
    lateinit var lastMessages: List<ChatMessage>
    var lastTemperature: Double? = null
    var lastTimeout: Duration? = null

    override fun chatCompletion(messages: List<ChatMessage>, temperature: Double?, timeout: Duration?): ChatCompletionResult {
        lastMessages = messages
        lastTemperature = temperature
        lastTimeout = timeout
        return ChatCompletionResult(content = response, inputTokens = 0, outputTokens = 0)
    }
}

private class ThrowingNeuralServiceClient : NeuralServiceClient {
    override fun chatCompletion(messages: List<ChatMessage>, temperature: Double?, timeout: Duration?): ChatCompletionResult {
        error("timeout")
    }
}

private class StaticPlannerContextProvider(
    private val value: String?,
) : PlannerContextProvider {
    override fun contextFor(input: AgentPlannerInput): String? = value
}
