package ai.openonion.oochat.network

import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.protocol.ApprovalResponse
import ai.openonion.oochat.data.protocol.AskUserResponse
import ai.openonion.oochat.data.protocol.ConnectMessage
import ai.openonion.oochat.data.protocol.ConnectPayload
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.data.protocol.InputMsg
import ai.openonion.oochat.data.protocol.InterruptMessage
import ai.openonion.oochat.data.protocol.ModeChangeMessage
import ai.openonion.oochat.data.protocol.OnboardSubmitMessage
import ai.openonion.oochat.data.protocol.OnboardSubmitPayload
import ai.openonion.oochat.data.protocol.PlanReviewResponse
import ai.openonion.oochat.data.protocol.PongMessage
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.protocol.SessionStatusQuery
import ai.openonion.oochat.data.protocol.SessionStatusQuerySession
import ai.openonion.oochat.data.protocol.UlwResponse
import ai.openonion.oochat.di.sharedHttpClient
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.usecase.StaleSessionDetector
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogSanitizer
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val pendingMessageSink: PendingMessageSink = InMemoryPendingMessageSink()
) {
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var keys: KeyManager.AddressData? = null
    @Volatile private var _currentSession: SessionState? = null
    val currentSession: SessionState? get() = _currentSession
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
        extraBufferCapacity = 64,
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
    private fun reopenSocket(reason: String) {
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
        // Never repeats, never goes backwards — see nextConnectTimestamp.
        val timestamp = nextConnectTimestamp()
        val directNow = isDirect

        val signature = signPayload(
            payloadFields = buildMap<String, Any> {
                put("timestamp", timestamp)
                if (!directNow) put("to", capturedAgentAddress)
            }
        ) ?: return

        val connectMsg = ConnectMessage(
            payload = ConnectPayload(
                timestamp = timestamp,
                to = if (!directNow) capturedAgentAddress else null
            ),
            from = k.address,
            signature = signature,
            timestamp = timestamp,
            to = if (!directNow) capturedAgentAddress else null,
            sessionId = _currentSession?.sessionId
        )

        val msgStr = json.encodeToString(connectMsg)
        FileLogger.i(LogTags.AGENT_CONN, "CONNECT → $capturedAgentAddress sid=${connectMsg.sessionId ?: "<new>"}")
        ws.send(msgStr)
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

        val msgStr = json.encodeToString(msg)
        FileLogger.i(LogTags.AGENT_CONN, "INPUT → ${LogSanitizer.contentSummary(prompt)}")
        val accepted = webSocket?.send(msgStr) ?: false
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
        webSocket?.send(json.encodeToString(AskUserResponse(answer = answer)))
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
        webSocket?.send(json.encodeToString(InterruptMessage()))
    }

    fun respondToApproval(approved: Boolean, scope: String = "once", mode: String? = null, feedback: String? = null) {
        webSocket?.send(json.encodeToString(ApprovalResponse(approved = approved, scope = scope, mode = mode, feedback = feedback)))
    }

    /** Reply to a [ChatItem.PlanReviewItem][ai.openonion.oochat.domain.model.ChatItem.PlanReviewItem] checkpoint. */
    fun respondToPlanReview(message: String) {
        webSocket?.send(json.encodeToString(PlanReviewResponse(message = message)))
    }

    /** Reply to a [ChatItem.UlwTurnsReachedItem][ai.openonion.oochat.domain.model.ChatItem.UlwTurnsReachedItem] checkpoint. */
    fun respondToUlwTurnsReached(action: String, turns: Int? = null, mode: String? = null) {
        webSocket?.send(json.encodeToString(UlwResponse(action = action, turns = turns, mode = mode)))
    }

    /**
     * Ask the agent to switch tool-approval mode — see [ModeChangeMessage]'s
     * own doc for the wire shape and the server code that reads it.
     * Fire-and-forget on whatever socket is live, same as
     * [respond]/[respondToApproval]: the server forwards this straight into
     * the running agent's mailbox, no CONNECTED handshake of its own needed.
     *
     * @param mode "safe" | "plan" | "accept_edits" | "ulw"
     * @param turns ULW's autonomous-turn budget; ignored by the server for
     *   every other mode, so callers pass null there.
     */
    fun setMode(mode: String, turns: Int? = null) {
        FileLogger.i(LogTags.AGENT_CONN, "mode_change → $mode${turns?.let { " ($it turns)" } ?: ""}")
        webSocket?.send(json.encodeToString(ModeChangeMessage(mode = mode, turns = turns)))
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

        val timestamp = System.currentTimeMillis() / 1000

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

    fun reset() {
        _currentSession = null
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
        if (!isReconnecting) return
        FileLogger.i(LogTags.AGENT_CONN, "Reconnect: retrying now (foreground)")
        reconnectJob?.cancel()
        reconnectAttempts = 0
        reconnectJob = reconnectScope.launch {
            runCatchingCancellable {
                retireSocket("Reconnecting")
                openWebSocket(agentAddress, directUrl)
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
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            FileLogger.w(LogTags.AGENT_CONN, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            isConnectionReady = false
            val reason = neverReachedReason
            _events.tryEmit(
                if (reason != null) ConnectionEvent.ConnectionError(reason)
                else ConnectionEvent.Disconnected
            )
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

        webSocket = webSocketFactory.create(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Deliberately does NOT reset the reconnect ladder — see the
                // CONNECTED branch of [handleMessage]. All this proves is that
                // a socket exists; whether the agent is on the other end of it
                // is the next frame's news.
                FileLogger.i(LogTags.AGENT_CONN, "WS opened, sending CONNECT")
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
            result.updateSession?.let { _currentSession = it }
            result.updateSessionId?.let { sid ->
                if (_currentSession?.sessionId != sid) {
                    _currentSession = (_currentSession ?: SessionState()).copy(sessionId = sid)
                }
                // Including server-assigned ids: a session we only ever
                // learned about from a CONNECTED is still one we have been
                // on, and [isWrongSession] cannot recognise it otherwise.
                rememberSession(sid)
            }
            _currentSession?.takeIf { it != sessionBefore }?.let {
                _events.tryEmit(ConnectionEvent.SessionUpdated(it))
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
                    flushPendingMessages()
                    // The frame's session payload is forwarded, not dropped:
                    // it is the server's own transcript for this agent, and
                    // the only chance to reconcile it against local history
                    // before the next turn overwrites it.
                    _events.tryEmit(
                        ConnectionEvent.Connected(
                            address = keys?.address ?: "unknown",
                            sessionId = result.updateSessionId,
                            session = result.updateSession
                        )
                    )
                }
                is ConnectionEvent.OutputReceived -> {
                    FileLogger.i(LogTags.AGENT_CONN, "OUTPUT ✓ ${LogSanitizer.contentSummary(event.result)}")
                    _events.tryEmit(event)
                }
                is ConnectionEvent.ConnectionError -> {
                    // Rate-limited: the retry ladder replays the identical
                    // rejection on every rung, and an unbounded loop of it is
                    // what filled app.log before the ladder was capped.
                    FileLogger.eRepeating(LogTags.AGENT_CONN, "ERROR: ${event.message}")
                    // Kept only while the handshake has never landed, so it can
                    // only ever explain a connection that never worked.
                    if (!reachedAgentThisCycle) neverReachedReason = event.message
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
                                        reason = event.message
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
                    } else if (!staleSessionRecoveryAttempted && StaleSessionDetector.isStaleSessionError(event.message)) {
                        // See recoverFromStaleSession's doc: the same
                        // "session already attached" rejection
                        // ConnectToAgentUseCase recovers from on an
                        // explicit first connect, but arriving here from
                        // this class's own auto-reconnect ladder instead
                        // — a layer that use case never sees.
                        recoverFromStaleSession()
                    } else {
                        // Pre-onboarding ERRORs (mid-conversation server
                        // crash, signature mismatch on an unrelated frame,
                        // etc.) still surface as a fatal connection error
                        // with the "Connection failed" banner — only the
                        // invite-code-rejection path and a single
                        // stale-session recovery attempt get contextual
                        // treatment instead.
                        _events.tryEmit(ConnectionEvent.ConnectionError(event.message))
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

        /** Interjections per run before the oldest id stops being recognised; see outstandingInputIds. */
        private const val MAX_OUTSTANDING_INPUT_IDS = 16

        /** The last timestamp signed into a CONNECT; see [nextConnectTimestamp]. */
        @Volatile private var lastConnectTimestamp = 0L

        /**
         * The next distinct second to sign a CONNECT with — never repeating,
         * never going backwards. The server refuses a signature it has already
         * seen (auth.py `signature_already_used`) and the signed payload is
         * `{timestamp(seconds), to}`, so same-second twins are rejected as a
         * replay. Process-wide, not per-instance: ConnectionRepositoryImpl
         * builds a fresh AgentConnection for every connect(), so an instance
         * field would reset exactly where two CONNECTs are closest together —
         * the stale-session retry.
         */
        @Synchronized
        internal fun nextConnectTimestamp(): Long =
            maxOf(System.currentTimeMillis() / 1000, lastConnectTimestamp + 1)
                .also { lastConnectTimestamp = it }

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
}
