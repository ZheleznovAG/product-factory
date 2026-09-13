package productfactory.neural

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

data class NeuralProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String? = null,
    val priority: Int = 100,
    val timeout: Duration? = null,
)

data class NeuralFallbackMatrix(
    val providers: List<NeuralProviderConfig> = emptyList(),
) {
    fun isConfigured(): Boolean = providers.isNotEmpty()

    fun primaryProvider(): NeuralProviderConfig? = providers.firstOrNull()

    companion object {
        fun fromEnvOrYaml(env: Map<String, String> = System.getenv()): NeuralFallbackMatrix {
            val indexed = parseIndexedProvidersFromEnv(env)
            if (indexed.isNotEmpty()) return NeuralFallbackMatrix(indexed.sortedByPriority())

            val primarySecondary = parsePrimarySecondaryFromEnv(env)
            if (primarySecondary.isNotEmpty()) return NeuralFallbackMatrix(primarySecondary.sortedByPriority())

            val fromYaml = parseFromYaml(env)
            if (fromYaml.isNotEmpty()) return NeuralFallbackMatrix(fromYaml.sortedByPriority())

            val legacy = parseLegacySingleProviderFromEnv(env)
            if (legacy != null) return NeuralFallbackMatrix(listOf(legacy))

            return NeuralFallbackMatrix()
        }

        fun single(baseUrl: String?, apiKey: String?, model: String? = null): NeuralFallbackMatrix {
            val normalizedUrl = baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return NeuralFallbackMatrix()
            return NeuralFallbackMatrix(
                providers = listOf(
                    NeuralProviderConfig(
                        name = "primary",
                        baseUrl = normalizedUrl,
                        apiKey = apiKey?.trim()?.takeIf { it.isNotBlank() },
                        model = model?.trim()?.takeIf { it.isNotBlank() },
                        priority = 100,
                    ),
                ),
            )
        }

        private fun parseIndexedProvidersFromEnv(env: Map<String, String>): List<NeuralProviderConfig> {
            val providers = mutableListOf<NeuralProviderConfig>()
            for (index in 1..16) {
                val prefix = "NEURAL_SERVICE_PROVIDER_${index}_"
                val url = env.trimmed("${prefix}URL") ?: continue
                val name = env.trimmed("${prefix}NAME") ?: "provider-$index"
                val priority = env.trimmed("${prefix}PRIORITY")?.toIntOrNull() ?: index * 100
                val timeout = env.trimmed("${prefix}TIMEOUT_SECONDS")?.toLongOrNull()?.takeIf { it > 0L }?.let(Duration::ofSeconds)
                val apiKey = resolveApiKey(
                    direct = env.trimmed("${prefix}API_KEY"),
                    envRef = env.trimmed("${prefix}API_KEY_ENV"),
                    env = env,
                )
                val model = env.trimmed("${prefix}MODEL") ?: env.trimmed("NEURAL_SERVICE_MODEL")
                providers += NeuralProviderConfig(
                    name = name,
                    baseUrl = url,
                    apiKey = apiKey,
                    model = model,
                    priority = priority,
                    timeout = timeout,
                )
            }
            return providers
        }

        private fun parsePrimarySecondaryFromEnv(env: Map<String, String>): List<NeuralProviderConfig> {
            val providers = mutableListOf<NeuralProviderConfig>()
            val primary = parseNamedProviderFromEnv(env, "PRIMARY", defaultPriority = 100, fallbackName = "primary")
                ?: if (env.trimmed("NEURAL_SERVICE_URL_SECONDARY") != null) parseLegacySingleProviderFromEnv(env) else null
            val secondary = parseNamedProviderFromEnv(env, "SECONDARY", defaultPriority = 200, fallbackName = "secondary")
            if (primary != null) providers += primary
            if (secondary != null) providers += secondary
            return providers
        }

        private fun parseNamedProviderFromEnv(
            env: Map<String, String>,
            suffix: String,
            defaultPriority: Int,
            fallbackName: String,
        ): NeuralProviderConfig? {
            val url = env.trimmed("NEURAL_SERVICE_URL_$suffix") ?: return null
            val name = env.trimmed("NEURAL_SERVICE_NAME_$suffix") ?: fallbackName
            val priority = env.trimmed("NEURAL_SERVICE_PRIORITY_$suffix")?.toIntOrNull() ?: defaultPriority
            val timeout = env.trimmed("NEURAL_SERVICE_TIMEOUT_SECONDS_$suffix")?.toLongOrNull()?.takeIf { it > 0L }?.let(Duration::ofSeconds)
            val apiKey = resolveApiKey(
                direct = env.trimmed("NEURAL_SERVICE_API_KEY_$suffix"),
                envRef = env.trimmed("NEURAL_SERVICE_API_KEY_ENV_$suffix"),
                env = env,
            )
            val model = env.trimmed("NEURAL_SERVICE_MODEL_$suffix") ?: env.trimmed("NEURAL_SERVICE_MODEL")
            return NeuralProviderConfig(
                name = name,
                baseUrl = url,
                apiKey = apiKey,
                model = model,
                priority = priority,
                timeout = timeout,
            )
        }

        private fun parseLegacySingleProviderFromEnv(env: Map<String, String>): NeuralProviderConfig? {
            val url = env.trimmed("NEURAL_SERVICE_URL") ?: return null
            val apiKey = resolveApiKey(
                direct = env.trimmed("NEURAL_SERVICE_API_KEY"),
                envRef = env.trimmed("NEURAL_SERVICE_API_KEY_ENV"),
                env = env,
            )
            val timeout = env.trimmed("NEURAL_SERVICE_TIMEOUT_SECONDS")?.toLongOrNull()?.takeIf { it > 0L }?.let(Duration::ofSeconds)
            val model = env.trimmed("NEURAL_SERVICE_MODEL")
            return NeuralProviderConfig(
                name = "primary",
                baseUrl = url,
                apiKey = apiKey,
                model = model,
                priority = 100,
                timeout = timeout,
            )
        }

        private fun parseFromYaml(env: Map<String, String>): List<NeuralProviderConfig> {
            val pathRaw = env.trimmed("NEURAL_SERVICE_FALLBACK_MATRIX_PATH") ?: return emptyList()
            val path = Path.of(pathRaw)
            if (!path.exists()) return emptyList()

            val mapper = ObjectMapper(YAMLFactory())
                .registerKotlinModule()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val yaml = runCatching { mapper.readValue<NeuralFallbackMatrixYaml>(path.toFile()) }.getOrNull() ?: return emptyList()

            return yaml.providers.mapNotNull { provider ->
                val url = provider.baseUrl?.trim()?.takeIf { it.isNotBlank() }
                    ?: provider.url?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = provider.name?.trim()?.takeIf { it.isNotBlank() } ?: "provider"
                val timeout = provider.timeoutSeconds?.takeIf { it > 0L }?.let(Duration::ofSeconds)
                val apiKey = resolveApiKey(
                    direct = provider.apiKey?.trim()?.takeIf { it.isNotBlank() },
                    envRef = provider.apiKeyEnv?.trim()?.takeIf { it.isNotBlank() },
                    env = env,
                )
                NeuralProviderConfig(
                    name = name,
                    baseUrl = url,
                    apiKey = apiKey,
                    model = provider.model?.trim()?.takeIf { it.isNotBlank() },
                    priority = provider.priority ?: 100,
                    timeout = timeout,
                )
            }
        }

        private fun resolveApiKey(direct: String?, envRef: String?, env: Map<String, String>): String? {
            if (!direct.isNullOrBlank()) return direct
            val keyName = envRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return env.trimmed(keyName)
        }

        private fun Map<String, String>.trimmed(key: String): String? =
            this[key]?.trim()?.takeIf { it.isNotBlank() }

        private fun List<NeuralProviderConfig>.sortedByPriority(): List<NeuralProviderConfig> =
            this.withIndex()
                .sortedWith(compareBy<IndexedValue<NeuralProviderConfig>> { it.value.priority }.thenBy { it.index })
                .map { it.value }
    }
}

private data class NeuralFallbackMatrixYaml(
    @JsonProperty("apiVersion") val apiVersion: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("providers") val providers: List<NeuralProviderYaml> = emptyList(),
)

private data class NeuralProviderYaml(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("base_url") val baseUrl: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("priority") val priority: Int? = null,
    @JsonProperty("timeout_seconds") val timeoutSeconds: Long? = null,
    @JsonProperty("api_key") val apiKey: String? = null,
    @JsonProperty("api_key_env") val apiKeyEnv: String? = null,
    @JsonProperty("model") val model: String? = null,
)
