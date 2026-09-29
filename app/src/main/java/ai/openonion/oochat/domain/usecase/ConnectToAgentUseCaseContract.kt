package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel-facing contract for connecting to an agent.
 *
 * Extracted as an interface so [ai.openonion.oochat.ui.chat.ChatViewModel]
 * can be unit-tested with an in-memory fake without spinning up a real
 * network stack or Ed25519 key manager. Matches the codebase pattern of
 * `ConnectionRepository` / `AgentRepository` / `IgnoredIdsStorage` — the
 * implementation ([ConnectToAgentUseCase]) is the production wiring, but
 * callers depend on the abstraction.
 */
interface ConnectToAgentUseCaseContract {

    /**
     * Current connection state — single source of truth for the UI.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * The currently-connected agent's own name card (model/tools/skills),
     * or null until its `AGENT_PROFILE` frame arrives — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.agentProfile].
     */
    val agentProfile: StateFlow<AgentLiveProfile?>

    /**
     * The live connection's tool-approval mode — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.approvalMode].
     * Reset to [ApprovalMode.DEFAULT] on every [connect].
     */
    val approvalMode: StateFlow<ApprovalMode>

    /**
     * Whether [approvalMode] is still a request rather than a confirmed
     * fact — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.modePending].
     */
    val modePending: StateFlow<Boolean>

     /**
     * The connected agent's own Home page (untrusted agent-authored HTML),
     * or null until its `DASHBOARD_SNAPSHOT` frame arrives — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.dashboardHtml].
     */
    val dashboardHtml: StateFlow<String?>

    /**
     * Observe chat events from the connected agent (Thinking / Agent
     * reply / Tool / Approval / Onboard / Output).
     */
    fun observeEvents(): Flow<ChatEvent>

    /**
     * Tell the connection its replayed transcript has been reconciled and can
     * be let go — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.releaseServerTranscript].
     * Default no-op so a fake with no connection behind it needs no override.
     */
    fun releaseServerTranscript() {}

    /**
     * Connect to an agent by address (relay mode) or URL (direct mode).
     *
     * Implementations should perform address discovery if needed and
     * block until the connection is established or times out.
     *
     * @param conversationId Local conversation to resume the server session
     *   of; null when it has no persisted row yet, which starts a fresh one
     * @return true if connected successfully, false on failure/timeout
     */
    suspend fun connect(agentAddress: String, conversationId: String? = null, directUrl: String? = null): Boolean

    /**
     * Move the live connection onto another conversation's server session —
     * see [ai.openonion.oochat.data.repository.ConnectionRepository.switchConversation].
     */
    suspend fun switchConversation(conversationId: String?)

    /**
     * The session id the live connection to [agentAddress] is on, or null when
     * there is none — see
     * [ai.openonion.oochat.data.repository.ConnectionRepository.liveSessionIdFor].
     * A conversation with no id yet adopts it rather than minting one.
     */
    fun liveSessionIdFor(agentAddress: String): String?

    /**
     * Send a user message to the connected agent.
     *
     * @param images Base64 data URLs to attach, e.g. "data:image/jpeg;base64,..."
     * @param files Non-image file attachments (name + base64 data URL each)
     */
    suspend fun sendMessage(content: String, agentAddress: String, images: List<String>? = null, files: List<OutgoingFileAttachment>? = null)

    /**
     * Reply to an `ask_user` event.
     */
    suspend fun respond(answer: String)

    /**
     * Ask the agent to gracefully stop its current run — it finishes the
     * current step and returns a closing message rather than stopping
     * mid-tool-call.
     */
    suspend fun interrupt()

    /**
     * Reply to an `approval_needed` event.
     *
     * @param scope "once" or "session" (session trusts this tool for the
     *   rest of the conversation)
     * @param mode when [approved] is false: "reject_soft" (skip this tool,
     *   keep going), "reject_hard" (stop the whole run), or
     *   "reject_explain" (ask the agent to explain before proceeding)
     * @param feedback free-text reason the LLM sees when [approved] is
     *   false, e.g. "use yarn instead". Ignored when [approved] is true.
     */
    suspend fun respondToApproval(approved: Boolean, scope: String = "once", mode: String? = null, feedback: String? = null)

    /**
     * Reply to an `ONBOARD_REQUIRED` event with an invite code or payment.
     */
    suspend fun respondToOnboard(method: String, inviteCode: String? = null, payment: Double? = null)

    /**
     * Reply to a `plan_review` checkpoint with free-text feedback.
     */
    suspend fun respondToPlanReview(message: String)

    /**
     * Reply to a `ulw_turns_reached` checkpoint.
     *
     * @param action "continue" or "switch_mode"
     * @param turns Additional turns to grant, when [action] is "continue"
     * @param mode Target mode ("safe", "plan", "accept_edits", "ulw"), when [action] is "switch_mode"
     */
    suspend fun respondToUlwTurnsReached(action: String, turns: Int? = null, mode: String? = null)

    /**
     * Ask the agent to switch tool-approval mode. Updates [approvalMode]
     * optimistically; the server's own `mode_changed` notification confirms
     * (or corrects) it.
     *
     * @param turns ULW's autonomous-turn budget — only meaningful for
     *   [ApprovalMode.ULW].
     */
    suspend fun setMode(mode: ApprovalMode, turns: Int? = null)

    /**
     * Disconnect from the current agent.
     */
    /**
     * Retry a pending reconnect now rather than on its backoff. Called when
     * the app returns to the foreground, where the cause of the drop — being
     * backgrounded — has just stopped applying.
     */
    fun retryNow()

    /**
     * Ask the server whether the current session is still running — used
     * after a reconnect to tell a genuinely-failed turn from one the server
     * is still working on. No-op if there is no known session id yet. Takes
     * no session id itself (unlike [ai.openonion.oochat.data.repository.ConnectionRepository.querySessionStatus]):
     * this contract stays at the domain layer, so it resolves the
     * wire-protocol session id internally rather than asking the ViewModel
     * to know it. The reply arrives asynchronously as a
     * [ChatEvent.SessionStatusReceived] through [observeEvents].
     */
    suspend fun querySessionStatus()

    suspend fun disconnect()

    /**
     * Whether a connection is currently open and ready for `sendMessage`.
     */
    fun isConnected(): Boolean

    /**
     * Reset the underlying session (used by `clearChat` so a re-connect
     * doesn't replay stale events).
     */
    suspend fun reset()
}
