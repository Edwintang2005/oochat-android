package ai.openonion.oochat.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StaleSessionDetector] is a tiny, dependency-free string heuristic, but a
 * load-bearing one: both [ConnectToAgentUseCase]'s fast-fail-and-retry path
 * and [ai.openonion.oochat.ui.chat.ChatViewModel]'s stale-session
 * error suppression depend on it agreeing with the server's actual wording.
 * Miss a real case here and both regress silently (a 30s hang, and a
 * permanent bogus error card, respectively); match too eagerly and a
 * genuine, unrelated error gets swallowed instead of shown.
 */
class StaleSessionDetectorTest {

    private fun isStale(message: String) = StaleSessionDetector.isStaleSessionError(message)

    @Test
    fun `recognizes the server's exact current wording, in any case`() {
        listOf(
            "Session is already attached to another connection",
            "SESSION IS ALREADY ATTACHED TO ANOTHER CONNECTION",
            "sEsSiOn iS AlReAdY aTtAcHeD"
        ).forEach { message -> assertTrue(message, isStale(message)) }
    }

    @Test
    fun `recognizes looser phrasing as long as two of the three keywords are present`() {
        assertTrue("session + already, no 'attach'", isStale("This session is already in use"))
        assertTrue("session + attach, no 'already'", isStale("Cannot attach: session busy"))
    }

    @Test
    fun `rejects messages carrying only one of the keywords`() {
        listOf(
            "Session expired",
            "This connection is already closed",
            "Failed to attach handler"
        ).forEach { message -> assertFalse(message, isStale(message)) }
    }

    @Test
    fun `rejects unrelated server errors and the empty string`() {
        listOf(
            "Insufficient ConnectOnion Credits",
            "Agent not connected",
            "forbidden: Denied by fast rules",
            ""
        ).forEach { message -> assertFalse(message, isStale(message)) }
    }
}
