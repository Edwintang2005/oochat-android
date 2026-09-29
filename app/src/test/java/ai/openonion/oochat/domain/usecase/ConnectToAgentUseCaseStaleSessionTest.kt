package ai.openonion.oochat.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the heuristic that decides whether a server Error message
 * means "your persisted session is no longer yours, drop it and
 * retry". The use case uses this to fast-fail the first attempt and
 * trigger the second attempt with sessionId=null, instead of waiting
 * the full 30s timeout.
 */
class ConnectToAgentUseCaseStaleSessionTest {

    @Test
    fun `matches the exact server wording`() {
        assertTrue(StaleSessionDetector.isStaleSessionError("Session is already attached to another connection"))
    }

    @Test
    fun `matches case-insensitively`() {
        assertTrue(StaleSessionDetector.isStaleSessionError("SESSION IS ALREADY ATTACHED"))
    }

    @Test
    fun `matches with extra context (server may prefix the error)`() {
        assertTrue(StaleSessionDetector.isStaleSessionError("[relay] session is already attached to another connection (sid=abc)"))
    }

    @Test
    fun `does not match plain network error`() {
        assertFalse(StaleSessionDetector.isStaleSessionError("Connection timeout"))
    }

    @Test
    fun `does not match auth error`() {
        assertFalse(StaleSessionDetector.isStaleSessionError("Unauthorized: invalid api key"))
    }

    @Test
    fun `does not match empty string`() {
        assertFalse(StaleSessionDetector.isStaleSessionError(""))
    }

    @Test
    fun `does not match a sentence that mentions session generically`() {
        // "session" alone (without "already" or "attach") is intentionally
        // NOT a match — we want a tight signal, not a broad one.
        assertFalse(StaleSessionDetector.isStaleSessionError("Starting a new chat session"))
    }
}
