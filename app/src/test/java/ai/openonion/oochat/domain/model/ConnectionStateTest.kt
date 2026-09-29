package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateTest {
    @Test
    fun disconnectedStateIsDisconnectedOnly() {
        val state = ConnectionState.Disconnected

        assertTrue(state.isDisconnected())
        assertFalse(state.isConnecting())
        assertFalse(state.isConnected())
        assertFalse(state.isError())
    }

    @Test
    fun connectingStateIsConnectingOnly() {
        val state = ConnectionState.Connecting

        assertTrue(state.isConnecting())
        assertFalse(state.isDisconnected())
        assertFalse(state.isConnected())
        assertFalse(state.isError())
    }

    @Test
    fun connectedStateIsConnectedWithAddress() {
        val state = ConnectionState.Connected(address = "0xabc")

        assertTrue(state.isConnected())
        assertEquals("0xabc", state.addressOrNull())
        assertNull(state.errorMessageOrNull())
    }

    @Test
    fun connectedStateCanCarrySession() {
        val session = SessionSnapshot(sessionId = "session-1", turn = null, messageCount = 0)
        val state = ConnectionState.Connected(address = "0xabc", session = session)

        assertEquals(session, state.session)
    }

    @Test
    fun errorStateIsErrorWithMessage() {
        val state = ConnectionState.Error(message = "Unable to connect")

        assertTrue(state.isError())
        assertEquals("Unable to connect", state.errorMessageOrNull())
        assertNull(state.addressOrNull())
    }

    @Test
    fun addressOrNullReturnsNullForDisconnected() {
        assertNull(ConnectionState.Disconnected.addressOrNull())
    }

    @Test
    fun addressOrNullReturnsNullForConnecting() {
        assertNull(ConnectionState.Connecting.addressOrNull())
    }

    @Test
    fun errorMessageOrNullReturnsNullForConnected() {
        val state = ConnectionState.Connected(address = "0xabc")

        assertNull(state.errorMessageOrNull())
    }

    @Test
    fun errorMessageOrNullReturnsNullForDisconnected() {
        assertNull(ConnectionState.Disconnected.errorMessageOrNull())
    }

    @Test
    fun errorFromExceptionCreatesErrorWithCauseAndDetails() {
        val exception = IllegalArgumentException("bad address")
        val state = ConnectionState.Error.fromException(exception)

        assertEquals("bad address", state.message)
        assertSame(exception, state.cause)
        assertTrue(state.technicalDetails?.contains("IllegalArgumentException") == true)
    }

    @Test
    fun errorWithMessageCreatesErrorWithoutCause() {
        val state = ConnectionState.Error.withMessage("Manual failure")

        assertEquals("Manual failure", state.message)
        assertNull(state.cause)
        assertNull(state.technicalDetails)
    }

    @Test
    fun `Connected state has complete mutually-exclusive flags`() {
        val state = ConnectionState.Connected(address = "test-address")

        assertTrue(state.isConnected())
        assertFalse(state.isConnecting())
        assertFalse(state.isError())
        assertFalse(state.isDisconnected())
        assertEquals("test-address", state.addressOrNull())
        assertNull(state.errorMessageOrNull())
    }

    @Test
    fun `Error state has complete mutually-exclusive flags`() {
        val state = ConnectionState.Error(message = "Test error")

        assertFalse(state.isConnected())
        assertFalse(state.isConnecting())
        assertTrue(state.isError())
        assertFalse(state.isDisconnected())
        assertNull(state.addressOrNull())
        assertEquals("Test error", state.errorMessageOrNull())
    }
}
