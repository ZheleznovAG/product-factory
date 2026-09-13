package productfactory.profile

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStoreTest {
    @Test
    fun `file profile store persists embedding rules and revision`() {
        val dir = createTempDirectory(prefix = "profile-store-")
        val store = FileProfileStore(dir)

        val first = store.upsert(
            tenantId = "acme",
            profileId = "alice",
            embedding = listOf(0.1f, 0.2f, 0.3f),
            embeddingModel = "text-embedding-3-small",
            rules = listOf(
                PreferenceRule(type = PreferenceRuleType.LIKE, value = "minimal-ui"),
                PreferenceRule(type = PreferenceRuleType.DISLIKE, value = "modal-overload"),
            ),
            consent = ProfileConsent(profileStorage = true, source = "manual"),
        )
        assertEquals(1L, first.revision)
        assertEquals(3, first.embedding.size)

        val second = store.upsert(
            tenantId = "acme",
            profileId = "alice",
            embedding = listOf(0.3f, 0.2f, 0.1f),
            embeddingModel = "text-embedding-3-small",
            rules = listOf(PreferenceRule(type = PreferenceRuleType.REQUIRE, value = "a11y")),
            consent = ProfileConsent(profileStorage = true, source = "manual"),
        )
        assertEquals(2L, second.revision)
        assertEquals("a11y", second.rules.single().value)

        val reloaded = FileProfileStore(dir).get("acme", "alice")
        assertNotNull(reloaded)
        assertEquals(2L, reloaded.revision)
        assertEquals(3, reloaded.embedding.size)
        assertEquals("a11y", reloaded.rules.single().value)
    }

    @Test
    fun `file profile store revoke removes persisted profile`() {
        val dir = createTempDirectory(prefix = "profile-store-")
        val store = FileProfileStore(dir)
        store.upsert(
            tenantId = "acme",
            profileId = "session-1",
            embedding = emptyList(),
            embeddingModel = null,
            rules = emptyList(),
            consent = ProfileConsent(profileStorage = true),
        )

        val revocation = store.revoke("acme", "session-1", "user_request")
        assertNotNull(revocation)
        assertTrue(revocation.revokedAt.isNotBlank())
        assertNull(store.get("acme", "session-1"))
    }

    @Test
    fun `file profile store reset clears embedding and rules but keeps profile`() {
        val dir = createTempDirectory(prefix = "profile-store-")
        val store = FileProfileStore(dir)
        store.upsert(
            tenantId = "acme",
            profileId = "alice",
            embedding = listOf(0.1f, 0.2f, 0.3f),
            embeddingModel = "text-embedding-3-small",
            rules = listOf(PreferenceRule(type = PreferenceRuleType.LIKE, value = "guided flow")),
            consent = ProfileConsent(profileStorage = true),
        )

        val reset = store.reset("acme", "alice")
        assertNotNull(reset)
        assertTrue(reset.embedding.isEmpty())
        assertTrue(reset.rules.isEmpty())
        assertEquals(2L, reset.revision)
    }

    @Test
    fun `file profile store setIncognito toggles profile mode`() {
        val dir = createTempDirectory(prefix = "profile-store-")
        val store = FileProfileStore(dir)
        store.upsert(
            tenantId = "acme",
            profileId = "alice",
            embedding = emptyList(),
            embeddingModel = null,
            rules = emptyList(),
            consent = ProfileConsent(profileStorage = true),
        )

        val enabled = store.setIncognito("acme", "alice", enabled = true)
        assertNotNull(enabled)
        assertTrue(enabled.incognito)
        val disabled = store.setIncognito("acme", "alice", enabled = false)
        assertNotNull(disabled)
        assertEquals(false, disabled.incognito)
        assertEquals(3L, disabled.revision)
    }
}
