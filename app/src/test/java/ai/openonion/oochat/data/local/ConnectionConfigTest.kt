package ai.openonion.oochat.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {
    @Test
    fun defaultConfigUsesConnectOnionRelay() {
        val config = ConnectionConfig.default()

        assertEquals(ConnectionConfig.DEFAULT_RELAY_URL, config.serverUrl)
        assertEquals(ConnectionConfig.DEFAULT_TIMEOUT, config.connectionTimeout)
        assertTrue(config.isValid)
    }

    @Test
    fun defaultConfigHasNoOptionalCredentials() {
        val config = ConnectionConfig.default()

        assertNull(config.apiKey)
        assertNull(config.agentAddress)
        assertNull(config.lastConnected)
    }

    @Test
    fun configWithValidUrlIsValid() {
        val config = ConnectionConfig(serverUrl = "wss://oo.openonion.ai")

        assertTrue(config.isValid)
    }

    @Test
    fun configWithEmptyUrlIsInvalid() {
        val config = ConnectionConfig(serverUrl = "")

        assertFalse(config.isValid)
    }

    @Test
    fun configWithBlankUrlIsInvalid() {
        val config = ConnectionConfig(serverUrl = "   ")

        assertFalse(config.isValid)
    }

    @Test
    fun displayUrlRemovesWssProtocol() {
        val config = ConnectionConfig(serverUrl = "wss://oo.openonion.ai")

        assertEquals("oo.openonion.ai", config.displayUrl)
    }

    @Test
    fun displayUrlRemovesWsProtocol() {
        val config = ConnectionConfig(serverUrl = "ws://localhost:8080")

        assertEquals("localhost:8080", config.displayUrl)
    }

    @Test
    fun displayUrlRemovesHttpProtocol() {
        val config = ConnectionConfig(serverUrl = "http://localhost:3000")

        assertEquals("localhost:3000", config.displayUrl)
    }

    @Test
    fun shortDisplayUrlTruncatesLongValues() {
        val config = ConnectionConfig(serverUrl = "https://very-long-agent-hostname.example.com:8080")

        assertEquals("very-long-agent...ple.com:8080", config.shortDisplayUrl)
    }

    @Test
    fun shortDisplayUrlKeepsShortValues() {
        val config = ConnectionConfig(serverUrl = "https://short.example")

        assertEquals("short.example", config.shortDisplayUrl)
    }

    @Test
    fun configCopyPreservesAndOverridesValues() {
        val config = ConnectionConfig(
            serverUrl = "wss://oo.openonion.ai",
            apiKey = "test-key",
            agentAddress = "test-agent",
            lastConnected = 123L,
            connectionTimeout = 5_000L
        )

        val copy = config.copy(serverUrl = "https://direct.example")

        assertEquals("https://direct.example", copy.serverUrl)
        assertEquals("test-key", copy.apiKey)
        assertEquals("test-agent", copy.agentAddress)
        assertEquals(123L, copy.lastConnected)
        assertEquals(5_000L, copy.connectionTimeout)
    }
}
