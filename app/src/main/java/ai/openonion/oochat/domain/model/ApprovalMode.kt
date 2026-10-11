package ai.openonion.oochat.domain.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The agent's tool-approval mode — `agent.current_session['mode']` on the
 * server. [wire] is the exact string the protocol uses, in both directions:
 * the client's outgoing `mode_change` frame and the server's `mode_changed`
 * notification (see
 * [ai.openonion.oochat.data.protocol.ModeChangeMessage] and
 * [ChatItem.ModeChangedItem]).
 *
 * Two vocabularies, one per Host generation — see [SessionModes]:
 *  - Legacy: [SAFE]/[PLAN]/[ACCEPT_EDITS] are the tool_approval plugin's own
 *    `VALID_MODES` (tool_approval/constants.py); [ULW] is a fourth mode owned
 *    by a separate plugin (ulw.py) that skips every approval check for a
 *    fixed turn budget.
 *  - Host-managed (ConnectOnion 1.7+, core/mode.py's `MODES`): [READ_ONLY],
 *    [AUTO] and [FULL_ACCESS]. The Host has no alias reader, so a legacy name
 *    sent to it is refused and must never be offered there.
 */
enum class ApprovalMode(val wire: String) {
    SAFE("safe"),
    PLAN("plan"),
    ACCEPT_EDITS("accept_edits"),
    ULW("ulw"),
    READ_ONLY("read-only"),
    AUTO("auto"),
    FULL_ACCESS("full-access");

    /**
     * True for the modes that run tools with no approval gate at all. Never
     * reachable by tapping the chip, only through the sheet; the chip turns
     * `error` while one is active and offers exit instead of cycling.
     */
    val isUnattended: Boolean get() = this == ULW || this == FULL_ACCESS

    companion object {
        /**
         * The legacy modes a single tap on the mode chip rotates through — and
         * the only ones it can ever produce on a legacy Host.
         *
         * [ULW] is deliberately absent. It hands the agent every tool with no
         * approval gate for a user-chosen number of turns, so it must be an
         * explicit choice with an explicit budget (the ULW sheet), never
         * something a mistap can land on. [next] is the only cycling entry
         * point and it reads this list (or [SessionModes.cycle], which applies
         * the same rule), so the property holds by construction —
         * ApprovalModeTest asserts it for every starting mode.
         */
        val CYCLE: List<ApprovalMode> = listOf(SAFE, PLAN, ACCEPT_EDITS)

        /** Parse a wire string, or null if the server sent a mode this client doesn't model. */
        fun fromWire(wire: String): ApprovalMode? = entries.firstOrNull { it.wire == wire }

        /** What a fresh legacy connection assumes until the agent says otherwise — the plugin's own `DEFAULT_MODE`. */
        val DEFAULT: ApprovalMode = SAFE
    }
}

/**
 * The next mode in [cycle]. From a mode that is not in the cycle ([ApprovalMode.ULW],
 * [ApprovalMode.FULL_ACCESS]) this returns the first cycle entry, so the same call
 * doubles as "leave unattended mode"; the chip never offers plain cycling while one is
 * active, it offers exit.
 */
fun ApprovalMode.next(cycle: List<ApprovalMode> = ApprovalMode.CYCLE): ApprovalMode {
    if (cycle.isEmpty()) return this
    return cycle[(cycle.indexOf(this) + 1) % cycle.size]
}

/**
 * Which modes this connection may offer, and who owns the mode.
 *
 * A Host on ConnectOnion 1.7+ with mode policy enabled puts `session_modes` on `CONNECTED`
 * (`currentModeId`, `turnsLeft`, `availableModes`) and answers `mode_change` itself, at once,
 * with `mode_changed` or an `ERROR` ([hostManaged]). An older Host sends nothing and leaves the
 * frame to the agent's tool_approval plugin, which reads the legacy vocabulary ([LEGACY]).
 *
 * @param available The modes the Host advertised, in its order, or the legacy four.
 * @param hostManaged True when the Host advertised them; see the class doc for what changes.
 * @param turnsLeft Full access's remaining turn budget, from `turnsLeft`, `mode_changed`'s
 *   `turns_left` and `mode_budget_changed`. Null in every other mode.
 */
data class SessionModes(
    val available: List<ApprovalMode>,
    val hostManaged: Boolean,
    val turnsLeft: Int? = null
) {
    /** What the chip rotates through: [available] minus the unattended modes. */
    val cycle: List<ApprovalMode> get() = available.filterNot { it.isUnattended }

    /** What a fresh connection assumes until the Host or agent says otherwise. */
    val default: ApprovalMode get() = if (hostManaged) ApprovalMode.AUTO else ApprovalMode.DEFAULT

    companion object {
        val LEGACY = SessionModes(
            available = listOf(ApprovalMode.SAFE, ApprovalMode.PLAN, ApprovalMode.ACCEPT_EDITS, ApprovalMode.ULW),
            hostManaged = false
        )
    }
}

/**
 * What `CONNECTED`'s `session_modes` said: the authoritative current mode and the modes on
 * offer. Ids this client does not model are dropped from [available]; a [current] it does
 * not model is null.
 */
data class HostModeState(
    val current: ApprovalMode?,
    val turnsLeft: Int?,
    val available: List<ApprovalMode>
)

/** A connection that never hears from a mode-managing Host: [SessionModes.LEGACY], forever. Shared default for fakes. */
val LEGACY_SESSION_MODES: StateFlow<SessionModes> = MutableStateFlow(SessionModes.LEGACY).asStateFlow()
