package productfactory.workflow.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCallRequest(
    @SerialName("toolName")
    val toolName: String,
    @SerialName("idempotencyKey")
    val idempotencyKey: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ToolCallResult(
    val success: Boolean,
    val result: JsonObject = JsonObject(emptyMap()),
    val message: String? = null,
    val replayed: Boolean = false,
)
