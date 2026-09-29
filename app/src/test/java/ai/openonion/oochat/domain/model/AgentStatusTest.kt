package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStatusTest {

    // ── isAvailable() ──────────────────────────────────────────────

    @Test
    fun `isAvailable returns true only for Active state`() {
        assertTrue(AgentStatus.Active.isAvailable())
    }

    @Test
    fun `isAvailable returns false for Connecting`() {
        assertFalse(AgentStatus.Connecting.isAvailable())
    }

    @Test
    fun `isAvailable returns false for Connected`() {
        assertFalse(AgentStatus.Connected.isAvailable())
    }

    @Test
    fun `isAvailable returns false for Error`() {
        assertFalse(AgentStatus.Error("fail").isAvailable())
    }

    @Test
    fun `isAvailable returns false for Disabled`() {
        assertFalse(AgentStatus.Disabled.isAvailable())
    }

    // ── isConnected() ──────────────────────────────────────────────

    @Test
    fun `isConnected returns true only for Connected state`() {
        assertTrue(AgentStatus.Connected.isConnected())
    }

    @Test
    fun `isConnected returns false for Active`() {
        assertFalse(AgentStatus.Active.isConnected())
    }

    @Test
    fun `isConnected returns false for Connecting`() {
        assertFalse(AgentStatus.Connecting.isConnected())
    }

    @Test
    fun `isConnected returns false for Error`() {
        assertFalse(AgentStatus.Error("fail").isConnected())
    }

    @Test
    fun `isConnected returns false for Disabled`() {
        assertFalse(AgentStatus.Disabled.isConnected())
    }

    // ── isError() ──────────────────────────────────────────────────

    @Test
    fun `isError returns true only for Error state`() {
        assertTrue(AgentStatus.Error("msg").isError())
    }

    @Test
    fun `isError returns false for Active`() {
        assertFalse(AgentStatus.Active.isError())
    }

    @Test
    fun `isError returns false for Connecting`() {
        assertFalse(AgentStatus.Connecting.isError())
    }

    @Test
    fun `isError returns false for Connected`() {
        assertFalse(AgentStatus.Connected.isError())
    }

    @Test
    fun `isError returns false for Disabled`() {
        assertFalse(AgentStatus.Disabled.isError())
    }

    // ── errorMessage() ─────────────────────────────────────────────

    @Test
    fun `errorMessage returns message for Error state`() {
        assertEquals("something went wrong", AgentStatus.Error("something went wrong").errorMessage())
    }

    @Test
    fun `errorMessage returns null for Active`() {
        assertNull(AgentStatus.Active.errorMessage())
    }

    @Test
    fun `errorMessage returns null for Connecting`() {
        assertNull(AgentStatus.Connecting.errorMessage())
    }

    @Test
    fun `errorMessage returns null for Connected`() {
        assertNull(AgentStatus.Connected.errorMessage())
    }

    @Test
    fun `errorMessage returns null for Disabled`() {
        assertNull(AgentStatus.Disabled.errorMessage())
    }

    // ── Error state edge cases ─────────────────────────────────────

    @Test
    fun `Error with empty string message stores it correctly`() {
        val error = AgentStatus.Error("")
        assertTrue(error.isError())
        assertEquals("", error.errorMessage())
    }

    @Test
    fun `Error with long message preserves full text`() {
        val longMsg = "A".repeat(1000)
        assertEquals(longMsg, AgentStatus.Error(longMsg).errorMessage())
    }

    // ── Distinct type checks ───────────────────────────────────────

    @Test
    fun `Active and Connected are different types`() {
        assertNotEquals(AgentStatus.Active, AgentStatus.Connected)
    }

    @Test
    fun `Connecting and Connected are different types`() {
        assertNotEquals(AgentStatus.Connecting, AgentStatus.Connected)
    }
}
