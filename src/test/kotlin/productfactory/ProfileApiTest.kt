package productfactory

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import productfactory.profile.InMemoryProfileStore
import productfactory.profile.ProfileStoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `profile API upsert and get returns persisted embedding and rules`() = testApplication {
        application {
            module(
                profileStore = InMemoryProfileStore(),
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 8,
                ),
            )
        }

        val upsert = client.put("/factory/profiles/alice") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "acme",
                  "embedding": [0.1, 0.2, 0.3],
                  "embeddingModel": "text-embedding-3-small",
                  "rules": [
                    {"type": "like", "value": "clean-layout"},
                    {"type": "avoid", "value": "autoplay", "weight": 0.8}
                  ],
                  "consent": {"profileStorage": true, "source": "manual"}
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, upsert.status)

        val get = client.get("/factory/profiles/alice?tenantId=acme")
        assertEquals(HttpStatusCode.OK, get.status)
        val payload = json.parseToJsonElement(get.bodyAsText()).jsonObject
        assertEquals("acme", payload["tenantId"]?.jsonPrimitive?.content)
        assertEquals("alice", payload["profileId"]?.jsonPrimitive?.content)
        assertEquals(2, payload["rules"]?.jsonArray?.size)
        assertEquals(3, payload["embedding"]?.jsonArray?.size)

        val missingTenant = client.get("/factory/profiles/alice?tenantId=other")
        assertEquals(HttpStatusCode.NotFound, missingTenant.status)
    }

    @Test
    fun `profile API rejects write without consent when consent is required`() = testApplication {
        application {
            module(
                profileStore = InMemoryProfileStore(),
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 8,
                ),
            )
        }

        val response = client.put("/factory/profiles/alice") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "acme",
                  "embedding": [0.1, 0.2, 0.3],
                  "rules": [],
                  "consent": {"profileStorage": false}
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("CONSENT_REQUIRED"))
    }

    @Test
    fun `profile API delete revokes consent and removes profile`() = testApplication {
        application {
            module(
                profileStore = InMemoryProfileStore(),
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 8,
                ),
            )
        }

        client.put("/factory/profiles/session-1") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "acme",
                  "embedding": [0.1, 0.2, 0.3],
                  "rules": [{"type": "require", "value": "a11y"}],
                  "consent": {"profileStorage": true}
                }
                """.trimIndent(),
            )
        }

        val delete = client.delete("/factory/profiles/session-1") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"acme","reason":"withdrawn"}""")
        }
        assertEquals(HttpStatusCode.OK, delete.status)
        assertTrue(delete.bodyAsText().contains("\"status\":\"revoked\""))

        val getAfterDelete = client.get("/factory/profiles/session-1?tenantId=acme")
        assertEquals(HttpStatusCode.NotFound, getAfterDelete.status)
    }

    @Test
    fun `profile API reset clears embedding and rules`() = testApplication {
        application {
            module(
                profileStore = InMemoryProfileStore(),
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 8,
                ),
            )
        }

        client.put("/factory/profiles/alice") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "acme",
                  "embedding": [0.1, 0.2, 0.3],
                  "rules": [{"type": "like", "value": "guided flow"}],
                  "consent": {"profileStorage": true}
                }
                """.trimIndent(),
            )
        }

        val reset = client.post("/factory/profiles/alice/reset") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"acme","reason":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, reset.status)
        val payload = json.parseToJsonElement(reset.bodyAsText()).jsonObject
        assertEquals(0, payload["embedding"]?.jsonArray?.size ?: 0)
        assertEquals(0, payload["rules"]?.jsonArray?.size ?: 0)
    }

    @Test
    fun `profile API incognito toggle updates flag`() = testApplication {
        application {
            module(
                profileStore = InMemoryProfileStore(),
                profileStoreConfig = ProfileStoreConfig(
                    enabled = true,
                    requireConsent = true,
                    embeddingDim = 3,
                    maxRules = 8,
                ),
            )
        }

        client.put("/factory/profiles/alice") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "tenantId": "acme",
                  "embedding": [0.1, 0.2, 0.3],
                  "rules": [{"type": "like", "value": "guided flow"}],
                  "consent": {"profileStorage": true}
                }
                """.trimIndent(),
            )
        }

        val enable = client.post("/factory/profiles/alice/incognito") {
            contentType(ContentType.Application.Json)
            setBody("""{"tenantId":"acme","enabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, enable.status)
        assertTrue(enable.bodyAsText().contains("\"incognito\":true"))
    }
}
