package productfactory.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond

private val PROTECTED_PATH_PREFIXES = listOf("/factory", "/intent", "/experience")

enum class ApiAuthMode {
    DISABLED,
    OIDC_JWT,
    ;

    companion object {
        fun parse(value: String?): ApiAuthMode {
            val normalized = value?.trim()?.lowercase()
            return if (normalized == "oidc_jwt") OIDC_JWT else DISABLED
        }
    }
}

data class ApiAuthConfig(
    val mode: ApiAuthMode = ApiAuthMode.DISABLED,
    val issuer: String? = null,
    val audience: String? = null,
    val claimTenantId: String = "tenant_id",
    val claimSubject: String = "sub",
    val claimRoles: String = "roles",
    val hs256Secret: String? = null,
) {
    fun enabled(): Boolean = mode != ApiAuthMode.DISABLED

    fun validate(): ApiAuthConfig {
        if (mode == ApiAuthMode.DISABLED) return this
        require(!issuer.isNullOrBlank()) { "AUTH_JWT_ISSUER must be set when AUTH_MODE=oidc_jwt" }
        require(!audience.isNullOrBlank()) { "AUTH_JWT_AUDIENCE must be set when AUTH_MODE=oidc_jwt" }
        require(!hs256Secret.isNullOrBlank()) { "AUTH_JWT_HS256_SECRET must be set when AUTH_MODE=oidc_jwt" }
        return this
    }

    companion object {
        fun fromEnv(): ApiAuthConfig = ApiAuthConfig(
            mode = ApiAuthMode.parse(System.getenv("AUTH_MODE")),
            issuer = System.getenv("AUTH_JWT_ISSUER")?.trim(),
            audience = System.getenv("AUTH_JWT_AUDIENCE")?.trim(),
            claimTenantId = System.getenv("AUTH_JWT_CLAIM_TENANT_ID")?.trim()?.takeIf { it.isNotBlank() } ?: "tenant_id",
            claimSubject = System.getenv("AUTH_JWT_CLAIM_SUBJECT")?.trim()?.takeIf { it.isNotBlank() } ?: "sub",
            claimRoles = System.getenv("AUTH_JWT_CLAIM_ROLES")?.trim()?.takeIf { it.isNotBlank() } ?: "roles",
            hs256Secret = System.getenv("AUTH_JWT_HS256_SECRET")?.trim(),
        ).validate()
    }
}

private data class ApiJwtVerifier(
    val issuer: String,
    val audience: String,
    val claimTenantId: String,
    val claimSubject: String,
    val claimRoles: String,
    val hs256Secret: String,
) {
    private val verifier = JWT.require(Algorithm.HMAC256(hs256Secret))
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun verify(token: String): Boolean {
        return try {
            val jwt = verifier.verify(token)
            val tenantId = jwt.getClaim(claimTenantId).asString()?.trim()
            val subject = jwt.getClaim(claimSubject).asString()?.trim()
            // Проверяем, что roles claim корректно парсится (строка или массив), даже если не обязательный.
            val rolesClaim = jwt.getClaim(claimRoles)
            val roles = runCatching { rolesClaim.asList(String::class.java) }.getOrNull()
                ?: rolesClaim.asString()?.let(::listOf)
                ?: emptyList()
            tenantId.isNullOrBlank().not() && subject.isNullOrBlank().not() && roles.none { it.isBlank() }
        } catch (_: JWTVerificationException) {
            false
        }
    }
}

private fun isProtectedPath(path: String): Boolean {
    val normalized = if (path.startsWith("/")) path else "/$path"
    return PROTECTED_PATH_PREFIXES.any { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun extractBearerToken(headerValue: String?): String? {
    if (headerValue.isNullOrBlank()) return null
    val parsed = runCatching { parseAuthorizationHeader(headerValue) }.getOrNull() ?: return null
    return (parsed as? HttpAuthHeader.Single)
        ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
        ?.blob
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

fun Application.installApiAuthGuard(config: ApiAuthConfig) {
    if (!config.enabled()) return
    val authConfig = config.validate()
    val verifier = ApiJwtVerifier(
        issuer = authConfig.issuer!!,
        audience = authConfig.audience!!,
        claimTenantId = authConfig.claimTenantId,
        claimSubject = authConfig.claimSubject,
        claimRoles = authConfig.claimRoles,
        hs256Secret = authConfig.hs256Secret!!,
    )

    intercept(ApplicationCallPipeline.Plugins) {
        if (!isProtectedPath(call.request.path())) return@intercept
        val token = extractBearerToken(call.request.headers["Authorization"])
        if (token == null || !verifier.verify(token)) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            return@intercept
        }
    }
}
