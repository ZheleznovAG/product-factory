package productfactory.policy

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyCheckTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `policy check sends structured input to OPA and reads approval flag`() {
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/data/factory") { exchange ->
                requestBody = exchange.requestBody.bufferedReader().readText()
                val response = """{"result":{"allow":true,"require_human_approval":true}}"""
                exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
            start()
        }

        try {
            val policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:${server.address.port}")
            val result = policyCheck.check(
                runId = "run-1",
                input = PolicyInput(
                    goal = "build deployable service",
                    toolCalls = listOf(PolicyToolCall(name = "deploy_staging")),
                    tokenUsage = 321,
                ),
            )

            assertTrue(result.allowed)
            assertTrue(result.requireHumanApproval)
            assertEquals("opa", result.source)
            assertEquals("human approval required by policy", result.reason)

            val parsed = json.parseToJsonElement(requestBody).jsonObject
            val input = parsed["input"]!!.jsonObject
            assertEquals("build deployable service", input["goal"]!!.jsonPrimitive.content)
            assertEquals("deploy_staging", input["tool_calls"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(321, input["token_usage"]!!.jsonPrimitive.content.toInt())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `policy check falls back to allow when opa is unavailable and fail mode is open`() {
        val policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:1", failMode = PolicyFailMode.OPEN)

        val result = policyCheck.check(
            runId = "run-2",
            input = PolicyInput(goal = "any"),
        )

        assertTrue(result.allowed)
        assertFalse(result.requireHumanApproval)
        assertEquals("fallback", result.source)
    }

    @Test
    fun `policy check denies when opa is unavailable and fail mode is closed`() {
        val policyCheck = PolicyCheck(opaBaseUrl = "http://127.0.0.1:1", failMode = PolicyFailMode.CLOSED)

        val result = policyCheck.check(
            runId = "run-3",
            input = PolicyInput(goal = "any"),
        )

        assertFalse(result.allowed)
        assertFalse(result.requireHumanApproval)
        assertEquals("fallback", result.source)
        assertTrue(result.reason.contains("fail-closed"))
    }
}
