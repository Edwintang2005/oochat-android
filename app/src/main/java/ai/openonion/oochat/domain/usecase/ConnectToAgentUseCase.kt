package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.repository.ConnectionRepository
import ai.openonion.oochat.data.repository.ConnectionRepositoryImpl
import ai.openonion.oochat.data.repository.SessionStore
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.OutgoingFileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.AgentDiscoveryService
import ai.openonion.oochat.network.InMemoryPendingMessageSink
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Use case for connecting to an agent.
 *
 * Encapsulates connection logic that was previously duplicated
 * between OnboardingScreen and ChatViewModel.
 *
 * @param keyManager Key manager for Ed25519 signing
 * @param relayUrl Relay server URL
 * @param connectionTimeoutMs Connection timeout in milliseconds
 * @param sessionStore Optional store for persisting session state across restarts
 * @param agentDiscovery Service for resolving agent addresses from input
 */
class ConnectToAgentUseCase(
    private val keyManager: KeyManager,
    private val relayUrl: String = AgentConnection.DEFAULT_RELAY,
    private val connectionTimeoutMs: Long = 30_000L,
    private val sessionStore: SessionStore? = null,
    private val agentDiscovery: AgentDiscoveryService = AgentDiscoveryService(),
    private val pendingMessageSink: PendingMessageSink = InMemoryPendingMessageSink(),
    // Factory (not a shared instance) so getOrCreateRepository() below still
    // creates at most one repository per use-case instance. Defaults to the
    // real ConnectionRepositoryImpl; tests substitute a fake ConnectionRepository
    // to exercise the retry/timeout state machine without a real network stack.
    private val repositoryFactory: () -> ConnectionRepository = {
        ConnectionRepositoryImpl(
            keyManager,
            relayUrl,
            sessionStore,
            pendingMessageSink,
            agentDiscovery = agentDiscovery
        )
    }
) : ConnectToAgentUseCaseContract {
    // @Volatile + @Synchronized on the creator below: this instance is now
    // app-scoped and reached from several ViewModels, so "at most one
    // repository" can't rely on every caller happening to be on the main thread.
    @Volatile private var repository: ConnectionRepository? = null

    /**
     * Current connection state.
     */
    override val connectionState: StateFlow<ConnectionState>
        get() = getOrCreateRepository().connectionState

    override val agentProfile: StateFlow<AgentLiveProfile?>
        get() = getOrCreateRepository().agentProfile

    override val approvalMode: StateFlow<ApprovalMode>
        get() = getOrCreateRepository().approvalMode

    override val modePending: StateFlow<Boolean>
        get() = getOrCreateRepository().modePending

    override val dashboardHtml: StateFlow<String?>
        get() = getOrCreateRepository().dashboardHtml

    /**
     * Connect to an agent with timeout.
     *
     * Performs address discovery: if the input is a full hex address, it is used
     * directly (relay mode). If the input is a URL, the /info endpoint is queried
     * to resolve the agent address (direct mode).
     *
     * @param agentAddress Agent address (0x...) or URL for discovery
     * @param directUrl Direct connection URL (null for relay mode); overridden if discovery resolves a URL
     * @return true if connected successfully, false if timed out or failed
     */
    override suspend fun connect(agentAddress: String, conversationId: String?, directUrl: String?): Boolean {
        val resolved = agentDiscovery.resolveAgentAddress(agentAddress)
        val effectiveAddress = resolved.address
        val effectiveDirectUrl = resolved.directUrl ?: directUrl

        // First attempt — uses the conversation's persisted sessionId if any.
        // The server may reject this if the session was already attached to
        // another connection (e.g. a previous instance that didn't cleanly
        // disconnect) or if the session expired on the server.
        if (tryConnectOnce(effectiveAddress, conversationId, effectiveDirectUrl, attempt = 1)) return true

        // A relay that already named the reason — the agent has no socket of
        // its own — has answered the question. Re-asking it costs another
        // handshake and a reset() that discards a session nothing is wrong
        // with. Transport failures below still get their retry.
        val verdict = getOrCreateRepository().connectionState.value.errorMessageOrNull()
        if (verdict != null && ServerErrorText.isOfflineVerdict(verdict)) {
            FileLogger.w(LogTags.CONNECT_USE_CASE, "Relay says the agent is offline; skipping attempt 2")
            return false
        }

        // Fallback: drop the persisted session and try once more with
        // sessionId=null, so the server creates a fresh session. The
        // wasted 30s timeout on the first attempt is the cost of
        // auto-recovering from a stale session; the second attempt
        // either connects (success) or fails fast (no real network
        // issue, so we don't wait the full timeout again — reset()
        // closes the WS immediately).
        FileLogger.w(LogTags.CONNECT_USE_CASE, "First attempt failed; clearing session and retrying")
        reset()

        return tryConnectOnce(effectiveAddress, conversationId, effectiveDirectUrl, attempt = 2)
    }

    override suspend fun switchConversation(conversationId: String?) {
        getOrCreateRepository().switchConversation(conversationId)
    }

    // Deliberately does not create the repository: "is anything live for this
    // agent" is a question with an honest null answer before one exists.
    override fun liveSessionIdFor(agentAddress: String): String? =
        repository?.liveSessionIdFor(agentAddress)


    /**
     * Single connect attempt. Returns true iff the connection reaches
     * the Connected state within [connectionTimeoutMs] — or the agent
     * gates on ONBOARD_REQUIRED (see below).
     *
     * Returns false early (no 30s wait) when the server rejects the
     * attempt with a "Session is already attached" error — that's the
     * signal to drop the persisted session and try again with
     * sessionId=null.
     */
    private suspend fun tryConnectOnce(
        address: String,
        conversationId: String?,
        directUrl: String?,
        attempt: Int
    ): Boolean {
        val repo = getOrCreateRepository()
        // The persistedSessionId is computed *just before* connect() so
        // we can log whether this attempt will try to resume an old
        // session or start fresh — invaluable for diagnosing
        // "Session is already attached to another connection" cases.
        val persistedSessionId = conversationId?.let { repo.peekPersistedSessionId(it) }
        FileLogger.i(
            "ConnectUseCase",
            "attempt=$attempt addr=${address.take(16)} direct=${directUrl != null} " +
                "persistedSessionId=${persistedSessionId ?: "<none>"}"
        )
        return coroutineScope {
            // Some agents only respond with ONBOARD_REQUIRED (never CONNECTED),
            // so race the state-based wait against the first ONBOARD_REQUIRED
            // chat event. The listener is subscribed before any ONBOARD_REQUIRED
            // could be emitted.
            val onboardDeferred = async {
                repo.observeEvents()
                    .filterIsInstance<ChatEvent.ChatItemReceived>()
                    .mapNotNull { it.item as? ChatItem.OnboardRequired }
                    .first()
            }
            val stateDeferred = async {
                repo.connect(address, conversationId, directUrl)
                withTimeoutOrNull(connectionTimeoutMs) {
                    // Wait until state leaves Connecting/Reconnecting. Surface
                    // a "stale session" error as a fast-fail so the outer
                    // connect() can drop the persisted sessionId and retry
                    // without waiting the full 30s.
                    while (repo.connectionState.value is ConnectionState.Connecting ||
                           repo.connectionState.value is ConnectionState.Reconnecting) {
                        val err = (repo.connectionState.value as? ConnectionState.Error)?.message
                        if (err != null && isStaleSessionError(err)) {
                            FileLogger.w(
                                "ConnectUseCase",
                                "Fast-fail on stale-session error after <30s: $err"
                            )
                            repo.disconnect()
                            return@withTimeoutOrNull false
                        }
                        delay(100)
                    }
                    repo.connectionState.value is ConnectionState.Connected
                } ?: run {
                    FileLogger.e(LogTags.CONNECT_USE_CASE, "Connection timeout after ${connectionTimeoutMs}ms")
                    repo.disconnect()
                    false
                }
            }

            val onboardWon = select<Boolean> {
                onboardDeferred.onAwait { true }
                stateDeferred.onAwait { false }
            }

            if (onboardWon) {
                stateDeferred.cancel()
                true
            } else {
                onboardDeferred.cancel()
                stateDeferred.await()
            }
        }
    }

    private fun isStaleSessionError(message: String): Boolean =
        StaleSessionDetector.isStaleSessionError(message)

    override suspend fun sendMessage(content: String, agentAddress: String, images: List<String>?, files: List<OutgoingFileAttachment>?) {
        getOrCreateRepository().sendMessage(content, agentAddress, images, files)
    }

    override fun observeEvents(): Flow<ChatEvent> {
        return getOrCreateRepository().observeEvents()
    }

    override fun releaseServerTranscript() {
        getOrCreateRepository().releaseServerTranscript()
    }

    override suspend fun respond(answer: String) {
        getOrCreateRepository().respond(answer)
    }

    override suspend fun interrupt() {
        getOrCreateRepository().interrupt()
    }

    override suspend fun respondToApproval(approved: Boolean, scope: String, mode: String?, feedback: String?) {
        getOrCreateRepository().respondToApproval(approved, scope, mode, feedback)
    }

    override suspend fun respondToOnboard(method: String, inviteCode: String?, payment: Double?) {
        getOrCreateRepository().respondToOnboard(method, inviteCode, payment)
    }

    override suspend fun respondToPlanReview(message: String) {
        getOrCreateRepository().respondToPlanReview(message)
    }

    override suspend fun respondToUlwTurnsReached(action: String, turns: Int?, mode: String?) {
        getOrCreateRepository().respondToUlwTurnsReached(action, turns, mode)
    }

    override suspend fun setMode(mode: ApprovalMode, turns: Int?) {
        getOrCreateRepository().setMode(mode, turns)
    }

    override fun retryNow() {
        repository?.retryNow()
    }

    override suspend fun querySessionStatus() {
        val repo = repository ?: return
        val sessionId = repo.getCurrentSession()?.sessionId ?: run {
            FileLogger.w(LogTags.CONNECT_USE_CASE, "querySessionStatus: no known session id, skipping")
            return
        }
        repo.querySessionStatus(sessionId)
    }

    override suspend fun disconnect() {
        repository?.disconnect()
    }

    override fun isConnected(): Boolean {
        return repository?.isConnected() == true
    }

    override suspend fun reset() {
        repository?.reset()
    }

    @Synchronized
    private fun getOrCreateRepository(): ConnectionRepository {
        return repository ?: repositoryFactory().also { repository = it }
    }
}
