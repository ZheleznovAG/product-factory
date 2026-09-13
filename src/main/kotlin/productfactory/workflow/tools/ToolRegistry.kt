package productfactory.workflow.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Запись о tool из реестра (single source of truth).
 * Полная схема — [contracts/tools.schema.json](../../../contracts/tools.schema.json).
 * [allowed_secrets] — имена env-переменных (например GITHUB_TOKEN), которые этот tool может получать.
 */
data class ToolRegistryEntry(
    val name: String,
    val risk_tier: String,
    val requires_human_approval: Boolean,
    val allowed_secrets: List<String> = emptyList(),
)

/**
 * Реестр tools: allowlist и метаданные для policy/executor.
 * Загружается из contracts/tools.registry.json или FACTORY_TOOLS_REGISTRY_PATH.
 */
data class ToolRegistry(
    val registry_version: String,
    val entries: List<ToolRegistryEntry>,
) {
    fun allowedNames(): Set<String> = entries.map { it.name }.toSet()

    fun getRiskTier(toolName: String): String? =
        entries.find { it.name == toolName }?.risk_tier

    fun requiresApproval(toolName: String): Boolean =
        entries.find { it.name == toolName }?.requires_human_approval ?: false

    /** Список имён env-секретов, разрешённых для данного tool. */
    fun allowedSecrets(toolName: String): Set<String> =
        entries.find { it.name == toolName }?.allowed_secrets?.toSet() ?: emptySet()
}

/**
 * Загружает реестр из JSON-файла. При отсутствии файла или ошибке парсинга возвращает null.
 */
fun loadToolRegistry(path: Path): ToolRegistry? {
    if (!Files.exists(path) || !Files.isRegularFile(path)) return null
    return try {
        val content = Files.readString(path)
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(content).jsonObject
        val version = root["registry_version"]?.jsonPrimitive?.content ?: "0.1.0"
        val toolsArray = root["tools"]?.jsonArray ?: return null
        val entries = mutableListOf<ToolRegistryEntry>()
        for (i in 0 until toolsArray.size) {
            val obj = toolsArray[i].jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            val riskTier = obj["risk_tier"]?.jsonPrimitive?.content ?: "write_limited"
            val requiresApproval = obj["requires_human_approval"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val allowedSecrets = obj["allowed_secrets"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            entries += ToolRegistryEntry(name = name, risk_tier = riskTier, requires_human_approval = requiresApproval, allowed_secrets = allowedSecrets)
        }
        ToolRegistry(registry_version = version, entries = entries)
    } catch (_: Exception) {
        null
    }
}
