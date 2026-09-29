package ai.openonion.oochat.data.repository

import ai.openonion.oochat.crypto.KeyManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AccountRepositoryImpl] against a real [MockWebServer] —
 * `baseUrl` is already a constructor param, so no production seam was
 * needed to point it at a local server instead of `oo.openonion.ai`.
 *
 * [AccountRepositoryImpl.authenticate] calls [KeyManager.sign], which needs
 * the native libsodium binary (blocked on a host JVM regardless of
 * Robolectric, see [ai.openonion.oochat.crypto.KeyManagerTest]), so
 * every test here seeds the private `cachedToken` field via reflection
 * instead of going through the real auth call — [AccountRepositoryImpl.loadAccount]
 * skips `authenticate()` entirely whenever a cached token exists and
 * `forceReauth` is false, so this exercises exactly the same `fetchProfile()`
 * code path a real cached-token call would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: AccountRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keyManager = KeyManager(ApplicationProvider.getApplicationContext())
        repository = AccountRepositoryImpl(keyManager, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun seedCachedToken(token: String) {
        val field = AccountRepositoryImpl::class.java.getDeclaredField("cachedToken")
        field.isAccessible = true
        field.set(repository, token)
    }

    private fun readCachedToken(): String? {
        val field = AccountRepositoryImpl::class.java.getDeclaredField("cachedToken")
        field.isAccessible = true
        return field.get(repository) as String?
    }

    @Test
    fun `loadAccount with a cached token fetches the profile without re-authenticating`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(
            MockResponse().setBody(
                """{"public_key":"0xabc","credits_usd":10.5,"total_cost_usd":2.5,"balance_usd":8.0}"""
            ).setResponseCode(200)
        )

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals("cached-token-123", snapshot.apiKey)
        assertEquals("0xabc", snapshot.profile.publicKey)
        assertEquals(10.5, snapshot.profile.creditsUsd, 0.001)
        assertEquals(8.0, snapshot.profile.balanceUsd, 0.001)

        val recorded = server.takeRequest()
        assertEquals("Bearer cached-token-123", recorded.getHeader("Authorization"))
        assertEquals("/api/v1/auth/me", recorded.path)
    }

    @Test
    fun `loadAccount fails and clears the cached token when the profile request errors`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isFailure)
        assertNull("a failed load must clear the cached token", readCachedToken())
    }

    @Test
    fun `invalidate clears the cached token`() {
        seedCachedToken("old-identity-token")

        repository.invalidate()

        assertNull("invalidate must forget the identity-scoped JWT", readCachedToken())
    }

    @Test
    fun `after invalidate the old identity's bearer is never sent to the server`() = runTest {
        seedCachedToken("old-identity-token")
        repository.invalidate()

        // With the cache invalidated, loadAccount must re-authenticate as
        // the *current* identity instead of reusing the old JWT. On the host
        // JVM the native Ed25519 signer is unavailable (see class doc), so
        // the re-auth attempt dies before any HTTP — which is exactly the
        // point: no request carrying the stale bearer may ever go out.
        runCatching { repository.loadAccount() }

        assertEquals(
            "no request (in particular none with the old bearer) may reach the server",
            0,
            server.requestCount
        )
    }

    @Test
    fun `loadAccount fails when the profile response body is malformed`() = runTest {
        seedCachedToken("cached-token-123")
        server.enqueue(MockResponse().setBody("not json").setResponseCode(200))

        val result = repository.loadAccount(forceReauth = false)

        assertTrue(result.isFailure)
    }
}
