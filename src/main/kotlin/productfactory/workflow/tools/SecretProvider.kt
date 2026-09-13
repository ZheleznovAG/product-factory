package productfactory.workflow.tools

/**
 * Выдаёт секреты только тем tools, которым они разрешены по реестру (allowed_secrets).
 * Снижает риск утечки: секрет не передаётся в tool, который не в allowlist.
 */
interface SecretProvider {
    /**
     * Возвращает значение секрета (env) для данного tool, если он в списке allowed_secrets у tool.
     * Иначе null.
     */
    fun getSecret(toolName: String, secretEnvName: String): String?
}

/**
 * Читает секреты из окружения; выдаёт только если [toolRegistry] разрешает этот секрет для [toolName].
 */
class EnvSecretProvider(
    private val toolRegistry: ToolRegistry?,
) : SecretProvider {
    override fun getSecret(toolName: String, secretEnvName: String): String? {
        if (toolRegistry == null) return System.getenv(secretEnvName)?.trim()?.takeIf { it.isNotBlank() }
        if (secretEnvName !in toolRegistry.allowedSecrets(toolName)) return null
        return System.getenv(secretEnvName)?.trim()?.takeIf { it.isNotBlank() }
    }
}
