package ai.openonion.oochat.network

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.ApprovalResponse
import ai.openonion.oochat.data.protocol.AskUserResponse
import ai.openonion.oochat.data.protocol.ConnectMessage
import ai.openonion.oochat.data.protocol.ConnectPayload
import ai.openonion.oochat.data.protocol.ExecMessage
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.InputMsg
import ai.openonion.oochat.data.protocol.InterruptMessage
import ai.openonion.oochat.data.protocol.ModeChangeMessage
import ai.openonion.oochat.data.protocol.OipProtocol
import ai.openonion.oochat.data.protocol.OnboardSubmitMessage
import ai.openonion.oochat.data.protocol.OnboardSubmitPayload
import ai.openonion.oochat.data.protocol.PlanReviewResponse
import ai.openonion.oochat.data.protocol.PongMessage
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.protocol.SessionStatusQuery
import ai.openonion.oochat.data.protocol.SessionStatusQuerySession
import ai.openonion.oochat.data.protocol.SignedCommand
import ai.openonion.oochat.data.protocol.UlwResponse
import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ExecOutcome
import ai.openonion.oochat.domain.usecase.ServerErrorText
import ai.openonion.oochat.domain.usecase.StaleSessionDetector
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogSanitizer
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connection to ConnectOnion agents.
 *
 * @param keyManager Key manager for Ed25519 signing
 * @param relayUrl Relay server URL (default: https://oo.openonion.ai)
 * @param webSocketFactory Factory for creating WebSocket instances (for testing)
 * @param signer Ed25519 signing seam (for testing) — defaults to
 *   [keyManager]'s real `sign()`, which needs the native libsodium binary
 *   and throws `UnsatisfiedLinkError` on a host JVM/Robolectric (see
 *   KeyManagerTest's doc comment). Tests inject a fake instead.
 */
class AgentConnection(
    private val keyManager: KeyManager,
    private val relayUrl: String = DEFAULT_RELAY,
    private val webSocketFactory: WebSocketFactory = OkHttpWebSocketFactory(webSocketClient),
    private val signer: (KeyManager.AddressData, String) -> String = keyManager::sign,
    // Where the reconnect ladder's delay() runs. A test dispatcher here lets a
    // test park a rung mid-backoff and then decide when it wakes, instead of
    // waiting out real seconds to find out whether it was disarmed.
    private val reconnectDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Persistence seam for messages queued while the connection isn't ready.
    // Defaults to in-memory so this class stays constructible with no Room
    // dependency; production wiring injects the Room-backed sink so a queued
    // message survives process death.
    private val pendingMessageSink: PendingMessageSink = InMemoryPendingMessageSink(),
    // Whether the app is on screen. The slow retry against an offline agent
    // (W16AAPPLE-79) only runs while it is: in the background it waits for
    // retryNow(), which the chat screen calls when it comes back.
    private val isAppVisible: () -> Boolean = { true }
) {
    @Volatile private var webSocket: WebSocket? = null

    /** Every signed timestamp's source: the device clock, corrected by the server's (W16AAPPLE-90). */
    private val hostClock = HostClock()
    @Volatile private var keys: KeyManager.AddressData? = null
    @Volatile private var _currentSession: SessionState? = null
    val currentSession: SessionState? get() = _currentSession
    private data class ReplayCursor(val sessionId: String, val msgId: String)
    @Volatile private var replayCursor: ReplayCursor? = null

    /**
     * Keys ([ParseResult.dedupeKey]) of the agent-log frames already handled, oldest first. After a
     * reconnect the Host re-sends frames this connection has seen, so repeats are dropped before they
     * reach anything with a side effect. The key is the frame's decoded content, not its raw text or
     * its id: the relay stamps a different conn_id on every socket, and the Host reuses one id for an
     * llm_call and its llm_result and for each status of a compact or an eval.
     */
    private val seenFrames = LinkedHashSet<String>()

    /** True the first time [key] is seen, false for a repeat; see [seenFrames]. */
    private fun isFirstSighting(key: String): Boolean {
        synchronized(seenFrames) {
            if (!seenFrames.add(key)) return false
            while (seenFrames.size > MAX_SEEN_FRAMES) seenFrames.remove(seenFrames.first())
            return true
        }
    }

    /**
     * Every INPUT whose reply is still owed, not just the newest one.
     *
     * A single slot was right while a turn could only be started, never added
     * to. An INPUT sent on a running session is pushed into that run as runtime
     * input (ws_router/session.py's INPUT branch) and answered by the run's
     * original OUTPUT, which on the relay path echoes the *first* input_id — so
     * overwriting the slot made the reply look like someone else's and
     * ProtocolParser dropped it, leaving the composer stuck mid-turn.
     */
    private val outstandingInputIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** EXEC calls waiting for the EXEC_RESULT carrying the same exec_id. */
    private val pendingExecs = ConcurrentHashMap<String, CompletableDeferred<ExecOutcome>>()
    @Volatile private var isDirect: Boolean = false
    @Volatile private var agentAddress: String = ""
    @Volatile private var directUrl: String? = null

    // Connection ready state - true only when WS is open and CONNECT handshake is done
    @Volatile
    private var isConnectionReady = false

    // Reconnection state. @Volatile: written from connect()/disconnect() and
    // from the OkHttp WebSocketListener callback thread, read from both plus
    // reconnectScope's own IO coroutine — needs cross-thread visibility.
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var reconnectScope = CoroutineScope(SupervisorJob() + reconnectDispatcher)
    @Volatile private var isReconnecting = false

    // Whether this connect() cycle has ever reached the agent (CONNECTED
    // landed), and if it never has, the server's own account of why. The relay
    // endpoint is shared, so a socket opens against an offline agent exactly as
    // it does against a live one — only CONNECTED separates them.
    @Volatile private var reachedAgentThisCycle = false
    @Volatile private var neverReachedReason: String? = null

    // True from sending a CONNECT on the current socket until the Host answers
    // it. An ERROR in that window is the answer, not a business error about a
    // turn, which is what lets a refused reconnect end (W16AAPPLE-82).
    @Volatile private var awaitingConnected = false

    // Whether the current socket was opened after this cycle had already
    // reached the agent: a reconnect, or a conversation switch. Captured when
    // the socket opens, so the gate's own re-CONNECT on the first socket
    // (reauthenticate) is never mistaken for one.
    @Volatile private var socketIsReconnect = false

    // Gives up on a reconnect socket whose CONNECT the Host never answers; see
    // [awaitConnectedOrRetry].
    @Volatile private var connectedWaitJob: Job? = null

    // Whether the relay said "Agent not connected" on the current attempt, and
    // whether the ladder has since run out and fallen back to [pollOfflineAgent]
    // (W16AAPPLE-79). Reset by every attempt and by CONNECTED respectively.
    @Volatile private var lastAttemptAgentOffline = false
    @Volatile private var pollingOfflineAgent = false

    // The relay's own words for the last "agent offline" verdict, which the
    // ladder repeats when it gives up on a cycle that had reached the agent.
    @Volatile private var offlineVerdict: String? = null

    // True from the moment the agent sends ONBOARD_REQUIRED until it
    // confirms ONBOARD_SUCCESS. While true, a dropped/failed socket is left
    // dropped instead of auto-retrying — the user hasn't done anything yet,
    // so there's nothing to recover; the amber "Connecting…"/error banner
    // would just be noise on top of the onboarding card. respondToOnboard
    // sends on the live socket regardless of connection-readiness, so a
    // dead socket here is genuinely unrecoverable.
    @Volatile private var onboardingPending = false

    // True when the server rejected a recent ONBOARD_SUBMIT with an ERROR
    // frame ("Invalid invite code", bad signature, etc.). On the next WS
    // failure/close we use this to distinguish a *submission* failure from
    // a transient connectivity blip: a submission failure should clear
    // [onboardingPending] and issue a fresh CONNECT to the same agent so
    // the server re-prompts ONBOARD_REQUIRED and the OnboardingFailedCard
    // can swap back to an empty OnboardRequiredCard for the user to retry.
    // A pure transient close (no submit happened) keeps suppressing auto-
    // reconnect because there's nothing to recover from until the user
    // submits again.
    @Volatile private var submitWasRejected = false

    // The card id this onboarding gate owns, from its first ONBOARD_REQUIRED
    // until it resolves, so a subsequent ERROR frame (server rejected the
    // user's invite code) can synthesize an OnboardingFailed card sharing that
    // id for in-place swap. Without this the reducer would have to scan by type
    // only and the round-trip through ChatViewModel.Error (which used to own
    // this synthesis) would surface a misleading "Connection failed" banner —
    // the connection isn't failed, only the invite-code submission was
    // rejected.
    //
    // One cycle, one card: [alignGateCardId] pins the re-prompt that follows a
    // rejection back onto this id, because adopting the server's new one would
    // change the list key and rebuild the row (keyboard included) under a user
    // already retyping. Cleared on OnboardSuccess and by every connect() bar
    // [schedulePostRejectionReconnect]'s, so it cannot outlive its gate.
    @Volatile private var lastOnboardRequiredId: String? = null

    // Monotonic counter bumped on every socket we open or retire, to suppress
    // stale callbacks from replaced sockets. Listener closures check this
    // against their captured generation and early-return if a newer socket has
    // replaced theirs. Atomic, not `@Volatile var` + `++`: the read-modify-write
    // is reachable from the OkHttp callback threads and reconnectScope at once,
    // and two overlapping opens minting the same generation would leave each
    // unable to suppress the other.
    private val socketGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // True once this connect() cycle has already tried the "stale
    // session" recovery — see [recoverFromStaleSession]'s doc. Bounds
    // the recovery to once per cycle so a server that keeps rejecting
    // the fresh (sessionId=null) CONNECT for some other reason doesn't
    // loop forever between dropping the session and retrying. Reset in
    // [connect] (a genuinely new cycle), NOT by [recoverFromStaleSession]
    // itself — the recovery re-opens the socket via [openWebSocket]
    // directly rather than [connect], precisely so this flag survives
    // its own retry.
    @Volatile private var staleSessionRecoveryAttempted = false

    // True once the Host has refused this client's OIP version (W16AAPPLE-12).
    // Terminal for the cycle: every reconnect would send the same descriptor
    // and be refused the same way, so [attemptReconnect], [retryNow] and
    // [reopenSocket] stand down. Reset only by [connect], i.e. by the user
    // trying again.
    @Volatile private var protocolRejected = false

    // Every session id this connection has been on, however it got there —
    // staged by us, or assigned by the server. Recording the server-assigned
    // ones is what makes [isWrongSession] work: without them the id a switch
    // was wrongly answered with looks like a fresh allocation. Bounded; only
    // the last few switches could be confused for the current session.
    private val knownSessionIds = LinkedHashSet<String>()

    /** Records [id] as a session this connection has been on; see [knownSessionIds]. */
    private fun rememberSession(id: String?) {
        if (id == null) return
        synchronized(knownSessionIds) {
            knownSessionIds += id
            while (knownSessionIds.size > MAX_REMEMBERED_SESSIONS) {
                knownSessionIds.remove(knownSessionIds.first())
            }
        }
    }

    /**
     * True when a CONNECTED answers with a session we did not ask for AND
     * have been on before — the host kept its own session instead of
     * honouring our `session_id`. Verified against the production relay,
     * which pins one session per socket.
     *
     * [_currentSession] is the right thing to compare against, not a
     * separately tracked "requested" id: a switch reopens the socket, and a
     * frame from the socket it replaced is dropped by the generation guard,
     * so the only CONNECT that can be answered here is the one carrying the
     * current session.
     *
     * An id we have NEVER been on is the reassignment the protocol documents
     * (the id we asked for belongs to another caller → new id), and must be
     * adopted rather than rejected.
     */
    private fun isWrongSession(reportedId: String?): Boolean {
        if (reportedId == null || reportedId == _currentSession?.sessionId) return false
        return synchronized(knownSessionIds) { reportedId in knownSessionIds }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // Broadcast semantics: every collector (state collector in ConnRepo,
    // ChatViewModel via observeEvents(), legacy connect-screen collectors)
    // sees every event. The previous Channel<ConnectionEvent>(BUFFERED) +
    // receiveAsFlow() had fan-out semantics ("one element to one collector
    // only" per Kotlin docs), so when both ConnRepo's state collector and
    // ChatViewModel's chat collector subscribed, each event went to
    // whichever one was ready first. ChatItem/Output frames that the
    // state collector won were silently dropped by its `else -> {}`.
    private val _events = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    // SharedFlow, not Flow: replay is 0, so a subscriber has to be able to
    // know it is subscribed before the socket opens — see
    // ConnectionRepositoryImpl.startCollectingEvents's onSubscription gate.
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    fun setSession(session: SessionState?) {
        _currentSession = session
        rememberSession(session?.sessionId)
    }

    /**
     * Point this connection at another conversation's server session, or at
     * none — [session] is null for a conversation the server has not
     * allocated one for yet, which makes the CONNECT below carry no
     * `session_id` and the server mint a fresh session, exactly as
     * [recoverFromStaleSession] does.
     *
     * Re-CONNECTs on the live socket instead of reopening it: the server
     * accepts a second CONNECT on an existing connection and re-points it at
     * the named session, which is the whole of a switch. [reauthenticate]
     * already depends on that. With no socket yet this only stages the
     * session for the next CONNECT.
     */
    fun switchSession(session: SessionState?) {
        _currentSession = session
        rememberSession(session?.sessionId)
        // A different session gets its own shot at stale-session recovery.
        staleSessionRecoveryAttempted = false
        if (webSocket == null) return
        FileLogger.i(LogTags.AGENT_CONN, "Switching session → sid=${session?.sessionId ?: "<new>"}")
        reopenSocket("Switching conversation")
    }

    /**
     * Drop the socket and open a new one, carrying whatever [_currentSession]
     * now holds into its CONNECT.
     *
     * A new socket, not a second CONNECT on the live one: the relay pins one
     * session per socket and echoes that session back whatever `session_id`
     * a later CONNECT asks for (observed on wss://oo.openonion.ai — we asked
     * for one conversation's id and were handed the previous one's). The
     * socket is the unit of session identity there, so moving conversation
     * means moving socket.
     *
     * Closing first is deliberate: this server keeps the old socket open
     * after ERROR, so without it two sockets would race the same agent.
     */
    @Synchronized
    private fun reopenSocket(reason: String) {
        // The caller saw a live socket, but a version refusal can have landed
        // on OkHttp's thread since; reopening would send the refused CONNECT
        // again, and Reconnecting would replace the error the user is reading.
        // @Synchronized: the same lock as stopAfterProtocolRejection, so the
        // two cannot interleave.
        if (protocolRejected) return
        isConnectionReady = false
        val addr = agentAddress
        val direct = directUrl
        // We are reopening now, so any rung still counting down is obsolete —
        // and would otherwise wake inside its own backoff, still see
        // isReconnecting (cleared only by CONNECTED/connect()/teardown()), and
        // close the socket opened just below. The attempt count is deliberately
        // left alone: this reopen can fail into the ladder, and the cap is what
        // bounds that. connect()/teardown()/retryNow() already do the same.
        reconnectJob?.cancel()
        reconnectJob = null
        isReconnecting = false
        retireSocket(reason)
        reconnectScope.launch {
            runCatchingCancellable {
                openWebSocket(addr, direct)
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_CONN, "$reason: failed to open socket: ${e.message}")
                attemptReconnect()
            }
        }
        _events.tryEmit(ConnectionEvent.Reconnecting)
    }

    /**
     * Close the live socket on the way to opening its replacement, bumping
     * [socketGeneration] *first* so the old listener is already stale when the
     * peer answers. Bumping inside [openWebSocket] alone was not enough
     * wherever the open is deferred behind a `launch` while the close runs
     * synchronously: suppression then only won if the dispatch hop beat a
     * network round-trip, and losing that race surfaced the replaced socket's
     * own close as a [ConnectionEvent.Disconnected].
     */
    private fun retireSocket(reason: String) {
        // Its CONNECT can no longer be answered, so nothing is waiting on it.
        awaitingConnected = false
        connectedWaitJob?.cancel()
        connectedWaitJob = null
        settlePendingExecs("The agent connection was replaced")
        socketGeneration.incrementAndGet()
        webSocket?.close(1000, reason)
        webSocket = null
    }

    fun connect(agentAddress: String, directUrl: String? = null) =
        connect(agentAddress, directUrl, carriedOnboardCardId = null)

    /**
     * @param carriedOnboardCardId the gate card id this cycle inherits, non-null
     *   only for [schedulePostRejectionReconnect] — every other caller is
     *   starting a genuinely new cycle and gets a clean [lastOnboardRequiredId].
     *   Passed rather than restored around the call so no frame can arrive on
     *   the new socket in between.
     */
    private fun connect(agentAddress: String, directUrl: String?, carriedOnboardCardId: String?) {
        synchronized(this) {
            keys = keyManager.loadOrGenerate()
            this.agentAddress = agentAddress
            this.directUrl = directUrl
            // Cancel any reconnect scheduled by a prior connect() cycle before
            // reassigning reconnectScope below — otherwise a stale delayed
            // reconnect can still fire and race a second socket into
            // existence using the fields just reassigned here.
            reconnectJob?.cancel()
            reconnectScope.cancel()
            reconnectAttempts = 0
            isReconnecting = false
            reachedAgentThisCycle = false
            neverReachedReason = null
            lastAttemptAgentOffline = false
            pollingOfflineAgent = false
            isConnectionReady = false
            onboardingPending = false
            submitWasRejected = false
            // New WS cycle starts clean: a previously-rejected gate has no
            // meaning on a fresh session, and the generation-counter check
            // in the listener closures will naturally reject any late
            // callbacks from the previous socket. The one exception is the
            // reconnect that re-prompts the same unresolved gate.
            lastOnboardRequiredId = carriedOnboardCardId
            // Same "fresh cycle" reasoning as onboardingPending/submitWasRejected
            // above: a new explicit connect() gets its own chance at the
            // stale-session recovery, independent of whether a previous
            // cycle already used (and possibly exhausted) its one shot.
            staleSessionRecoveryAttempted = false
            protocolRejected = false
            reconnectScope = CoroutineScope(SupervisorJob() + reconnectDispatcher)

            openWebSocket(agentAddress, directUrl)
        }
    }

    private fun sendConnectMessage(ws: WebSocket, capturedAgentAddress: String) {
        val k = keys ?: return
        // Single timestamp used for both the signed payload AND the wire
        // envelope: a split-second skew between the two was the seam the
        // previous revision introduced when it called
        // System.currentTimeMillis() once inside signPayload and once again
        // for the envelope. The server's auth.py:180 verifies the signature
        // over the payload bytes — so a payload-timestamp of TS1 paired with
        // an envelope-timestamp of TS1+1 silently fails signature check.
        // Snapshot isDirect as well: switching it mid-call would produce
        // the same mismatch shape for the "to" field.
        // The server's time, not the device's (W16AAPPLE-90); see nextConnectTimestamp.
        val timestamp = nextConnectTimestamp(hostClock.nowSeconds())
        val directNow = isDirect
        val sessionId = _currentSession?.sessionId
        val payload = ConnectPayload(
            timestamp = timestamp,
            to = if (!directNow) capturedAgentAddress else null,
            nonce = SignedCommand.newNonce()
        )

        // Signed exactly as serialised, so the bytes the Host canonicalises
        // are the bytes signed here, signed_commands included.
        val signature = signPayload(json.encodeToJsonElement(payload).jsonObject) ?: return

        val connectMsg = ConnectMessage(
            payload = payload,
            from = k.address,
            signature = signature,
            timestamp = timestamp,
            to = if (!directNow) capturedAgentAddress else null,
            sessionId = sessionId,
            lastMsgId = replayCursor?.takeIf { it.sessionId == sessionId }?.msgId
        )

        val msgStr = json.encodeToString(connectMsg)
        FileLogger.i(LogTags.AGENT_CONN, "CONNECT → $capturedAgentAddress sid=${connectMsg.sessionId ?: "<new>"}")
        awaitingConnected = true
        ws.send(msgStr)
        if (socketIsReconnect) awaitConnectedOrRetry()
    }

    /**
     * Give a reconnect's CONNECT [CONNECTED_TIMEOUT_MS] to be answered, then
     * treat the silence as a failed rung (W16AAPPLE-82). The Host keeps a
     * socket open whatever it thinks of the CONNECT, so without this a reconnect
     * it never answers sat on "Reconnecting…" until the user intervened.
     *
     * Only for reconnects: a first connect is already bounded by
     * ConnectToAgentUseCase's own timeout, and a gate (ONBOARD_REQUIRED) answers
     * in place of CONNECTED, which [trackOnboardingState] treats as an answer.
     */
    private fun awaitConnectedOrRetry() {
        val generation = socketGeneration.get()
        connectedWaitJob?.cancel()
        connectedWaitJob = reconnectScope.launch {
            delay(CONNECTED_TIMEOUT_MS)
            synchronized(this@AgentConnection) {
                if (!awaitingConnected || generation != socketGeneration.get()) return@launch
                FileLogger.w(LogTags.AGENT_CONN, "No CONNECTED within ${CONNECTED_TIMEOUT_MS}ms — treating the reconnect as failed")
                retireSocket("No answer to CONNECT")
            }
            attemptReconnect()
        }
    }

    /**
     * Canonicalize a payload and Ed25519-sign it. Returns the hex signature.
     * Returns null and logs an error when keys aren't loaded — both CONNECT
     * and ONBOARD_SUBMIT share this exact "no keys" dead-code path, and the
     * previous revision handled it inconsistently (silent return in
     * sendConnectMessage, log-and-return in respondToOnboard).
     *
     * Shared between [sendConnectMessage] and [respondToOnboard] so a future
     * change to the canonicalization or signing scheme has to be applied in
     * one place.
     */
    private fun signPayload(payloadFields: Map<String, Any>): String? {
        val k = keys ?: run {
            FileLogger.e(LogTags.AGENT_CONN, "signPayload: no keys loaded, dropping")
            return null
        }
        val canonical = keyManager.canonicalJson(payloadFields)
        return signer(k, canonical)
    }

    /**
     * [command] as a v2 signed command (see [SignedCommand]), ready to send,
     * or null when there are no keys to sign with. The CONNECT on every socket
     * enables the Host's command gate, so an unsigned command would be refused:
     * callers drop the frame rather than send it unsigned.
     *
     * @param to the agent CONNECT named; the Host refuses any other recipient.
     */
    private inline fun <reified T> signedCommand(command: T, to: String = agentAddress): String? {
        val k = keys ?: run {
            FileLogger.e(LogTags.AGENT_CONN, "signedCommand: no keys loaded, dropping")
            return null
        }
        val frame = SignedCommand.envelope(
            command = json.encodeToJsonElement(command).jsonObject,
            to = to,
            timestamp = hostClock.nowSeconds(),
            nonce = SignedCommand.newNonce(),
            from = k.address,
            sign = { payload -> signer(k, keyManager.canonicalJson(payload)) }
        )
        return json.encodeToString(JsonObject.serializer(), frame)
    }

    /** Sign and send an application command on the live socket. */
    private inline fun <reified T> sendSigned(command: T) {
        signedCommand(command)?.let { webSocket?.send(it) }
    }

    /**
     * Send one direct EXEC command and wait for its matching EXEC_RESULT.
     *
     * EXEC is never queued or retried: once the socket accepts the frame, a
     * timeout or disconnect cannot tell us whether the remote tool ran.
     */
    suspend fun executeTool(
        execId: String,
        tool: String,
        args: JsonObject,
        recipient: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS
    ): ExecOutcome {
        val socket = webSocket?.takeIf { isConnectionReady }
            ?: return ExecOutcome.NotSent("The agent connection is not ready")

        val message = createExecMessage(
            execId = execId,
            tool = tool,
            args = args,
            recipient = recipient
        ) ?: return ExecOutcome.NotSent("The client identity is unavailable")

        val result = CompletableDeferred<ExecOutcome>()
        if (pendingExecs.putIfAbsent(execId, result) != null) {
            return ExecOutcome.NotSent("Duplicate EXEC id: $execId")
        }

        val accepted = socket.send(message)
        if (!accepted) {
            pendingExecs.remove(execId, result)
            return ExecOutcome.NotSent("The WebSocket rejected the EXEC frame")
        }

        FileLogger.i(LogTags.AGENT_CONN, "EXEC → tool=$tool exec_id=${execId.take(8)}")
        return awaitExecResult(execId, result, timeoutMs)
    }

    /** Build the signed EXEC frame through the shared command envelope. */
    private fun createExecMessage(
        execId: String,
        tool: String,
        args: JsonObject,
        recipient: String
    ): String? =
        signedCommand(ExecMessage(execId = execId, tool = tool, args = args, to = recipient), to = recipient)

    /** Wait for EXEC result, if timeout remove pending EXEC commands. */
    private suspend fun awaitExecResult(
        execId: String,
        result: CompletableDeferred<ExecOutcome>,
        timeoutMs: Long
    ): ExecOutcome =
        try {
            // Generic signed-command refusals intentionally wait for the
            // normal timeout. They may belong to an INPUT, so treating every
            // `unauthorized: ...` frame as an EXEC failure would blame the
            // wrong card. Known EXEC-only refusals are settled above.
            withTimeoutOrNull(timeoutMs) {
                result.await()
            } ?: run {
                currentCoroutineContext().ensureActive()
                ExecOutcome.Unknown("No EXEC_RESULT received within ${timeoutMs}ms")
            }
        } finally {
            pendingExecs.remove(execId, result)
        }

    /** Complete the caller waiting for this result. */
    private fun completeExecResult(event: ConnectionEvent.ExecResultReceived) {
        val outcome = when (event.status) {
            "success" -> ExecOutcome.Success(
                result = event.result.orEmpty(),
                durationMs = event.durationMs
            )
            "error" -> ExecOutcome.Error(
                message = event.error.orEmpty(),
                durationMs = event.durationMs
            )
            else -> ExecOutcome.Unknown("Unsupported EXEC_RESULT status: ${event.status}")
        }

        val waiter = pendingExecs.remove(event.execId)
        if (waiter == null) {
            FileLogger.w(
                LogTags.AGENT_CONN,
                "Ignoring EXEC_RESULT for unknown exec_id=${event.execId.take(8)}"
            )
        } else {
            waiter.complete(outcome)
        }
    }

    /**
     * Some EXEC refusals arrive as a plain ERROR without an exec_id. The Host
     * uses these for requests that cannot be accepted at all, so complete the
     * waiting EXEC calls instead of leaving them to time out.
     *
     * @return true when the ERROR was about EXEC alone and has now been shown
     *   on its cards, so the generic chat error would only repeat it. That is
     *   `provider_station_only` (session.py's EXEC branch). "authenticate first"
     *   refuses every command on the socket, so it settles the cards but still
     *   returns false: a message refused alongside them must still be reported.
     */
    private fun failPendingExecsForRefusal(event: ConnectionEvent.ConnectionError): Boolean {
        // Keep this list narrow. A generic `unauthorized: ...` ERROR may have
        // rejected an INPUT rather than an EXEC, so matching every refusal
        // would incorrectly settle unrelated tool cards.
        val providerRestriction = event.code.equals("provider_station_only", ignoreCase = true)
        val authenticationRequired = event.message.contains("authenticate first", ignoreCase = true)
        if (!providerRestriction && !authenticationRequired) return false

        val outcome = ExecOutcome.Error(event.message)
        var settled = false
        pendingExecs.entries.forEach { (execId, waiter) ->
            if (pendingExecs.remove(execId, waiter)) {
                waiter.complete(outcome)
                settled = true
            }
        }
        return settled && providerRestriction
    }

    /** Release every caller waiting on a socket that can no longer answer. */
    private fun settlePendingExecs(reason: String) {
        val unknown = ExecOutcome.Unknown(reason)
        pendingExecs.entries.forEach { (execId, waiter) ->
            // Remove the exact entry before completing it. A concurrent
            // EXEC added after this iteration began must not be removed by a
            // broad clear() without also being notified.
            if (pendingExecs.remove(execId, waiter)) {
                waiter.complete(unknown)
            }
        }
    }

    /**
     * Send a message to the agent.
     * If connection is not ready, queues the message for later delivery.
     */
    fun sendMessage(prompt: String, agentAddress: String, images: List<String>? = null, files: List<FileAttachment>? = null) {
        if (!isConnectionReady || webSocket == null) {
            // The sink bounds its own size (see MAX_PENDING_MESSAGES), so a
            // stuck reconnect loop plus repeated sends can't queue forever —
            // newest kept, oldest dropped. files_json (PendingMessageEntity,
            // MIGRATION_8_9) carries files through the same way images_json
            // already does, so nothing is dropped on this path.
            FileLogger.w(LogTags.AGENT_CONN, "Connection not ready, queuing message${if (!files.isNullOrEmpty()) " (${files.size} file(s))" else ""}")
            pendingMessageSink.enqueue(agentAddress, _currentSession?.sessionId, prompt, images, files)
            return
        }

        // A socket that is closing still passes the readiness check above and
        // then refuses the write. Falling back to the queue keeps the message
        // instead of losing it in that window — it goes out on the next
        // CONNECTED like any other queued one.
        if (!sendMessageInternal(prompt, agentAddress, images, files)) {
            FileLogger.w(LogTags.AGENT_CONN, "Socket refused the write, queuing instead")
            pendingMessageSink.enqueue(agentAddress, _currentSession?.sessionId, prompt, images, files)
        }
    }

    /** @return false when the socket refused the write, so the caller can queue it. */
    private fun sendMessageInternal(prompt: String, agentAddress: String, images: List<String>? = null, files: List<FileAttachment>? = null): Boolean {
        val inputId = java.util.UUID.randomUUID().toString()
        synchronized(outstandingInputIds) {
            // Bounded: a run whose OUTPUT never arrives would otherwise leak an
            // id per send for the life of the socket.
            if (outstandingInputIds.size >= MAX_OUTSTANDING_INPUT_IDS) {
                outstandingInputIds.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            }
            outstandingInputIds.add(inputId)
        }

        val msg = InputMsg(
            inputId = inputId,
            prompt = prompt,
            to = if (!isDirect) agentAddress else null,
            images = images,
            files = files
        )

        val msgStr = signedCommand(msg, to = agentAddress)
        FileLogger.i(LogTags.AGENT_CONN, "INPUT → ${LogSanitizer.contentSummary(prompt)}")
        val accepted = msgStr != null && webSocket?.send(msgStr) == true
        if (!accepted) {
            // The id would otherwise sit in the set forever, and an OUTPUT
            // arriving for some other send could be matched against it.
            synchronized(outstandingInputIds) { outstandingInputIds.remove(inputId) }
        }
        return accepted
    }

    private fun flushPendingMessages() {
        // Only what belongs on the session now established — see the sink.
        // Drained one at a time rather than read out as a list: a queued
        // attachment is base64, so the whole outbox used to be decoded at once
        // and then re-encoded per send.
        var flushed = 0
        pendingMessageSink.drain(agentAddress, _currentSession?.sessionId) { pending ->
            flushed++
            sendMessageInternal(pending.prompt, agentAddress, pending.images, pending.files)
        }
        if (flushed > 0) FileLogger.i(LogTags.AGENT_CONN, "Flushed $flushed pending messages")
    }

    fun respond(answer: String) {
        sendSigned(AskUserResponse(answer = answer))
    }

    /**
     * Ask the agent to gracefully stop its current run — see
     * [InterruptMessage]'s own doc. Fire-and-forget on whatever socket is
     * live, same as [respond]/[respondToApproval]: no [isConnectionReady]
     * gate needed, since the server only requires a live connection to
     * receive this, not a completed CONNECTED handshake.
     */
    fun interrupt() {
        FileLogger.i(LogTags.AGENT_CONN, "INTERRUPT → socket=${webSocket != null}")
        sendSigned(InterruptMessage())
    }

    fun respondToApproval(approved: Boolean, scope: String = "once", mode: String? = null, feedback: String? = null) {
        sendSigned(ApprovalResponse(approved = approved, scope = scope, mode = mode, feedback = feedback))
    }

    /** Reply to a [ChatItem.PlanReviewItem][ai.openonion.oochat.domain.model.ChatItem.PlanReviewItem] checkpoint. */
    fun respondToPlanReview(message: String) {
        sendSigned(PlanReviewResponse(message = message))
    }

    /** Reply to a [ChatItem.UlwTurnsReachedItem][ai.openonion.oochat.domain.model.ChatItem.UlwTurnsReachedItem] checkpoint. */
    fun respondToUlwTurnsReached(action: String, turns: Int? = null, mode: String? = null) {
        sendSigned(UlwResponse(action = action, turns = turns, mode = mode))
    }

    /**
     * Ask the agent to switch tool-approval mode — see [ModeChangeMessage]'s
     * own doc for the wire shape and the server code that reads it.
     * Fire-and-forget on whatever socket is live, same as
     * [respond]/[respondToApproval]: the server forwards this straight into
     * the running agent's mailbox, no CONNECTED handshake of its own needed.
     *
     * @param mode "safe" | "plan" | "accept_edits" | "ulw" on a legacy Host;
     *   "read-only" | "auto" | "full-access" on a Host that advertised
     *   `session_modes`, which answers at once with `mode_changed` or `ERROR`.
     * @param turns ULW's autonomous-turn budget; ignored by the server for
     *   every other mode, so callers pass null there. Never sent for
     *   full access: the Host grants that budget, no client field can.
     */
    fun setMode(mode: String, turns: Int? = null) {
        FileLogger.i(LogTags.AGENT_CONN, "mode_change → $mode${turns?.let { " ($it turns)" } ?: ""}")
        sendSigned(ModeChangeMessage(mode = mode, turns = turns))
    }

    /**
     * Ask the server whether [sessionId] is still running — see
     * [SessionStatusQuery]'s own doc. Fire-and-forget on whatever socket is
     * live, same as [respond]/[interrupt]: this only reads registry state,
     * it doesn't need the authenticated CONNECTED handshake to have landed.
     * The reply arrives asynchronously as a
     * [ConnectionEvent.SessionStatusReceived] on [events].
     */
    fun querySessionStatus(sessionId: String) {
        FileLogger.i(LogTags.AGENT_CONN, "SESSION_STATUS → ${sessionId.take(8)}...")
        webSocket?.send(json.encodeToString(SessionStatusQuery(session = SessionStatusQuerySession(sessionId = sessionId))))
    }

    /**
     * Reply to an ONBOARD_REQUIRED gate from the agent.
     *
     * Ed25519-signed the same way [sendConnectMessage] signs CONNECT — the
     * server's handle_onboard_submit re-verifies a signature on this exact
     * message regardless of trust level, so an unsigned/mis-typed frame is
     * silently ignored rather than acted on (see [OnboardSubmitMessage]'s
     * doc comment).
     *
     * Always sent on the live socket (when one exists). The server only
     * requires a live socket and a valid signature — no prior CONNECTED
     * needed.
     *
     * @param method "invite_code" or "payment" — kept for callers' own
     *   branching; the wire payload itself just carries whichever of
     *   [inviteCode]/[payment] is non-null, matching the real protocol.
     *   Must be consistent with which of [inviteCode]/[payment] is non-null
     *   — mismatched pairs are rejected with [IllegalArgumentException]
     *   rather than silently submitting an empty payload.
     * @param inviteCode the code the user entered (when method == "invite_code")
     * @param payment the amount the user confirmed paying (when method == "payment")
     */
    fun respondToOnboard(
        method: String,
        inviteCode: String? = null,
        payment: Double? = null
    ) {
        require(method == "invite_code" || method == "payment") {
            "method must be \"invite_code\" or \"payment\", got \"$method\""
        }
        require((method == "invite_code") == (inviteCode != null) && (method == "payment") == (payment != null)) {
            "respondToOnboard: method=\"$method\" must match the non-null " +
                "argument (invite_code ↔ inviteCode!=null, payment ↔ payment!=null), " +
                "got inviteCode=$inviteCode payment=$payment"
        }

        val k = keys ?: run {
            FileLogger.e(LogTags.AGENT_CONN, "respondToOnboard: no keys available, dropping")
            return
        }
        val socket = webSocket ?: run {
            FileLogger.e(LogTags.AGENT_CONN, "respondToOnboard: no live socket, dropping — caller should reconnect")
            return
        }

        val timestamp = hostClock.nowSeconds()

        val payloadFields = buildMap<String, Any> {
            put("timestamp", timestamp)
            if (inviteCode != null) put("invite_code", inviteCode)
            if (payment != null) put("payment", payment)
        }
        val signature = signPayload(payloadFields) ?: return

        val response = OnboardSubmitMessage(
            payload = OnboardSubmitPayload(timestamp = timestamp, inviteCode = inviteCode, payment = payment),
            from = k.address,
            signature = signature
        )

        FileLogger.i(LogTags.AGENT_CONN, "ONBOARD_SUBMIT → method=$method")
        socket.send(json.encodeToString(response))
    }

    /** The user chose to disconnect: drop the socket *and* the queued outbox. */
    fun disconnect() {
        pendingMessageSink.clear(agentAddress)
        teardown()
    }

    /**
     * Teardown for a discarded owner (see ConnectionRepositoryImpl.close),
     * as opposed to a user-initiated [disconnect]. Leaves the outbox alone:
     * clearing it hits Room, which throws when called from onCleared() on the
     * main thread, and a queued message is meant to outlive the connection
     * that queued it anyway — that is the whole point of persisting it.
     */
    fun teardown() {
        synchronized(this) {
            isReconnecting = false
            isConnectionReady = false
            settlePendingExecs("The agent connection was closed")
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
            webSocket?.close(1000, "User disconnected")
            webSocket = null
            outstandingInputIds.clear()
            reconnectScope.cancel()
        }
    }

    fun isConnected(): Boolean = webSocket != null && isConnectionReady

    /**
     * End this cycle after the Host refused our OIP version. The Host keeps
     * the socket open after its ERROR (ws_router/session.py returns to the
     * read loop), so the client closes it: left open, its eventual drop would
     * climb the reconnect ladder and be refused once per rung. Retiring it
     * bumps [socketGeneration], so that close's own onClosed is ignored rather
     * than reported as a [ConnectionEvent.Disconnected] over the real reason.
     */
    private fun stopAfterProtocolRejection() {
        synchronized(this) {
            protocolRejected = true
            isReconnecting = false
            isConnectionReady = false
            reconnectJob?.cancel()
            reconnectJob = null
            onboardingPending = false
            retireSocket("Unsupported OIP protocol")
        }
    }

    fun reset() {
        _currentSession = null
        replayCursor = null
        synchronized(seenFrames) { seenFrames.clear() }
        outstandingInputIds.clear()
        pendingMessageSink.clear(agentAddress)
    }

    /**
     * Retry the pending reconnect now instead of waiting out its backoff.
     *
     * Returning to the foreground is new information: the socket was almost
     * certainly aborted *because* the app was backgrounded, so the reason it
     * failed has just gone away. A delay computed for a flaky network is the
     * wrong thing to sit through at the one moment the user is watching.
     * No-op unless a reconnect is already pending — this does not start one.
     */
    fun retryNow() {
        if (!isReconnecting || protocolRejected) return
        FileLogger.i(LogTags.AGENT_CONN, "Reconnect: retrying now (foreground)")
        reconnectJob?.cancel()
        reconnectAttempts = 0
        // A fresh ladder, so if it runs out again the verdict is said again
        // over the "Reconnecting…" it showed meanwhile.
        pollingOfflineAgent = false
        reconnectJob = reconnectScope.launch {
            runCatchingCancellable {
                // Re-checked under stopAfterProtocolRejection's lock: retryNow
                // runs on the main thread and the refusal lands on OkHttp's, so
                // it can arrive after the guard above but before this job exists
                // to be cancelled, and would be answered with one more CONNECT.
                synchronized(this@AgentConnection) {
                    if (!protocolRejected) {
                        retireSocket("Reconnecting")
                        openWebSocket(agentAddress, directUrl)
                    }
                }
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_CONN, "Immediate reconnect failed: ${e.message}")
                attemptReconnect()
            }
        }
    }

    /**
     * Schedule the next rung of the reconnect ladder, or give up.
     *
     * The timing is the same whatever failed: a dropped socket and a relay
     * saying "Agent not connected" are both "might come back, might not", and
     * one ladder is cheaper to reason about than two. What differs is the
     * verdict — when the server explained why we never got through, the give-up
     * repeats that explanation instead of a bare [ConnectionEvent.Disconnected],
     * which the UI reads (correctly) as a network drop.
     */
    private fun attemptReconnect() {
        if (protocolRejected) {
            FileLogger.i(LogTags.AGENT_CONN, "OIP version rejected — not reconnecting")
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            isConnectionReady = false
            if (lastAttemptAgentOffline && !onboardingPending) {
                // The relay says the agent itself is gone, which is the one
                // failure that ends by the agent coming back on its own (a
                // Host restart, a laptop waking). Say so once, then keep
                // looking at a slow pace instead of waiting for the user
                // (W16AAPPLE-79).
                if (!pollingOfflineAgent) {
                    FileLogger.w(LogTags.AGENT_CONN, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS), agent offline — polling every ${OFFLINE_POLL_INTERVAL_MS}ms")
                    reportGaveUp()
                }
                pollOfflineAgent()
                return
            }
            FileLogger.w(LogTags.AGENT_CONN, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            reportGaveUp()
            return
        }

        isReconnecting = true
        isConnectionReady = false
        reconnectAttempts++
        val delayMs = reconnectBackoffMs(reconnectAttempts)

        FileLogger.i(LogTags.AGENT_CONN, "Reconnect #$reconnectAttempts in ${delayMs}ms")
        _events.tryEmit(ConnectionEvent.Reconnecting)

        reconnectJob = reconnectScope.launch {
            delay(delayMs)
            if (!isReconnecting) return@launch
            runCatchingCancellable {
                retireSocket("Reconnecting")
                openWebSocket(agentAddress, directUrl)
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_CONN, "Reconnect failed: ${e.message}")
                attemptReconnect()
            }
        }
    }

    /**
     * The ladder's last word. When the server explained why we never got
     * through, the give-up repeats that explanation instead of a bare
     * [ConnectionEvent.Disconnected], which the UI reads (correctly) as a
     * network drop. On a cycle that had already reached the agent the
     * explanation goes out as [ConnectionEvent.ReconnectRefused]: a plain
     * ConnectionError there is read as a business error on a live session and
     * leaves the banner on "Reconnecting…".
     */
    private fun reportGaveUp() {
        val reason = neverReachedReason
        val offline = offlineVerdict?.takeIf { lastAttemptAgentOffline }
        _events.tryEmit(
            when {
                reason != null -> ConnectionEvent.ConnectionError(reason)
                offline != null -> ConnectionEvent.ReconnectRefused(offline)
                else -> ConnectionEvent.Disconnected
            }
        )
    }

    /**
     * Try an offline agent again every [OFFLINE_POLL_INTERVAL_MS] once the
     * ladder has run out (W16AAPPLE-79). Quiet: no [ConnectionEvent.Reconnecting]
     * per try, so the screen keeps showing why it is not connected rather than
     * flicking between that and "Reconnecting…" once a minute.
     *
     * Only while the app is on screen. A try that comes due in the background
     * is skipped, and [isReconnecting] stays set so [retryNow], which the chat
     * screen calls on its way back, goes at once with a fresh ladder.
     */
    private fun pollOfflineAgent() {
        pollingOfflineAgent = true
        isReconnecting = true
        reconnectJob = reconnectScope.launch {
            delay(OFFLINE_POLL_INTERVAL_MS)
            if (!isReconnecting) return@launch
            if (!isAppVisible()) {
                FileLogger.i(LogTags.AGENT_CONN, "Agent offline, app in background — waiting for the foreground to try again")
                return@launch
            }
            FileLogger.i(LogTags.AGENT_CONN, "Agent offline — trying again")
            runCatchingCancellable {
                // Same lock as stopAfterProtocolRejection; see retryNow.
                synchronized(this@AgentConnection) {
                    if (!protocolRejected) {
                        retireSocket("Polling an offline agent")
                        openWebSocket(agentAddress, directUrl)
                    }
                }
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_CONN, "Offline poll failed: ${e.message}")
                attemptReconnect()
            }
        }
    }

    /**
     * End a reconnect whose CONNECT the server answered with ERROR
     * (W16AAPPLE-82). The Host keeps the socket open after an ERROR, so no
     * close ever arrived to climb the ladder, and the repository read the
     * ERROR as a business error on a live session: the screen sat on
     * "Reconnecting…" for good.
     *
     * The relay's "Agent not connected" is a failed rung like any dropped
     * socket, since the agent can be back in a moment. Anything else is the
     * Host refusing us (trust, signature, the clock), which another identical
     * CONNECT will not change: the ladder stops and the refusal is reported
     * as [ConnectionEvent.ReconnectRefused], so the screen shows why and offers
     * Reconnect.
     */
    private fun endRefusedReconnect(message: String) {
        if (lastAttemptAgentOffline) {
            synchronized(this) { retireSocket("Agent offline") }
            attemptReconnect()
            return
        }
        FileLogger.w(LogTags.AGENT_CONN, "Reconnect refused by the server — stopping the ladder")
        synchronized(this) {
            isReconnecting = false
            isConnectionReady = false
            reconnectJob?.cancel()
            reconnectJob = null
            retireSocket("Reconnect refused")
        }
        _events.tryEmit(ConnectionEvent.ReconnectRefused(message))
    }

    /**
     * Issue a fresh CONNECT to the same agent when an ONBOARD_SUBMIT was
     * rejected by the server. The re-CONNECT is scheduled on [reconnectScope]
     * (not the OkHttp IO thread) so the OkHttp listener can return without
     * re-entering connection state from within a callback. The server
     * re-prompts ONBOARD_REQUIRED, the chat pipeline replaces the
     * OnboardingFailed card with a fresh OnboardRequired card, and the
     * user can retry without manual intervention.
     *
     * The old socket is closed before opening a new one: this server keeps
     * the socket open after sending ERROR (verified against
     * production oo.openonion.ai — PING/PONG continued for minutes after
     * "Invalid invite code"), so without an explicit close we'd race two
     * sockets against the same agent. Closing the old one before opening
     * the new one keeps the listener surface bounded.
     *
     * Clears [onboardingPending] and [submitWasRejected] atomically with
     * the new connect: if the new CONNECT fails to even open the socket,
     * the [connect] call re-arms both flags from scratch so a second
     * transient close doesn't trigger another re-prompt loop.
     */
    private fun schedulePostRejectionReconnect() {
        FileLogger.i(LogTags.AGENT_CONN, "Onboarding submit rejected — reconnecting to re-prompt ONBOARD_REQUIRED")
        val addr = agentAddress
        val direct = directUrl
        // The gate is still the same unresolved one, so its card id rides
        // through the reconnect — see [lastOnboardRequiredId].
        val carriedCardId = lastOnboardRequiredId
        onboardingPending = false
        submitWasRejected = false
        isConnectionReady = false
        // Stale-callback guard, see retireSocket.
        retireSocket("Onboarding rejected, reconnecting")
        reconnectScope.launch {
            runCatchingCancellable {
                connect(addr, direct, carriedCardId)
            }.onFailure { e ->
                FileLogger.e(LogTags.AGENT_CONN, "Post-rejection reconnect failed: ${e.message}")
            }
        }
    }

    /**
     * Recover from the server rejecting a CONNECT with "Session is
     * already attached to another connection" — the same heuristic
     * [StaleSessionDetector] already backs for [ConnectToAgentUseCase]'s
     * explicit-connect retry ([ConnectToAgentUseCase.tryConnectOnce]).
     * That use-case-level recovery only runs on the *first* connect
     * attempt of a session, while it's still racing the 30s timeout —
     * it has no way to see a rejection that arrives after this class's
     * own auto-reconnect ladder (attemptReconnect/openWebSocket) has
     * already re-opened the socket and resent CONNECT on its own,
     * outside any use-case call. Confirmed against a real device: the
     * reconnect ladder climbs correctly, the socket opens, CONNECT is
     * rejected as stale — and then nothing recovers it, because this
     * server keeps the socket open after ERROR (see
     * [schedulePostRejectionReconnect]'s doc for the same server
     * behavior), so no onClosed/onFailure ever fires to retry. The user
     * is left with a socket that looks "open" but will never receive
     * CONNECTED; sendMessage silently queues forever.
     *
     * Drops the in-memory session (so the next CONNECT carries no
     * session_id) and re-opens via [openWebSocket] directly — not
     * [connect] — so this doesn't reset unrelated per-cycle state
     * (onboarding flags, reconnect-attempt counter) and so
     * [staleSessionRecoveryAttempted] survives the retry it itself
     * triggers; see that field's doc for the loop-prevention contract.
     */
    private fun recoverFromStaleSession() {
        staleSessionRecoveryAttempted = true
        FileLogger.w(
            LogTags.AGENT_CONN,
            "Stale session rejected by server (sid=${_currentSession?.sessionId}) — " +
                "dropping it and retrying with sessionId=null"
        )
        _currentSession = null
        reopenSocket("Stale session, reconnecting")
    }

    private fun openWebSocket(agentAddress: String, directUrl: String?) {
        val wsUrl = if (directUrl != null) {
            isDirect = true
            val baseUrl = directUrl.replace(Regex("^https?://"), "")
            val protocol = if (directUrl.startsWith("https")) "wss" else "ws"
            "$protocol://$baseUrl/ws"
        } else {
            isDirect = false
            // Convert relay URL to wss:// if needed
            val effectiveRelayUrl = if (relayUrl.startsWith("https://")) {
                "wss://${relayUrl.removePrefix("https://")}"
            } else if (relayUrl.startsWith("http://")) {
                "ws://${relayUrl.removePrefix("http://")}"
            } else {
                relayUrl
            }
            "$effectiveRelayUrl/ws/input"
        }

        FileLogger.i(LogTags.AGENT_CONN, "WS open → $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        // Capture the address into the listener closure: an orphan listener
        // on a previously-orphaned socket must sign CONNECTs for the agent
        // *this* socket was opened with, not whatever value the volatile
        // instance field happens to hold at the moment the orphan callback
        // fires (a switch-agent flow would otherwise target the wrong party
        // and silently fail server-side signature verification). Today
        // ConnectionRepositoryImpl always builds a fresh AgentConnection
        // per connect, so this brittleness is latent — the parameter
        // snapshot makes the per-socket contract explicit and reviewable.
        val addressForThisSocket = agentAddress

        // Stale-callback guard, see socketGeneration doc.
        val generationForThisSocket = socketGeneration.incrementAndGet()

        // A new attempt: nothing has been asked or answered on it yet.
        awaitingConnected = false
        lastAttemptAgentOffline = false
        socketIsReconnect = reachedAgentThisCycle

        webSocket = webSocketFactory.create(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Deliberately does NOT reset the reconnect ladder — see the
                // CONNECTED branch of [handleMessage]. All this proves is that
                // a socket exists; whether the agent is on the other end of it
                // is the next frame's news.
                FileLogger.i(LogTags.AGENT_CONN, "WS opened, sending CONNECT")
                // Before the CONNECT, so even the first signature on this
                // socket carries the server's time. A replaced socket's reading
                // is not this connection's any more.
                if (generationForThisSocket == socketGeneration.get()) hostClock.observe(response)
                sendConnectMessage(webSocket, addressForThisSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Stale-callback guard, see socketGeneration doc. Every other
                // callback had this; onMessage did not, so a frame still in
                // flight on a socket we had already replaced was processed as
                // if it were current — which is exactly what a conversation
                // switch now produces.
                if (generationForThisSocket != socketGeneration.get()) {
                    FileLogger.i(LogTags.AGENT_CONN, "Ignoring frame from a replaced socket (gen=$generationForThisSocket, current=${socketGeneration.get()})")
                    return
                }
                handleMessage(text, addressForThisSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Stale-callback guard, see socketGeneration doc.
                if (generationForThisSocket != socketGeneration.get()) {
                    FileLogger.i(LogTags.AGENT_CONN, "Ignoring stale onFailure (gen=$generationForThisSocket, current=${socketGeneration.get()})")
                    return
                }
                // Rate-limited for the same reason as the ERROR line in
                // handleMessage: one identical failure per ladder rung.
                FileLogger.eRepeating(LogTags.AGENT_CONN, "WS failed: ${t.message}")
                isConnectionReady = false
                settlePendingExecs("The agent connection failed")
                // Server keeps socket open after ERROR, so error reconnect is
                // scheduled from handleMessage, not here; see schedulePostRejectionReconnect doc.
                //
                // Always climb the ladder, regardless of `isReconnecting` — that
                // flag is set true by the attemptReconnect() call that opened
                // *this* socket and only resets on onOpen, so gating on it here
                // stopped every reconnect socket's own failure from retrying.
                when {
                    onboardingPending -> {
                        FileLogger.i(LogTags.AGENT_CONN, "Onboarding pending — suppressing auto-reconnect")
                    }
                    else -> attemptReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Stale-callback guard, see socketGeneration doc.
                if (generationForThisSocket != socketGeneration.get()) {
                    FileLogger.i(LogTags.AGENT_CONN, "Ignoring stale onClosed $code (gen=$generationForThisSocket, current=${socketGeneration.get()})")
                    return
                }
                FileLogger.i(LogTags.AGENT_CONN, "WS closed: $code $reason")
                isConnectionReady = false
                settlePendingExecs("The agent connection closed")
                // See onFailure's comment above — same fix, same reason:
                // always climb the ladder on a non-deliberate close.
                when {
                    code == 1000 -> _events.tryEmit(ConnectionEvent.Disconnected)
                    onboardingPending -> {
                        FileLogger.i(LogTags.AGENT_CONN, "Onboarding pending — suppressing auto-reconnect")
                    }
                    else -> attemptReconnect()
                }
            }
        })
    }

    /**
     * Pure state-tracking: flips [onboardingPending] in response to
     * OnboardRequired/OnboardSuccess, and records the most recent
     * OnboardRequired's id so a later ERROR frame can synthesize an
     * OnboardingFailed card that shares it (in-place swap in the reducer).
     * Network IO that may need to happen in response to OnboardSuccess is
     * handled by the caller (see [handleMessage]'s `OnboardSuccess` branch
     * — it calls [reauthenticate]), not here, so the method's name
     * continues to describe what it does.
     */
    private fun trackOnboardingState(item: ChatItem) {
        when (item) {
            is ChatItem.OnboardRequired -> {
                onboardingPending = true
                // The gate is the Host's answer to our CONNECT; the next one is
                // the user's to give, however long that takes.
                awaitingConnected = false
                connectedWaitJob?.cancel()
                // Only the first prompt of a cycle names the card; a re-prompt
                // has already been pinned to it by [alignGateCardId].
                lastOnboardRequiredId = lastOnboardRequiredId ?: item.id
            }
            is ChatItem.OnboardSuccess -> {
                onboardingPending = false
                lastOnboardRequiredId = null
            }
            else -> {}
        }
    }

    /**
     * Pins an ONBOARD_REQUIRED onto the card id this gate cycle already owns,
     * so the re-prompt that follows a rejection swaps the card's variant in
     * place instead of rebuilding the row (and losing the keyboard) under a
     * user who has started retyping. Only the id is replaced — the frame's own
     * methods/payment terms are the server's current instruction and pass
     * through. Everything else is returned untouched.
     */
    private fun alignGateCardId(item: ChatItem): ChatItem {
        if (item !is ChatItem.OnboardRequired) return item
        val carried = lastOnboardRequiredId ?: return item
        return if (carried == item.id) item else item.copy(id = carried)
    }

    /**
     * Resend CONNECT on the given socket to convert a freshly-promoted
     * trust state into an authenticated session. The server's
     * handle_onboard_submit only promotes trust — it never sends CONNECTED
     * itself (that only happens inside handle_connect) — so without this
     * retry the socket's `isConnectionReady` would never flip to `true`
     * (see ws_admin.py:54-90 and ws_router/connect.py:26-31).
     *
     * Flipping [isConnectionReady] to `false` here, before the new CONNECT
     * reaches the server and the reply CONNECTED lands, is what gates
     * [sendMessage] (line 167) so a user-input INPUT can't race the
     * in-flight re-authentication. [handleMessage]'s Connected branch
     * (line ~448) flips it back to `true` when the reply arrives.
     *
     * @param capturedAgentAddress the agent the socket was opened for
     *   (captured into the listener closure at [openWebSocket] time), not
     *   the current value of the instance field — a stale event on an
     *   old, to-be-orphaned socket must not sign a CONNECT that targets
     *   a different agent.
     */
    private fun reauthenticate(ws: WebSocket, capturedAgentAddress: String) {
        isConnectionReady = false
        sendConnectMessage(ws, capturedAgentAddress)
    }

    /**
     * Handle incoming WebSocket message using ProtocolParser.
     *
     * @param capturedAgentAddress the agent the live socket was opened for,
     *   plumbed down from the [WebSocketListener] closure at
     *   [openWebSocket] time. Used by [reauthenticate]'s OnboardSuccess
     *   branch so a stale callback from an orphan listener can't sign a
     *   CONNECT against a freshly-switched `agentAddress` instance field.
     */
    private fun handleMessage(text: String, capturedAgentAddress: String) {
        try {
            FileLogger.d(LogTags.AGENT_CONN, "RX ← ${LogSanitizer.jsonTypeSummary(text)}")
            // Copied under the lock: Collections.synchronizedSet does not guard
            // iteration, and toSet() iterates.
            val outstanding = synchronized(outstandingInputIds) { outstandingInputIds.toSet() }
            val result = ProtocolParser.parse(text, outstanding, isDirect)
            // A replay re-sends frames already handled; drop repeats before any side effect. Only
            // frames with a dedupeKey are compared, so snapshots re-sent on every CONNECT still pass.
            val dedupeKey = result.dedupeKey
            if (dedupeKey != null && !isFirstSighting(dedupeKey)) {
                FileLogger.d(LogTags.AGENT_CONN, "Dropping replayed frame: ${LogSanitizer.jsonTypeSummary(text)}")
                return
            }

            // The run these ids belonged to has answered (or given up), so the
            // next send starts from an empty set rather than inheriting them.
            if (result.event is ConnectionEvent.OutputReceived ||
                result.event is ConnectionEvent.ConnectionError
            ) {
                outstandingInputIds.clear()
            }

            // Send PONG if needed
            if (result.shouldSendPong) {
                webSocket?.send(json.encodeToString(PongMessage()))
            }

            // Never adopt a session we did not ask for. Applying it would
            // file the previous conversation's session under the one on
            // screen, so the row would name a session it never requested.
            if (result.event is ConnectionEvent.Connected &&
                isWrongSession(result.updateSessionId)
            ) {
                FileLogger.e(
                    LogTags.AGENT_CONN,
                    "CONNECTED carried ${result.updateSessionId} but we asked for " +
                        "${_currentSession?.sessionId ?: "<new>"} — refusing to adopt it"
                )
                _events.tryEmit(
                    ConnectionEvent.ConnectionError(
                        "Could not switch conversation: the server kept the previous session."
                    )
                )
                return
            }

            // Update session state. A frame can carry the id without a session
            // body (a brand-new conversation has no transcript to send), so the
            // id is applied whenever it differs — keying off `== null` meant a
            // server-reissued id was dropped once a session already existed.
            val sessionBefore = _currentSession
            result.updateSession?.let { _currentSession = it.keepingPlanOf(sessionBefore) }
            result.updateSessionId?.let { sid ->
                if (_currentSession?.sessionId != sid) {
                    _currentSession = (_currentSession ?: SessionState()).copy(sessionId = sid)
                }
                // Including server-assigned ids: a session we only ever
                // learned about from a CONNECTED is still one we have been
                // on, and [isWrongSession] cannot recognise it otherwise.
                rememberSession(sid)
            }
            // A `plan` frame is session state too: kept on the session so it is
            // saved with it and echoed on the next CONNECT, which the Host
            // adopts as the session — a stale plan there would be the agent's.
            (result.event as? ConnectionEvent.PlanUpdated)?.let { update ->
                _currentSession = _currentSession?.copy(plan = ProtocolParser.encodePlan(update.entries))
            }
            _currentSession?.takeIf { it != sessionBefore }?.let {
                _events.tryEmit(ConnectionEvent.SessionUpdated(it))
            }
            result.msgId?.let { id ->
                _currentSession?.sessionId?.let { sid -> replayCursor = ReplayCursor(sid, id) }
            }

            // Set waiting state
            if (result.setWaiting) {
                _events.tryEmit(ConnectionEvent.Waiting)
            }

            // Fan out extra events first (e.g. session_sync's `extraEvents` are
            // the earlier turns in chronological order, oldest-first, with the
            // newest turn held back as `result.event` below — emitting extras
            // after `event` would replay a whole synced history newest-first,
            // corrupting both the visible order and (via persistMessage
            // stamping Room's timestamp column at processing time) the order
            // permanently persisted to Room).
            for (extra in result.extraEvents) {
                when (extra) {
                    is ConnectionEvent.ChatItemReceived -> {
                        val openSocket = webSocket
                        val aligned = extra.copy(item = alignGateCardId(extra.item))
                        trackOnboardingState(aligned.item)
                        if (aligned.item is ChatItem.OnboardSuccess && openSocket != null) {
                            reauthenticate(openSocket, capturedAgentAddress)
                        }
                        _events.tryEmit(aligned)
                    }
                    is ConnectionEvent.ChatItemUpdated -> {
                        val openSocket = webSocket
                        val aligned = extra.copy(item = alignGateCardId(extra.item))
                        trackOnboardingState(aligned.item)
                        if (aligned.item is ChatItem.OnboardSuccess && openSocket != null) {
                            reauthenticate(openSocket, capturedAgentAddress)
                        }
                        _events.tryEmit(aligned)
                    }
                    else -> {}
                }
            }

            // Map internal event to public event and send
            when (val event = result.event) {
                is ConnectionEvent.Connected -> {
                    FileLogger.i(LogTags.AGENT_CONN, "CONNECTED ✓ session=${result.updateSessionId}")
                    isConnectionReady = true
                    // The only proof we reached the agent, so the only place
                    // the reconnect ladder may be reset. `onOpen` used to do it
                    // and could not: the relay's /ws/input is shared, so it
                    // accepts our socket whether or not the target agent is
                    // online, which pinned the backoff at its 2s floor forever.
                    // Applied on the direct path too — CONNECTED implies the
                    // socket opened, and even there an open socket does not mean
                    // the CONNECT handshake was accepted.
                    reconnectAttempts = 0
                    isReconnecting = false
                    reachedAgentThisCycle = true
                    neverReachedReason = null
                    awaitingConnected = false
                    connectedWaitJob?.cancel()
                    lastAttemptAgentOffline = false
                    pollingOfflineAgent = false
                    flushPendingMessages()
                    // The frame's session payload is forwarded, not dropped:
                    // it is the server's own transcript for this agent, and
                    // the only chance to reconcile it against local history
                    // before the next turn overwrites it.
                    _events.tryEmit(
                        ConnectionEvent.Connected(
                            address = keys?.address ?: "unknown",
                            sessionId = result.updateSessionId,
                            session = result.updateSession,
                            status = event.status,
                            serverNewer = event.serverNewer,
                            modes = event.modes
                        )
                    )
                }
                is ConnectionEvent.OutputReceived -> {
                    FileLogger.i(LogTags.AGENT_CONN, "OUTPUT ✓ ${LogSanitizer.contentSummary(event.result)}")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.ExecResultReceived -> {
                    completeExecResult(event)
                }
                is ConnectionEvent.ProtocolRejected -> {
                    FileLogger.e(
                        LogTags.AGENT_CONN,
                        "CONNECT refused: Host speaks ${event.hostProtocol ?: "<undeclared>"}, " +
                            "client ${OipProtocol.NAME}/${OipProtocol.VERSION} — not reconnecting"
                    )
                    stopAfterProtocolRejection()
                    _events.tryEmit(event)
                }
                is ConnectionEvent.ConnectionError -> {
                    // Rate-limited: the retry ladder replays the identical
                    // rejection on every rung, and an unbounded loop of it is
                    // what filled app.log before the ladder was capped.
                    FileLogger.eRepeating(LogTags.AGENT_CONN, "ERROR: ${event.message}")
                    val execRefusalHandled = failPendingExecsForRefusal(event)
                    // Kept only while the handshake has never landed, so it can
                    // only ever explain a connection that never worked.
                    if (!reachedAgentThisCycle) neverReachedReason = event.message
                    // With a CONNECT outstanding this ERROR is the answer to it:
                    // the attempt is over, whatever happens next.
                    val answersConnect = awaitingConnected
                    if (answersConnect) {
                        awaitingConnected = false
                        connectedWaitJob?.cancel()
                        if (ServerErrorText.isAgentOffline(event.message)) {
                            lastAttemptAgentOffline = true
                            offlineVerdict = event.message
                        }
                    }
                    // Submission rejection recovery; see schedulePostRejectionReconnect doc.
                    if (onboardingPending) {
                        submitWasRejected = true
                        // Synthesize the OnboardingFailed card directly here
                        // rather than routing through ChatViewModel.Error.
                        // That route would also flip connectionState to
                        // Error and surface a "Connection failed" banner —
                        // misleading, since the WS is healthy: only the
                        // invite-code submission was rejected. Emitting
                        // ChatItemReceived + Reconnecting (below) keeps the
                        // banner in the "Waiting for invite code…" / "Connecting
                        // to agent…" family throughout the brief flash, so the
                        // user just sees the failed card's reason and then an
                        // empty input again once the new ONBOARD_REQUIRED
                        // arrives.
                        val existingId = lastOnboardRequiredId
                        if (existingId != null) {
                            _events.tryEmit(
                                ConnectionEvent.ChatItemReceived(
                                    ChatItem.OnboardingFailed(
                                        id = existingId,
                                        // The card is read by the user, so it gets the
                                        // same wording a connection error would (a wrong
                                        // clock refuses ONBOARD_SUBMIT too).
                                        reason = ServerErrorText.humanize(event.message)
                                    )
                                )
                            )
                        }
                        // Do NOT also emit ConnectionError here — that path
                        // sets connectionState=Error and synthesizes its own
                        // (now-stale) OnboardingFailed card. We've already
                        // covered both responsibilities above and below.
                        schedulePostRejectionReconnect()
                        _events.tryEmit(ConnectionEvent.Reconnecting)
                    } else if (execRefusalHandled) {
                        // The refusal has already been delivered to the EXEC
                        // card; emitting a second ConnectionError would show
                        // the same failure in the generic chat error UI.
                    } else if (!staleSessionRecoveryAttempted && StaleSessionDetector.isStaleSessionError(event.message)) {
                        // See recoverFromStaleSession's doc: the same
                        // "session already attached" rejection
                        // ConnectToAgentUseCase recovers from on an
                        // explicit first connect, but arriving here from
                        // this class's own auto-reconnect ladder instead
                        // — a layer that use case never sees.
                        recoverFromStaleSession()
                    } else if (answersConnect && socketIsReconnect) {
                        // A reconnect refused, rather than a business error on
                        // a live session; see endRefusedReconnect.
                        endRefusedReconnect(event.message)
                    } else {
                        // Pre-onboarding ERRORs (mid-conversation server
                        // crash, signature mismatch on an unrelated frame,
                        // etc.) still surface as a fatal connection error
                        // with the "Connection failed" banner — only the
                        // invite-code-rejection path and a single
                        // stale-session recovery attempt get contextual
                        // treatment instead.
                        _events.tryEmit(ConnectionEvent.ConnectionError(event.message, event.code))
                    }
                }
                is ConnectionEvent.ChatItemReceived -> {
                    FileLogger.d(LogTags.AGENT_CONN, "ChatItemReceived: ${event.item::class.simpleName}")
                    val openSocket = webSocket
                    val aligned = event.copy(item = alignGateCardId(event.item))
                    trackOnboardingState(aligned.item)
                    if (aligned.item is ChatItem.OnboardSuccess && openSocket != null) {
                        // see reauthenticate's doc — promote-trust does not
                        // itself send CONNECTED, so we re-auth on the same
                        // socket to lift isConnectionReady.
                        reauthenticate(openSocket, capturedAgentAddress)
                    }
                    _events.tryEmit(aligned)
                }
                is ConnectionEvent.ChatItemUpdated -> {
                    FileLogger.d(LogTags.AGENT_CONN, "ChatItemUpdated: ${event.item::class.simpleName}")
                    val openSocket = webSocket
                    val aligned = event.copy(item = alignGateCardId(event.item))
                    trackOnboardingState(aligned.item)
                    if (aligned.item is ChatItem.OnboardSuccess && openSocket != null) {
                        reauthenticate(openSocket, capturedAgentAddress)
                    }
                    _events.tryEmit(aligned)
                }
                is ConnectionEvent.AgentProfileReceived -> {
                    FileLogger.i(LogTags.AGENT_CONN, "AGENT_PROFILE ✓ model=${event.profile.model} tools=${event.profile.tools.size} skills=${event.profile.skills.size}")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.DashboardSnapshotReceived -> {
                    // Length only — the payload is agent-authored HTML and has
                    // no business in a log file.
                    FileLogger.i(LogTags.AGENT_CONN, "DASHBOARD_SNAPSHOT ✓ ${event.html.length} chars")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.SessionStatusReceived -> {
                    FileLogger.i(LogTags.AGENT_CONN, "SESSION_STATUS ← ${event.status}")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.ModeBudgetChanged -> {
                    FileLogger.i(LogTags.AGENT_CONN, "mode_budget_changed ← ${event.mode.wire} (${event.turnsLeft} turns left)")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.PlanUpdated -> {
                    // Count only — the entries are the agent's own task text.
                    FileLogger.d(LogTags.AGENT_CONN, "plan ✓ ${event.entries.size} entries")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.RuntimeInputAcknowledged -> {
                    FileLogger.i(LogTags.AGENT_CONN, "RUNTIME_INPUT_ACK ✓")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.RuntimeInputRejected -> {
                    // Deliberately not routed through ConnectionError: the
                    // socket and the running turn are both fine, and that path
                    // clears outstandingInputIds and can start a reconnect.
                    FileLogger.w(LogTags.AGENT_CONN, "RUNTIME_INPUT_REJECTED (retryable=${event.retryable})")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.Waiting -> {} // Already handled above
                else -> {} // Ignore Unknown, Ping, etc.
            }
        } catch (e: Exception) {
            FileLogger.e(LogTags.AGENT_CONN, "handleMessage failed: ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_RELAY = "https://oo.openonion.ai"
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 600L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val PING_INTERVAL_SECONDS = 15L
        /** Maximum time to wait for a shell-capable Host to return EXEC_RESULT. */
        const val EXEC_TIMEOUT_MS = 60_000L

        /**
         * The WebSocket-tuned view of [sharedHttpClient], built once.
         *
         * ConnectionRepositoryImpl's factory constructs a fresh
         * [AgentConnection] on every connect() — every agent switch, every
         * rung of the reconnect ladder — and the old constructor default
         * stood up a whole client (dispatcher thread pool included) each
         * time. Derived with `newBuilder()`, so it keeps the app's single
         * dispatcher and connection pool while still carrying the socket's
         * own timeouts.
         */
        private val webSocketClient: OkHttpClient by lazy {
            sharedHttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** Per-agent cap on the queued-message outbox; the oldest is dropped past this. */
        const val MAX_PENDING_MESSAGES = 50

        /** Reconnect attempts before the ladder gives up; see attemptReconnect. */
        internal const val MAX_RECONNECT_ATTEMPTS = 5

        /** How long a reconnect's CONNECT may go unanswered; see awaitConnectedOrRetry. */
        internal const val CONNECTED_TIMEOUT_MS = 15_000L

        /** How often an offline agent is tried once the ladder has run out; see pollOfflineAgent. */
        internal const val OFFLINE_POLL_INTERVAL_MS = 60_000L

        /**
         * Per-agent cap on the *base64* bytes those messages carry, which the
         * row count says nothing about: 50 rows is 50 pointers or ~666MB of
         * attachments depending on what was attached. Drops oldest-first past
         * this.
         *
         * Sized off the composer's own ceiling rather than the per-file one:
         * FileAttachmentStoreImpl accepts a whole selection up to
         * MAX_TOTAL_BYTES (20MB raw, ~26.7MB encoded), so a smaller cap here
         * would put every maximal-but-legal message over budget on arrival and
         * quietly reduce the byte cap to a one-message cap.
         */
        const val MAX_PENDING_ATTACHMENT_BYTES = 32L * 1024 * 1024

        /** How many superseded session ids one connection remembers; see knownSessionIds. */
        private const val MAX_REMEMBERED_SESSIONS = 16

        /**
         * Slots in each event buffer between the socket and the chat (this class's, and the
         * repository's chat bus). A replay can hand over a whole turn at once; a consumer slower
         * than the socket makes a full buffer drop its oldest event, and with the cursor already
         * past it that message is gone for good. DROP_OLDEST stays, so a stuck collector can
         * never stall the socket.
         */
        internal const val EVENT_BUFFER_CAPACITY = 1024

        /** How many handled frames are remembered for replay de-duplication; see seenFrames. */
        private const val MAX_SEEN_FRAMES = 4096

        /** Interjections per run before the oldest id stops being recognised; see outstandingInputIds. */
        private const val MAX_OUTSTANDING_INPUT_IDS = 16

        /** The last timestamp signed into a CONNECT; see [nextConnectTimestamp]. */
        @Volatile private var lastConnectTimestamp = 0L

        /** The clock reading that last timestamp was taken from; see [nextConnectTimestamp]. */
        @Volatile private var lastConnectClock = 0L

        /**
         * The second to sign a CONNECT with: [nowSeconds], bumped past the last
         * one so CONNECTs in a burst never share a second. The server refuses a
         * signature it has already seen (auth.py `signature_already_used`). The
         * payload's nonce already keeps same-second twins distinct; this stays
         * as a second guard, since a pre-nonce payload of `{timestamp(seconds), to}`
         * was exactly how twins got rejected as a replay. Process-wide, not
         * per-instance: ConnectionRepositoryImpl builds a fresh AgentConnection
         * for every connect(), so an instance field would reset exactly where
         * two CONNECTs are closest together — the stale-session retry.
         *
         * Except when the clock itself has gone back by more than
         * [CLOCK_CORRECTION_SECONDS]: the user fixed a wrong date, or the
         * server's correction arrived (W16AAPPLE-90). That is followed, not
         * overridden. Holding every CONNECT past the old, wrong second is what
         * kept a phone refused after its clock had been put right. It is the
         * clock reading that is compared, not the last timestamp, which a burst
         * can push well ahead of the clock without anything being wrong.
         *
         * @param nowSeconds the server-corrected time; see [HostClock].
         */
        @Synchronized
        internal fun nextConnectTimestamp(nowSeconds: Long = System.currentTimeMillis() / 1000): Long {
            val clockWentBack = nowSeconds < lastConnectClock - CLOCK_CORRECTION_SECONDS
            val next = if (clockWentBack) nowSeconds else maxOf(nowSeconds, lastConnectTimestamp + 1)
            lastConnectClock = nowSeconds
            lastConnectTimestamp = next
            return next
        }

        /** How far the clock must move back to count as corrected; see [nextConnectTimestamp]. */
        private const val CLOCK_CORRECTION_SECONDS = 5L

        /**
         * Exponential reconnect backoff: 2s, 4s, 8s, 16s, 32s, capped at 32s
         * for any further attempt. Extracted as a pure function (no
         * WebSocket/coroutine state) so it's unit-testable without the rest
         * of [AgentConnection], which needs a real [ai.openonion.oochat.crypto.KeyManager]
         * (Android Keystore) to do anything beyond this calculation.
         *
         * @param attemptNumber 1-based attempt count (matches [reconnectAttempts] after increment)
         */
        internal fun reconnectBackoffMs(attemptNumber: Int): Long =
            (2000L * (1 shl (attemptNumber - 1))).coerceAtMost(32000L)
    }

    /**
     * This snapshot, with the plan it should carry. A snapshot whose plan is
     * missing or malformed says nothing about the plan, so the one already
     * held for the same session stands — the web client's rule
     * (remote-agent.ts's _applySessionSnapshot); a different session's plan
     * is never carried over.
     */
    private fun SessionState.keepingPlanOf(previous: SessionState?): SessionState = when {
        ProtocolParser.decodePlan(plan) != null -> this
        previous != null && previous.sessionId == sessionId -> copy(plan = previous.plan)
        else -> copy(plan = null)
    }
}
