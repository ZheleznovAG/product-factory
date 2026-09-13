package productfactory

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import productfactory.auth.ApiAuthConfig
import productfactory.auth.ApiAuthMode
import productfactory.workflow.InMemoryApprovalStore
import productfactory.workflow.InMemoryAskUserStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthSmokeApiTest {
    private val authConfig = ApiAuthConfig(
        mode = ApiAuthMode.OIDC_JWT,
        issuer = "https://iam.example.test/realms/product-factory",
        audience = "product-factory-api",
        hs256Secret = "test-secret",
    )

    @Test
    fun `protected endpoints return 401 without bearer token`() = testApplication {
        application {
            module(
                apiAuthConfig = authConfig,
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
            )
        }

        val factoryResp = client.get("/factory/ui")
        assertEquals(HttpStatusCode.Unauthorized, factoryResp.status)

        val intentResp = client.post("/intent/estimate") {
            contentType(ContentType.Application.Json)
            setBody("""{"apiVersion":"productfactory.io/v1","requestId":"req-auth-1","input":{"query":"test"}}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, intentResp.status)

        val experienceResp = client.post("/experience/generate") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion":"productfactory.io/v1",
                  "requestId":"req-auth-2",
                  "intent":{"outcome":"test","experience":"test"},
                  "generation":{"variants":3}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, experienceResp.status)
    }

    @Test
    fun `valid bearer token passes auth guard`() = testApplication {
        application {
            module(
                apiAuthConfig = authConfig,
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
            )
        }

        val token = JWT.create()
            .withIssuer(authConfig.issuer)
            .withAudience(authConfig.audience)
            .withClaim(authConfig.claimTenantId, "default")
            .withClaim(authConfig.claimSubject, "tester")
            .withArrayClaim(authConfig.claimRoles, arrayOf("factory.operator"))
            .sign(Algorithm.HMAC256(authConfig.hs256Secret))
        val bearer = "Bearer $token"

        val factoryResp = client.get("/factory/ui") {
            header(HttpHeaders.Authorization, bearer)
        }
        assertEquals(HttpStatusCode.OK, factoryResp.status)

        val intentResp = client.post("/intent/estimate") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"apiVersion":"productfactory.io/v1","requestId":"req-auth-ok","input":{"query":"test"}}""")
        }
        assertEquals(HttpStatusCode.OK, intentResp.status)

        val experienceResp = client.post("/experience/generate") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "apiVersion":"productfactory.io/v1",
                  "requestId":"req-auth-ok-2",
                  "intent":{"outcome":"test","experience":"test"},
                  "generation":{"variants":3}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, experienceResp.status)
    }

    @Test
    fun `custom claims mapping from config is applied`() = testApplication {
        val customAuthConfig = ApiAuthConfig(
            mode = ApiAuthMode.OIDC_JWT,
            issuer = "https://iam.example.test/realms/product-factory",
            audience = "product-factory-api",
            claimTenantId = "tenant",
            claimSubject = "user_id",
            claimRoles = "permissions",
            hs256Secret = "test-secret",
        )
        application {
            module(
                apiAuthConfig = customAuthConfig,
                approvalStore = InMemoryApprovalStore(),
                askUserStore = InMemoryAskUserStore(),
            )
        }

        val token = JWT.create()
            .withIssuer(customAuthConfig.issuer)
            .withAudience(customAuthConfig.audience)
            .withClaim(customAuthConfig.claimTenantId, "default")
            .withClaim(customAuthConfig.claimSubject, "tester")
            .withClaim(customAuthConfig.claimRoles, "factory.operator")
            .sign(Algorithm.HMAC256(customAuthConfig.hs256Secret))
        val bearer = "Bearer $token"

        val response = client.get("/factory/ui") {
            header(HttpHeaders.Authorization, bearer)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
