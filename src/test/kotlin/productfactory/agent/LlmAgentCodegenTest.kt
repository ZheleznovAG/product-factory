package productfactory.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.neural.ChatCompletionResult
import productfactory.neural.ChatMessage
import productfactory.neural.NeuralServiceClient
import productfactory.workflow.AuditLog
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmAgentCodegenTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `returns codegen patch set for valid llm json`() {
        val validJson = """
            {
              "proposals": [
                {
                  "filePath": "README.md",
                  "summary": "Add readme",
                  "patch": "diff --git a/README.md b/README.md\nindex 0000000..1111111 100644\n--- a/README.md\n+++ b/README.md\n@@ -0,0 +1,2 @@\n+# Service\n+Goal: test\n"
                },
                {
                  "filePath": "src/test/kotlin/SmokeTest.kt",
                  "summary": "Smoke test",
                  "structuredPatch": {
                    "path": "src/test/kotlin/SmokeTest.kt",
                    "old_content": null,
                    "new_content": "package generated\n\nimport kotlin.test.Test\nimport kotlin.test.assertTrue\n\nclass SmokeTest {\n    @Test\n    fun smoke() { assertTrue(true) }\n}\n"
                  }
                }
              ]
            }
        """.trimIndent()
        val neuralClient = RecordingCodegenNeuralServiceClient(response = validJson)
        val auditLog = CodegenTestAuditLog()
        val plannerResult = StubAgentPlanner().generate(AgentPlannerInput(goal = "Test"))
        val codegen = LlmAgentCodegen(neuralClient = neuralClient, auditLog = auditLog)

        val result = codegen.generate(
            AgentCodegenInput(
                goal = "Test goal",
                constraints = listOf("kotlin"),
                plannerArtifacts = plannerResult.artifacts,
            ),
        )

        assertEquals("codegen_patch_set", result.artifact.type)
        assertEquals(2, result.artifact.proposals.size)
        assertTrue(result.artifact.proposals.any { it.filePath == "README.md" && it.patch != null })
        assertTrue(result.artifact.proposals.any { it.filePath == "src/test/kotlin/SmokeTest.kt" && it.structuredPatch != null })
        assertTrue(neuralClient.lastMessages.first().content.contains("Role prompt template: codegen/v2"))
        assertTrue(neuralClient.lastMessages.first().content.contains("Collaboration role contract:"))
        assertTrue(neuralClient.lastMessages.first().content.contains("Tester:"))
        val codegenEvent = auditLog.events.lastOrNull { it.eventType == "agent_codegen_call" }
        assertTrue(codegenEvent != null)
        val payload = json.parseToJsonElement(codegenEvent.payload).jsonObject
        assertTrue(payload["success"]?.jsonPrimitive?.content == "true")
        assertTrue(payload["fallback_used"]?.jsonPrimitive?.content == "false")
    }

    @Test
    fun `falls back to stub on invalid json`() {
        val neuralClient = object : NeuralServiceClient {
            override fun chatCompletion(
                messages: List<ChatMessage>,
                temperature: Double?,
                timeout: Duration?,
            ): ChatCompletionResult = ChatCompletionResult(content = "not valid json", inputTokens = 0, outputTokens = 0)
        }
        val auditLog = CodegenTestAuditLog()
        val plannerResult = StubAgentPlanner().generate(AgentPlannerInput(goal = "Goal"))
        val codegen = LlmAgentCodegen(neuralClient = neuralClient, auditLog = auditLog)

        val result = codegen.generate(
            AgentCodegenInput(goal = "Goal", plannerArtifacts = plannerResult.artifacts),
        )

        assertEquals("codegen_patch_set", result.artifact.type)
        assertTrue(result.artifact.proposals.isNotEmpty())
        val codegenEvent = auditLog.events.lastOrNull { it.eventType == "agent_codegen_call" }
        assertTrue(codegenEvent != null)
        val payload = json.parseToJsonElement(codegenEvent.payload).jsonObject
        assertTrue(payload["fallback_used"]?.jsonPrimitive?.content == "true")
    }
}

private class CodegenTestAuditLog : AuditLog {
    val events = mutableListOf<CodegenTestAuditEvent>()

    override fun log(runId: String, eventType: String, payload: String) {
        events += CodegenTestAuditEvent(runId = runId, eventType = eventType, payload = payload)
    }
}

private data class CodegenTestAuditEvent(
    val runId: String,
    val eventType: String,
    val payload: String,
)

private class RecordingCodegenNeuralServiceClient(
    private val response: String?,
) : NeuralServiceClient {
    lateinit var lastMessages: List<ChatMessage>

    override fun chatCompletion(messages: List<ChatMessage>, temperature: Double?, timeout: Duration?): ChatCompletionResult {
        lastMessages = messages
        return ChatCompletionResult(content = response, inputTokens = 0, outputTokens = 0)
    }
}
