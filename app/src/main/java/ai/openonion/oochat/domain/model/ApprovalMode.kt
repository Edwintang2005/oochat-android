package ai.openonion.oochat.domain.model

/**
 * The agent's tool-approval mode — `agent.current_session['mode']` on the
 * server. [wire] is the exact string the protocol uses, in both directions:
 * the client's outgoing `mode_change` frame and the server's `mode_changed`
 * notification (see
 * [ai.openonion.oochat.data.protocol.ModeChangeMessage] and
 * [ChatItem.ModeChangedItem]).
 *
 * [SAFE]/[PLAN]/[ACCEPT_EDITS] are the server's own `VALID_MODES`
 * (tool_approval/constants.py); [ULW] is a fourth mode owned by a separate
 * plugin (ulw.py) that skips every approval check for a fixed turn budget.
 */
enum class ApprovalMode(val wire: String) {
    SAFE("safe"),
    PLAN("plan"),
    ACCEPT_EDITS("accept_edits"),
    ULW("ulw");

    companion object {
        /**
         * The modes a single tap on the mode chip rotates through — and the
         * only ones it can ever produce.
         *
         * [ULW] is deliberately absent. It hands the agent every tool with no
         * approval gate for a user-chosen number of turns, so it must be an
         * explicit choice with an explicit budget (the ULW sheet), never
         * something a mistap can land on. [next] is the only cycling entry
         * point and it reads this list, so the property holds by construction
         * — ApprovalModeTest asserts it for every starting mode.
         */
        val CYCLE: List<ApprovalMode> = listOf(SAFE, PLAN, ACCEPT_EDITS)

        /** Parse a wire string, or null if the server sent a mode this client doesn't model. */
        fun fromWire(wire: String): ApprovalMode? = entries.firstOrNull { it.wire == wire }

        /** What a fresh connection assumes until the agent says otherwise — the server's own `DEFAULT_MODE`. */
        val DEFAULT: ApprovalMode = SAFE
    }
}

/**
 * The next mode in [ApprovalMode.CYCLE]. From [ApprovalMode.ULW] — which is
 * not in the cycle — this returns the first cycle entry, so the same call
 * doubles as "leave ULW"; the chip never offers plain cycling while ULW is
 * active, it offers exit.
 */
fun ApprovalMode.next(): ApprovalMode {
    val cycle = ApprovalMode.CYCLE
    return cycle[(cycle.indexOf(this) + 1) % cycle.size]
}
