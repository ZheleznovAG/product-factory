package productfactory.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val PROFILE_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,62}$")

@Serializable
enum class PreferenceRuleType {
    LIKE,
    DISLIKE,
    REQUIRE,
    AVOID,
}

@Serializable
data class PreferenceRule(
    val type: PreferenceRuleType,
    val value: String,
    val weight: Double? = null,
)

@Serializable
data class ProfileConsent(
    val profileStorage: Boolean,
    val cloudBackup: Boolean = false,
    val source: String? = null,
    val grantedAt: String? = null,
)

@Serializable
data class PreferenceProfile(
    val tenantId: String,
    val profileId: String,
    val embedding: List<Float> = emptyList(),
    val embeddingModel: String? = null,
    val rules: List<PreferenceRule> = emptyList(),
    val incognito: Boolean = false,
    val consent: ProfileConsent,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ProfileConsentRevocation(
    val revokedAt: String,
    val reason: String? = null,
)

interface ProfileStore {
    fun upsert(
        tenantId: String,
        profileId: String,
        embedding: List<Float>,
        embeddingModel: String?,
        rules: List<PreferenceRule>,
        consent: ProfileConsent,
    ): PreferenceProfile

    fun get(tenantId: String, profileId: String): PreferenceProfile?

    fun revoke(tenantId: String, profileId: String, reason: String? = null): ProfileConsentRevocation?

    fun reset(tenantId: String, profileId: String): PreferenceProfile?

    fun setIncognito(tenantId: String, profileId: String, enabled: Boolean): PreferenceProfile?
}

class InMemoryProfileStore : ProfileStore {
    private val profiles = ConcurrentHashMap<String, PreferenceProfile>()

    override fun upsert(
        tenantId: String,
        profileId: String,
        embedding: List<Float>,
        embeddingModel: String?,
        rules: List<PreferenceRule>,
        consent: ProfileConsent,
    ): PreferenceProfile {
        val now = Instant.now().toString()
        val key = key(tenantId, profileId)
        val existing = profiles[key]
        val createdAt = existing?.createdAt ?: now
        val revision = (existing?.revision ?: 0L) + 1L
        val profile = PreferenceProfile(
            tenantId = tenantId,
            profileId = profileId,
            embedding = embedding,
            embeddingModel = embeddingModel,
            rules = rules,
            incognito = existing?.incognito ?: false,
            consent = consent,
            revision = revision,
            createdAt = createdAt,
            updatedAt = now,
        )
        profiles[key] = profile
        return profile
    }

    override fun get(tenantId: String, profileId: String): PreferenceProfile? = profiles[key(tenantId, profileId)]

    override fun revoke(tenantId: String, profileId: String, reason: String?): ProfileConsentRevocation? {
        val removed = profiles.remove(key(tenantId, profileId)) ?: return null
        return ProfileConsentRevocation(revokedAt = removed.updatedAt, reason = reason)
    }

    override fun reset(tenantId: String, profileId: String): PreferenceProfile? {
        val existing = profiles[key(tenantId, profileId)] ?: return null
        return upsert(
            tenantId = tenantId,
            profileId = profileId,
            embedding = emptyList(),
            embeddingModel = null,
            rules = emptyList(),
            consent = existing.consent,
        )
    }

    override fun setIncognito(tenantId: String, profileId: String, enabled: Boolean): PreferenceProfile? {
        val key = key(tenantId, profileId)
        val existing = profiles[key] ?: return null
        val updated = existing.copy(
            incognito = enabled,
            revision = existing.revision + 1L,
            updatedAt = Instant.now().toString(),
        )
        profiles[key] = updated
        return updated
    }

    private fun key(tenantId: String, profileId: String): String = "$tenantId::$profileId"
}

class FileProfileStore(
    directory: Path = Path.of(System.getenv("PROFILE_STORE_DIR") ?: "profiles"),
) : ProfileStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root = directory.toAbsolutePath().normalize()
    private val lock = Any()

    init {
        root.createDirectories()
    }

    override fun upsert(
        tenantId: String,
        profileId: String,
        embedding: List<Float>,
        embeddingModel: String?,
        rules: List<PreferenceRule>,
        consent: ProfileConsent,
    ): PreferenceProfile {
        synchronized(lock) {
            val existing = readProfile(tenantId, profileId)
            val now = Instant.now().toString()
            val profile = PreferenceProfile(
                tenantId = tenantId,
                profileId = profileId,
                embedding = embedding,
                embeddingModel = embeddingModel,
                rules = rules,
                incognito = existing?.incognito ?: false,
                consent = consent,
                revision = (existing?.revision ?: 0L) + 1L,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            writeProfile(tenantId, profileId, profile)
            return profile
        }
    }

    override fun get(tenantId: String, profileId: String): PreferenceProfile? {
        synchronized(lock) {
            return readProfile(tenantId, profileId)
        }
    }

    override fun revoke(tenantId: String, profileId: String, reason: String?): ProfileConsentRevocation? {
        synchronized(lock) {
            val path = pathFor(tenantId, profileId)
            if (!path.exists()) {
                return null
            }
            Files.delete(path)
            return ProfileConsentRevocation(
                revokedAt = Instant.now().toString(),
                reason = reason,
            )
        }
    }

    override fun reset(tenantId: String, profileId: String): PreferenceProfile? {
        synchronized(lock) {
            val existing = readProfile(tenantId, profileId) ?: return null
            val now = Instant.now().toString()
            val updated = existing.copy(
                embedding = emptyList(),
                embeddingModel = null,
                rules = emptyList(),
                revision = existing.revision + 1L,
                updatedAt = now,
            )
            writeProfile(tenantId, profileId, updated)
            return updated
        }
    }

    override fun setIncognito(tenantId: String, profileId: String, enabled: Boolean): PreferenceProfile? {
        synchronized(lock) {
            val existing = readProfile(tenantId, profileId) ?: return null
            val updated = existing.copy(
                incognito = enabled,
                revision = existing.revision + 1L,
                updatedAt = Instant.now().toString(),
            )
            writeProfile(tenantId, profileId, updated)
            return updated
        }
    }

    private fun readProfile(tenantId: String, profileId: String): PreferenceProfile? {
        val path = pathFor(tenantId, profileId)
        if (!path.exists()) {
            return null
        }
        return runCatching {
            json.decodeFromString(PreferenceProfile.serializer(), path.readText())
        }.getOrNull()
    }

    private fun writeProfile(tenantId: String, profileId: String, profile: PreferenceProfile) {
        pathFor(tenantId, profileId).writeText(json.encodeToString(profile))
    }

    private fun pathFor(tenantId: String, profileId: String): Path {
        val normalizedTenant = normalizeId(tenantId)
        val normalizedProfile = normalizeId(profileId)
        val path = root.resolve("${normalizedTenant}__${normalizedProfile}.json").normalize()
        require(path.startsWith(root)) { "Profile file path escapes profile directory" }
        Files.createDirectories(root)
        return path
    }

    private fun normalizeId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
}

data class ProfileStoreConfig(
    val enabled: Boolean,
    val requireConsent: Boolean,
    val embeddingDim: Int,
    val maxRules: Int,
) {
    companion object {
        fun fromEnv(): ProfileStoreConfig = ProfileStoreConfig(
            enabled = parseBooleanEnv("PROFILE_STORE_ENABLED", default = false),
            requireConsent = parseBooleanEnv("PROFILE_REQUIRE_CONSENT", default = true),
            embeddingDim = System.getenv("PROFILE_EMBEDDING_DIM")?.toIntOrNull()?.coerceAtLeast(8) ?: 1536,
            maxRules = System.getenv("PROFILE_MAX_RULES")?.toIntOrNull()?.coerceAtLeast(1) ?: 64,
        )
    }
}

private fun parseBooleanEnv(name: String, default: Boolean): Boolean {
    val raw = System.getenv(name)?.trim()?.lowercase() ?: return default
    return when (raw) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> default
    }
}

fun requireValidProfileId(profileId: String): String {
    val normalized = profileId.trim().lowercase()
    require(PROFILE_ID_REGEX.matches(normalized)) { "profileId must match ${PROFILE_ID_REGEX.pattern}" }
    return normalized
}
