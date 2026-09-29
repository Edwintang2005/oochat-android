package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApprovalMode]'s cycle, and the safety property that motivates it.
 */
class ApprovalModeTest {

    /**
     * The property the whole design rests on: no sequence of chip taps can
     * ever put the agent into ULW. ULW skips every tool approval for a fixed
     * turn budget, so it must be a deliberate choice made in the sheet, with
     * a budget the user typed — not somewhere a mistap can land.
     *
     * Asserted from *every* mode, ULW included, because [next] is the only
     * cycling entry point in the app ([ChatViewModel.cycleApprovalMode][ai.openonion.oochat.ui.chat.ChatViewModel.cycleApprovalMode]
     * calls it and nothing else does).
     */
    @Test
    fun `no starting mode can reach ULW by cycling`() {
        ApprovalMode.entries.forEach { start ->
            var mode = start
            // One full lap past the cycle length from every entry point —
            // enough to visit every reachable state and return.
            repeat(ApprovalMode.CYCLE.size + 2) {
                mode = mode.next()
                assertFalse(
                    "cycling from $start reached ULW after $it step(s)",
                    mode == ApprovalMode.ULW
                )
            }
        }
    }

    @Test
    fun `CYCLE holds exactly the three base modes and excludes ULW`() {
        assertEquals(
            listOf(ApprovalMode.SAFE, ApprovalMode.PLAN, ApprovalMode.ACCEPT_EDITS),
            ApprovalMode.CYCLE
        )
        assertFalse(ApprovalMode.CYCLE.contains(ApprovalMode.ULW))
    }

    @Test
    fun `next walks the cycle in order and wraps`() {
        assertEquals(ApprovalMode.PLAN, ApprovalMode.SAFE.next())
        assertEquals(ApprovalMode.ACCEPT_EDITS, ApprovalMode.PLAN.next())
        assertEquals(ApprovalMode.SAFE, ApprovalMode.ACCEPT_EDITS.next())
    }

    /** From outside the cycle, "next" is the exit the chip offers while ULW is live. */
    @Test
    fun `next from ULW lands on safe`() {
        assertEquals(ApprovalMode.SAFE, ApprovalMode.ULW.next())
    }

    @Test
    fun `wire strings match the server's own mode vocabulary`() {
        assertEquals("safe", ApprovalMode.SAFE.wire)
        assertEquals("plan", ApprovalMode.PLAN.wire)
        assertEquals("accept_edits", ApprovalMode.ACCEPT_EDITS.wire)
        assertEquals("ulw", ApprovalMode.ULW.wire)
    }

    @Test
    fun `fromWire round-trips every mode and rejects unknown strings`() {
        ApprovalMode.entries.forEach { mode ->
            assertEquals(mode, ApprovalMode.fromWire(mode.wire))
        }
        assertNull(ApprovalMode.fromWire("yolo"))
        assertNull(ApprovalMode.fromWire(""))
    }

    @Test
    fun `DEFAULT is safe`() {
        assertTrue(ApprovalMode.DEFAULT == ApprovalMode.SAFE)
    }
}
