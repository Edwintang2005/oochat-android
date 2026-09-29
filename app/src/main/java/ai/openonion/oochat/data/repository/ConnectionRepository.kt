package ai.openonion.oochat.data.repository

import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing agent connections.
 *
 * Abstracts connection management from ViewModel.
 * Provides single source of truth for connection state.
 *
 * Designed for testability and future extensibility.
 */
interface ConnectionRepository {

    /**
     * Current connection state as StateFlow.
     * This is the single source of truth for connection status.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * The currently-connected agent's own name card, or null when no
     * `AGENT_PROFILE` frame has arrived yet for the live connection. Reset
     * to null at the start of every [connect] call — see [connect]'s own
     * note — so a reconnect never leaks the previous agent's tools/skills
     * into the new connection's UI.
     */
    val agentProfile: StateFlow<AgentLiveProfile?>

    /**
     * The live connection's tool-approval mode. Connection-scoped like
     * [agentProfile]: reset to [ApprovalMode.DEFAULT] at the start of every
     * [connect] so a reconnect can't show the previous agent's mode as if it
     * were this one's. Authoritative source is the server's `mode_changed`
     * notification; [setMode] additionally updates it optimistically so the
     * chip responds to a tap without a round trip.
     */
    val approvalMode: StateFlow<ApprovalMode>

    /**
     * True from the moment [setMode] sends its optimistic write until the
     * agent's own `mode_changed` confirms it (or an `ERROR` naming
     * `mode_change` rolls [approvalMode] back). The server only applies a
     * mode change on its next `before_iteration` hook, so this window can
     * span a whole turn — see [setMode]'s own doc. Reset to false alongside
     * [approvalMode] at the start of every [connect].
     */
    val modePending: StateFlow<Boolean>

     /**
     * The connected agent's own `dashboard.html`, verbatim, or null when no
     * `DASHBOARD_SNAPSHOT` frame has arrived for the live connection. The
     * payload is **untrusted agent-authored HTML** — see
     * [ai.openonion.oochat.ui.dashboard.DashboardDocument] for how it
     * is contained before rendering.
     *
     * Reset to null at the start of every [connect] for the same reason as
     * [agentProfile]: one agent's Home page must never be shown under
     * another agent's name, and its buttons are validated against
     * [agentProfile]'s skill list, which is cleared at the same moment.
     */
    val dashboardHtml: StateFlow<String?>

    fun observeEvents(): Flow<ChatEvent>

    /**
     * Drop the replayed transcript this connection is holding for late
     * subscribers, once one has reconciled it. Default no-op so a fake that
     * never replays one does not have to say so.
     */
    fun releaseServerTranscript() {}

    /**
     * Connect to an agent, resuming [conversationId]'s own server session.
     *
     * @param agentAddress Agent address or server URL
     * @param conversationId Local conversation this connection is for; null
     *   when it has no persisted row yet, which starts a fresh server session
     * @param directUrl Direct connection URL (optional)
     */
    suspend fun connect(agentAddress: String, conversationId: String? = null, directUrl: String? = null)

    /**
     * Move the live connection onto [conversationId]'s server session,
     * re-CONNECTing so subsequent frames belong to it alone. The conversation
     * id is the session id, so one the server has never seen simply starts a
     * new session under that id.
     */
    suspend fun switchConversation(conversationId: String?)

    /**
     * Send a message to the connected agent.
     *
     * @param content Message content
     * @param agentAddress Agent address
     * @param images Base64 data URLs to attach, e.g. "data:image/jpeg;base64,..."
     * @param files Non-image file attachments (name + base64 data URL each)
     */
    suspend fun sendMessage(content: String, agentAddress: String, images: List<String>? = null, files: List<OutgoingFileAttachment>? = null)

    /**
     * Respond to ask_user event.
     *
     * @param answer User's answer
     */
    suspend fun respond(answer: String)

    /**
     * Ask the agent to gracefully stop its current run — it finishes the
     * current step and returns a closing message rather than stopping
     * mid-tool-call.
     */
    suspend fun interrupt()

    /**
     * Respond to approval_needed event.
     *
     * @param approved Whether approved
     * @param scope Approval scope ("once" or "session")
     * @param mode Rejection reason when [approved] is false — "reject_soft",
     *   "reject_hard", or "reject_explain". Ignored when [approved] is true.
     * @param feedback Free-text reason shown to the LLM when [approved] is
     *   false. Ignored when [approved] is true.
     */
    suspend fun respondToApproval(approved: Boolean, scope: String = "once", mode: String? = null, feedback: String? = null)

    /**
     * Respond to ONBOARD_REQUIRED event with either an invite code
     * or a payment confirmation.
     */
    suspend fun respondToOnboard(method: String, inviteCode: String? = null, payment: Double? = null)

    /**
     * Respond to a plan_review checkpoint with free-text feedback
     * (approval/rejection is encoded in [message] — see
     * [ChatItem.PlanReviewItem][ai.openonion.oochat.domain.model.ChatItem.PlanReviewItem]).
     */
    suspend fun respondToPlanReview(message: String)

    /**
     * Respond to a ulw_turns_reached checkpoint.
     *
     * @param action "continue" or "switch_mode"
     * @param turns Additional turns to grant, when [action] is "continue"
     * @param mode Target mode ("safe", "plan", "accept_edits", "ulw"), when [action] is "switch_mode"
     */
    suspend fun respondToUlwTurnsReached(action: String, turns: Int? = null, mode: String? = null)

    /**
     * Ask the agent to switch tool-approval mode, and reflect the request in
     * [approvalMode] right away — see [modePending] for how long that
     * request can stay unconfirmed.
     *
     * @param turns ULW's autonomous-turn budget. Only meaningful for
     *   [ApprovalMode.ULW]; the server ignores it on every other branch.
     */
    suspend fun setMode(mode: ApprovalMode, turns: Int? = null)

    /**
     * Retry a pending reconnect immediately instead of waiting out its
     * backoff — see AgentConnection.retryNow. No-op when nothing is pending.
     */
    fun retryNow()

    /**
     * Ask the server whether [sessionId] is still running — see
     * AgentConnection.querySessionStatus. The reply arrives asynchronously
     * as a [ChatEvent.SessionStatusReceived] through [observeEvents].
     */
    suspend fun querySessionStatus(sessionId: String)

    suspend fun disconnect()

    fun isConnected(): Boolean

    suspend fun reset()

    fun getCurrentSession(): SessionState?

    /**
     * The session id the live connection to [agentAddress] is talking on, or
     * null when nothing is connected, the connection is on a different agent,
     * or no session has been agreed yet.
     *
     * A conversation with no id of its own adopts this instead of minting
     * one — the conversation id IS the session id (see the impl's `sessionFor`),
     * so adopting keeps the two equal and joins the live socket.
     */
    fun liveSessionIdFor(agentAddress: String): String?

    /**
     * Read-only peek of the persisted sessionId for a conversation — does
     * NOT touch the connection, does NOT start collection, just looks up the
     * on-disk row. Used by the use-case layer to log which sessionId an
     * upcoming connect() call is about to try to resume.
     */
    suspend fun peekPersistedSessionId(conversationId: String): String?
}
