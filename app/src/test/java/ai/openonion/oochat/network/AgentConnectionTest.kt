package ai.openonion.oochat.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [AgentConnection.Companion.reconnectBackoffMs] in isolation — it
 * was pulled out of [AgentConnection.attemptReconnect] specifically so this
 * calculation could be tested without the rest of the class.
 *
 * Everything else on [AgentConnection] stays out of plain-JVM reach here:
 * `connect()` calls `keyManager.loadOrGenerate()`, which needs a real
 * Android Keystore (`EncryptedSharedPreferences`) and a working
 * [android.content.Context] — neither exists without Robolectric or an
 * instrumented/on-device run, and this project has set up neither (see
 * [ai.openonion.oochat.crypto.KeyManagerTest] for the identical
 * limitation on `KeyManager`). Every other piece of connection state
 * (`isConnectionReady`, `webSocket`, `pendingMessages`) only becomes
 * observable after a successful `connect()`, so it's blocked the same way.
 */
class AgentConnectionTest {

    @Test
    fun `the first five attempts double each time, starting at 2 seconds`() {
        val expected = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)

        expected.forEachIndexed { index, expectedDelay ->
            assertEquals(expectedDelay, AgentConnection.reconnectBackoffMs(index + 1))
        }
    }

    @Test
    fun `the attempt right past the fifth stays capped at 32 seconds`() {
        // reconnectBackoffMs only ever gets called from attemptReconnect()
        // with attemptNumber in 1..MAX_RECONNECT_ATTEMPTS (5) — the caller
        // stops reconnecting once it hits that cap, so this only checks one
        // step past the realistic range. The `1 shl n` term would wrap at
        // shift widths >= 32, but no caller ever reaches that far.
        assertEquals(32_000L, AgentConnection.reconnectBackoffMs(6))
    }

    @Test
    fun `delays never plateau or drop before the cap kicks in`() {
        val sequence = (1..5).map(AgentConnection::reconnectBackoffMs)

        sequence.zipWithNext().forEach { (earlier, later) ->
            assertTrue("expected $later > $earlier in sequence $sequence", later > earlier)
        }
    }
}
