package ai.openonion.oochat.network

import ai.openonion.oochat.data.protocol.ServerEvent
import ai.openonion.oochat.data.protocol.SessionState
import ai.openonion.oochat.data.protocol.SkillWire
import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import ai.openonion.oochat.domain.model.stableAssistantId
import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogSanitizer
import ai.openonion.oochat.util.LogTags
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure function for parsing WebSocket messages into ConnectionEvents.
 *
 * Extracted from AgentConnection.handleMessage() for testability.
 * This class has no side effects and no dependencies on WebSocket state.
 */
object ProtocolParser {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Parse a raw WebSocket message into a ConnectionEvent.
     *
     * @param text Raw JSON message from WebSocket
     * @param outstandingInputIds Every INPUT still awaiting a reply, for filtering OUTPUT messages
     * @param isDirect Whether this is a direct connection (not relay)
     * @return ParsedResult containing the event and any side effects
     */
    fun parse(text: String, outstandingInputIds: Set<String>, isDirect: Boolean): ParseResult {
        val event = try {
            json.decodeFromString<ServerEvent>(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            // Don't rely on the caller's try/catch — this class claims to be
            // safe to call standalone.
            // Only the exception's first line: it names the offending field
            // and the expected type, which is how a genuinely malformed frame
            // is told apart from one our schema is too strict for. The rest is
            // the decoder's verbatim copy of the frame — see
            // [LogSanitizer.decodeErrorSummary].
            FileLogger.w(
                LogTags.PROTOCOL,
                "Malformed server event, ignoring: ${LogSanitizer.jsonTypeSummary(text)} — " +
                    LogSanitizer.decodeErrorSummary(e.message)
            )
            return ParseResult(event = ConnectionEvent.Unknown)
        }
        FileLogger.d(
            LogTags.PROTOCOL,
            "Parsed type=${event.type}, ${LogSanitizer.contentSummary(event.content.orEmpty())}"
        )

        return when (event.type) {
            "PING" -> ParseResult(
                event = ConnectionEvent.Ping,
                shouldSendPong = true
            )

            "CONNECTED" -> ParseResult(
                event = ConnectionEvent.Connected(
                    address = "", // Will be filled by caller
                    sessionId = event.sessionId,
                    session = event.session
                ),
                updateSession = event.session,
                updateSessionId = event.sessionId
            )

            // Sent once, right after CONNECT clears the trust gate (see
            // ws_router/connect.py's handle_connect, ~line 147) — the
            // agent's own full name card, a superset of the public /info
            // endpoint. Not a chat item: it describes the connection, not
            // something that happened in the conversation, so it gets its
            // own ConnectionEvent branch instead of ChatItemReceived/Updated
            // — same treatment as CONNECTED/SessionUpdated above.
            "AGENT_PROFILE" -> ParseResult(
                event = ConnectionEvent.AgentProfileReceived(
                    AgentLiveProfile(
                        sessionId = event.sessionId,
                        name = event.name,
                        address = event.address,
                        model = event.model,
                        tools = event.tools ?: emptyList(),
                        skills = event.skills.toAgentSkills(),
                        balanceUsd = event.balanceUsd
                    )
                )
            )

            // The agent's own dashboard.html, pushed once on connect and
            // again whenever the file changes (ws_router/dashboard.py's
            // send_dashboard, deduped per connection). Connection state, not
            // a chat item — same reasoning as AGENT_PROFILE above.
            //
            // Both guards below drop the frame rather than surfacing an empty
            // or absurd one: the payload is agent-authored, and a dashboard
            // that cannot be rendered must read as "this agent has no Home",
            // never as a blank screen the user has to interpret.
            "DASHBOARD_SNAPSHOT" -> {
                val html = event.html
                when {
                    html.isNullOrBlank() -> ParseResult(event = ConnectionEvent.Unknown)
                    html.length > MAX_DASHBOARD_HTML_CHARS -> {
                        FileLogger.w(
                            LogTags.PROTOCOL,
                            "DASHBOARD_SNAPSHOT is ${html.length} chars (limit $MAX_DASHBOARD_HTML_CHARS) — ignoring"
                        )
                        ParseResult(event = ConnectionEvent.Unknown)
                    }
                    else -> ParseResult(event = ConnectionEvent.DashboardSnapshotReceived(html))
                }
            }

            "llm_call" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.Turn(
                        id = event.id ?: generateId(),
                        thinking = ChatItem.Thinking(
                            id = event.id ?: generateId(),
                            status = ThinkingStatus.RUNNING,
                            model = event.model
                        )
                    )
                )
            )

            "llm_result" -> {
                val id = event.id ?: return ParseResult(event = ConnectionEvent.Unknown)
                // Same precedence the reference web client uses
                // (status-bar.tsx): an explicit total wins, otherwise sum the
                // two sides, each accepting either spelling. A missing counter
                // reads as 0 for that side, not as "no usage at all" — only a
                // wholly absent `usage` object leaves tokensTotal null.
                val tokensTotal = event.usage?.let { u ->
                    u.totalTokens
                        ?: ((u.inputTokens ?: u.promptTokens ?: 0) + (u.outputTokens ?: u.completionTokens ?: 0))
                }
                ParseResult(
                    event = ConnectionEvent.ChatItemUpdated(
                        ChatItem.Turn(
                            id = id,
                            thinking = ChatItem.Thinking(
                                id = id,
                                status = if (event.status == "error") ThinkingStatus.ERROR else ThinkingStatus.DONE,
                                model = event.model,
                                durationMs = event.durationMs,
                                tokensTotal = tokensTotal,
                                costUsd = event.usage?.cost,
                                contextPercent = event.contextPercent
                            )
                        )
                    )
                )
            }

            "thinking" -> {
                val id = event.id ?: generateId()
                if (!event.content.isNullOrBlank()) {
                    // Streaming assistant text delivered without an
                    // assistant event — model directly via Turn with the
                    // content-derived id so it merges with the eventual
                    // assistant / session_sync Turn for the same content.
                    val tid = stableAssistantId(event.content)
                    ParseResult(
                        event = ConnectionEvent.ChatItemReceived(
                            ChatItem.Turn(
                                id = tid,
                                agent = ChatItem.Agent(id = tid, content = event.content)
                            )
                        )
                    )
                } else {
                    ParseResult(
                        event = ConnectionEvent.ChatItemReceived(
                            ChatItem.Turn(
                                id = id,
                                thinking = ChatItem.Thinking(
                                    id = id,
                                    status = ThinkingStatus.DONE,
                                    model = event.model
                                )
                            )
                        )
                    )
                }
            }

            "tool_call" -> {
                val id = event.toolId ?: event.id ?: generateId()
                ParseResult(
                    event = ConnectionEvent.ChatItemReceived(
                        ChatItem.ToolCall(
                            id = id,
                            name = event.name ?: "unknown",
                            args = event.args.toDisplayMap(),
                            status = ToolStatus.RUNNING
                        )
                    )
                )
            }

            "tool_result" -> {
                val id = event.toolId ?: event.id ?: return ParseResult(event = ConnectionEvent.Unknown)
                ParseResult(
                    event = ConnectionEvent.ChatItemUpdated(
                        ChatItem.ToolCall(
                            id = id,
                            name = event.name ?: "",
                            // The reference implementation itself treats
                            // anything but "success" as an error path (see
                            // tool_executor.py's `status in ("error",
                            // "not_found")` on_error gate) — mirror that
                            // instead of only recognizing the literal string
                            // "error", which silently rendered a tool-not-
                            // found failure as a normal DONE result.
                            status = when (event.status) {
                                "error", "not_found" -> ToolStatus.ERROR
                                else -> ToolStatus.DONE
                            },
                            result = event.result
                        )
                    )
                )
            }

            "agent_image" -> {
                if (event.image != null) {
                    // No server-provided id for this event, and parse() has
                    // no lookback into the current chat item list to merge
                    // into the in-flight Turn itself (ChatEventReducer does
                    // that instead — it holds the current list). Still use a
                    // content-derived id, not a random one: this frame is
                    // subject to the same redelivery-on-reconnect risk as
                    // every other event type here, and a random id would
                    // duplicate the bubble instead of deduping via
                    // dedupeUI(), same class of bug stableAssistantId was
                    // introduced to fix for assistant text.
                    ParseResult(
                        event = ConnectionEvent.ChatItemReceived(
                            ChatItem.Agent(
                                id = stableAssistantId(event.image),
                                content = "",
                                images = listOf(event.image)
                            )
                        )
                    )
                } else {
                    ParseResult(event = ConnectionEvent.Unknown)
                }
            }

            "assistant" -> {
                if (event.content != null) {
                    // Use the content-derived id so the assistant Turn
                    // dedupes with later session_sync replays of the same
                    // reply (same content → same hash → same id).
                    val tid = stableAssistantId(event.content)
                    ParseResult(
                        event = ConnectionEvent.ChatItemReceived(
                            ChatItem.Turn(
                                id = tid,
                                agent = ChatItem.Agent(id = tid, content = event.content)
                            )
                        )
                    )
                } else {
                    ParseResult(event = ConnectionEvent.Unknown)
                }
            }

            // The server now streams a full session snapshot via "session_sync"
            // after each turn. Extract assistant messages so they render in
            // the chat list. IDs are position-based so repeated syncs dedupe.
            "session_sync" -> parseSessionSync(event)

            // The server echoes back the user's input as a "user_input" event
            // (and includes it inside session_sync). We don't render the echo
            // — the local ChatItem.User already covers it. Silently drop.
            "user_input" -> ParseResult(event = ConnectionEvent.Unknown)

            "ask_user" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.AskUser(
                        id = event.id ?: generateId(),
                        // The server's actual field is "question" (see
                        // ask_user.py's event payload) — "text" is not sent
                        // for this event type, only kept as a fallback so an
                        // older/different frame shape still renders something.
                        text = event.question ?: event.text ?: "",
                        options = event.options ?: emptyList(),
                        multiSelect = event.multiSelect ?: false
                    )
                ),
                setWaiting = true
            )

            "approval_needed" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.ApprovalNeeded(
                        id = event.id ?: generateId(),
                        tool = event.tool ?: "",
                        arguments = event.arguments.toDisplayMap() ?: emptyMap(),
                        description = event.description,
                        batchRemaining = event.batchRemaining
                            ?.keepUsable("batch_remaining") { it.tool.isNotBlank() }
                            ?.map {
                                ai.openonion.oochat.domain.model.BatchToolPreview(it.tool, it.arguments)
                            }
                    )
                ),
                setWaiting = true
            )

            "ONBOARD_REQUIRED" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.OnboardRequired(
                        id = event.id ?: generateId(),
                        methods = event.methods ?: emptyList(),
                        paymentAmount = event.paymentAmount,
                        paymentAddress = event.paymentAddress
                    )
                ),
                setWaiting = true
            )

            "ONBOARD_SUCCESS" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.OnboardSuccess(
                        id = event.id ?: generateId(),
                        level = event.level ?: "",
                        message = event.message ?: ""
                    )
                )
            )

            "intent" -> ParseResult(
                event = ConnectionEvent.ChatItemUpdated(
                    ChatItem.IntentItem(
                        id = event.id ?: generateId(),
                        status = if (event.status == "understood") IntentStatus.UNDERSTOOD else IntentStatus.ANALYZING,
                        ack = event.ack,
                        isBuild = event.isBuild
                    )
                )
            )

            "eval" -> ParseResult(
                event = ConnectionEvent.ChatItemUpdated(
                    ChatItem.EvalItem(
                        id = event.id ?: generateId(),
                        status = if (event.status == "done") EvalStatus.DONE else EvalStatus.EVALUATING,
                        passed = event.passed,
                        summary = event.summary,
                        expected = event.expected,
                        evalPath = event.evalPath
                    )
                )
            )

            "compact" -> ParseResult(
                event = ConnectionEvent.ChatItemUpdated(
                    ChatItem.CompactItem(
                        id = event.id ?: generateId(),
                        status = when (event.status) {
                            "done" -> CompactStatus.DONE
                            "error" -> CompactStatus.ERROR
                            else -> CompactStatus.COMPACTING
                        },
                        contextBefore = event.contextBefore,
                        contextAfter = event.contextAfter,
                        contextPercent = event.contextPercent,
                        message = event.message,
                        error = event.error
                    )
                )
            )

            "tool_blocked" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.ToolBlockedItem(
                        id = event.id ?: generateId(),
                        tool = event.tool ?: "",
                        reason = event.reason ?: "",
                        message = event.message ?: "",
                        command = event.command
                    )
                )
            )

            "plan_review" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.PlanReviewItem(
                        id = event.id ?: generateId(),
                        planContent = event.planContent ?: ""
                    )
                ),
                setWaiting = true
            )

            "files_received" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.FilesReceivedItem(
                        id = event.id ?: generateId(),
                        files = event.files
                            ?.keepUsable("files") { it.name.isNotBlank() }
                            ?.map { ReceivedFile(it.name, it.path) }
                            ?: emptyList()
                    )
                )
            )

            // Informational preview of a pending file write's unified diff —
            // see diff_writer.py's _send_preview. Sent right before the
            // paired ask_user/approval_needed request; does not block or
            // set waiting itself.
            "diff_preview" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.DiffPreviewItem(
                        id = event.id ?: generateId(),
                        path = event.path ?: "",
                        preview = event.preview ?: "",
                        truncated = event.truncated ?: false,
                        fileExists = event.fileExists ?: true
                    )
                )
            )

            "ulw_turns_reached" -> ParseResult(
                event = ConnectionEvent.ChatItemReceived(
                    ChatItem.UlwTurnsReachedItem(
                        id = event.id ?: generateId(),
                        turnsUsed = event.turnsUsed ?: 0,
                        maxTurns = event.maxTurns ?: 0
                    )
                ),
                setWaiting = true
            )

            "OUTPUT" -> {
                val isForUs = isDirect || event.inputId == null || event.inputId in outstandingInputIds
                if (isForUs) {
                    ParseResult(
                        event = ConnectionEvent.OutputReceived(
                            result = event.result ?: "",
                            session = event.session
                        ),
                        updateSession = event.session
                    )
                } else {
                    ParseResult(event = ConnectionEvent.Unknown)
                }
            }

            "ERROR" -> ParseResult(
                event = ConnectionEvent.ConnectionError(
                    message = event.message ?: event.error ?: "Unknown error"
                )
            )

            // Plugin-initiated mode switch (ULW enter/exit, tool_approval's
            // _set_mode, plan_mode enter/exit) — see ServerEvent.mode's own
            // doc for the field's provenance. `mode` is required; a frame
            // missing it is malformed rather than a real notification.
            "mode_changed" -> {
                val mode = event.mode ?: return ParseResult(event = ConnectionEvent.Unknown)
                ParseResult(
                    event = ConnectionEvent.ChatItemReceived(
                        ChatItem.ModeChangedItem(
                            id = event.id ?: generateId(),
                            mode = mode,
                            triggeredBy = event.triggeredBy
                        )
                    )
                )
            }

            // Reply to AgentConnection.querySessionStatus — see
            // ws_router/session.py's SESSION_STATUS branch: `status` is one
            // of "running" | "connected" | "not_found" (a registry miss).
            // Not a ChatItem: this is a connection-layer signal the
            // repository forwards as its own ChatEvent, not something
            // rendered in the message list.
            "SESSION_STATUS" -> {
                val status = event.status ?: return ParseResult(event = ConnectionEvent.Unknown)
                ParseResult(
                    event = ConnectionEvent.SessionStatusReceived(
                        sessionId = event.sessionId,
                        status = status
                    )
                )
            }

            else -> ParseResult(event = ConnectionEvent.Unknown)
        }
    }

    /**
     * Convert a `session_sync` server event into one or more
     * [ConnectionEvent.ChatItemReceived] events — one per assistant
     * message in the session's `messages` array.
     *
     * Position-based IDs ("session-msg-{index}") keep repeated syncs
     * idempotent — the [ai.openonion.oochat.domain.model.dedupeUI]
     * helper in ChatViewModel collapses duplicates by id.
     *
     * Only `role == "assistant"` messages are surfaced as chat items.
     * User messages are skipped (the local [ChatItem.User] is the
     * source of truth for outgoing text).
     */
    private fun parseSessionSync(event: ServerEvent): ParseResult {
        val session: SessionState = event.session ?: return ParseResult(event = ConnectionEvent.Unknown)
        val messages = session.messages ?: return ParseResult(event = ConnectionEvent.Unknown)

        // Content-derived id so repeated session_sync replays dedupe with earlier Turns.
        var lastQuestion: String? = null
        val events = messages.mapNotNull { msg ->
            if (msg.role.equals("user", ignoreCase = true)) {
                msg.content?.takeIf { it.isNotBlank() }?.let { lastQuestion = it }
            }
            // content is null for a tool-calls-only assistant turn (no text
            // reply) — nothing to render as a chat bubble, skip it rather
            // than crash (see SessionMessage.content's own doc).
            val content = msg.content
            if (msg.role.equals("assistant", ignoreCase = true) && !content.isNullOrBlank()) {
                val id = stableAssistantId(content)
                ConnectionEvent.ChatItemReceived(
                    ChatItem.Turn(
                        id = id,
                        agent = ChatItem.Agent(
                            id = id,
                            content = content
                        )
                    ),
                    fromSessionSnapshot = true,
                    answeredQuestion = lastQuestion
                )
            } else null
        }

        return if (events.isEmpty()) {
            // Keep the session payload for callers that want to persist it,
            // even when there's no assistant text to render right now.
            ParseResult(event = ConnectionEvent.Unknown, updateSession = session)
        } else {
            // We can only return one ConnectionEvent per ParseResult.
            // Emit the LAST assistant message as the primary event and
            // wrap any earlier ones so the caller can fan them out.
            // Earlier duplicates merge by id in dedupeUI().
            val last = events.last()
            val earlier = events.dropLast(1)
            ParseResult(
                event = last,
                extraEvents = earlier,
                updateSession = session
            )
        }
    }

    /**
     * Ceiling on a `DASHBOARD_SNAPSHOT`'s payload, mirroring the host's own
     * `MAX_DASHBOARD_BYTES` (ws_router/dashboard.py). A conforming host never
     * sends more, so anything past it is a runaway file or a hostile relay —
     * either way it must not reach a WebView, where the cost is paid in the
     * UI thread's layout pass.
     */
    const val MAX_DASHBOARD_HTML_CHARS = 2 * 1024 * 1024

    private fun generateId(): String = java.util.UUID.randomUUID().toString()

    /**
     * Wire skills to domain skills. Nameless entries are dropped — there is
     * no command to complete without one — and a description that says
     * nothing (blank, or the host's `"No description"` placeholder) becomes
     * null so the palette shows the name alone rather than filler.
     *
     * Names are deduplicated because the palette keys its list on them, and a
     * `LazyColumn` throws on a repeated key. Nothing on the wire promises they
     * are unique: an agent that registers the same skill twice would take the
     * app down the moment the user typed `/`.
     */
    private fun List<SkillWire>?.toAgentSkills(): List<AgentSkill> =
        this.orEmpty()
            .keepUsable("skills") { it.name.isNotBlank() }
            .distinctBy { it.name }
            .map { wire ->
                val description = wire.description?.trim()
                AgentSkill(
                    name = wire.name,
                    description = description
                        ?.takeIf { it.isNotEmpty() && !it.equals(NO_DESCRIPTION_PLACEHOLDER, ignoreCase = true) }
                )
            }

    /** What the host emits for a skill whose file has no summary — see [toAgentSkills]. */
    private const val NO_DESCRIPTION_PLACEHOLDER = "No description"

    /**
     * Drops the entries of a nested wire list that carry nothing renderable,
     * keeping the frame they arrived in. The count is logged, never the
     * entries: a silent drop hides server-side schema drift, the thing that
     * already cost this layer three dropped-frame bugs (`args`, `skills`,
     * `contextPercent`), while logging an entry would put payload back into
     * app.log.
     */
    private inline fun <T> List<T>.keepUsable(field: String, usable: (T) -> Boolean): List<T> {
        val kept = filter(usable)
        if (kept.size != size) {
            FileLogger.w(LogTags.PROTOCOL, "Dropped ${size - kept.size} of $size unusable `$field` entries")
        }
        return kept
    }

    /**
     * Renders a wire-typed args/arguments map (native JSON types — string,
     * int, bool, ...) down to the display-only `Map<String, String>` that
     * [ChatItem.ToolCall.args] / [ChatItem.ApprovalNeeded.arguments] use.
     * A [JsonPrimitive]'s `content` gives the unquoted literal (`"120"`,
     * `"true"`) instead of JSON source (`120`, `true` would also work, but
     * a nested object/array falls back to `toString()`, which does include
     * JSON punctuation — there's no flatter display form for those).
     */
    private fun Map<String, JsonElement>?.toDisplayMap(): Map<String, String>? =
        this?.mapValues { (_, value) ->
            if (value is JsonPrimitive) value.content else value.toString()
        }
}

/**
 * Result of parsing a WebSocket message.
 *
 * `extraEvents` lets a single parsed frame produce multiple chat items
 * (used by session_sync to replay all assistant messages). The caller is
 * responsible for dispatching each event in turn.
 */
data class ParseResult(
    val event: ConnectionEvent,
    val shouldSendPong: Boolean = false,
    val updateSession: ai.openonion.oochat.data.protocol.SessionState? = null,
    val updateSessionId: String? = null,
    val setWaiting: Boolean = false,
    val extraEvents: List<ConnectionEvent> = emptyList()
)

/**
 * Connection events used internally by AgentConnection.
 */
sealed class ConnectionEvent {
    data class Connected(val address: String, val sessionId: String? = null, val session: ai.openonion.oochat.data.protocol.SessionState? = null) : ConnectionEvent()
    /**
     * @param fromSessionSnapshot true when this item came out of a
     *   `session_sync` frame, i.e. it is the server replaying its own history
     *   rather than reporting something that just happened. The session is
     *   the active conversation's own, so the replay is too.
     */
    data class ChatItemReceived(
        val item: ChatItem,
        val fromSessionSnapshot: Boolean = false,
        /**
         * For a snapshot item, the outgoing message it answers. The snapshot
         * carries no ids of ours, so this is what lets the ViewModel find the
         * conversation that asked -- see MessageDao.getSessionIdByUserContent.
         */
        val answeredQuestion: String? = null
    ) : ConnectionEvent()
    data class ChatItemUpdated(val item: ChatItem) : ConnectionEvent()
    data class OutputReceived(val result: String, val session: ai.openonion.oochat.data.protocol.SessionState?) : ConnectionEvent()

    /**
     * The connection's session changed, from whichever frame carried it —
     * CONNECTED, OUTPUT or session_sync. Emitted so persistence has one thing
     * to listen to instead of guessing which frame types carry a session.
     */
    data class SessionUpdated(val session: ai.openonion.oochat.data.protocol.SessionState) : ConnectionEvent()

    /** The agent's own name card — see [AgentLiveProfile]'s doc for why this is connection state, not a chat item. */
    data class AgentProfileReceived(val profile: AgentLiveProfile) : ConnectionEvent()

    /**
     * The agent's own `dashboard.html`, verbatim and **untrusted** — see
     * [ai.openonion.oochat.ui.dashboard.DashboardDocument] for the two
     * browser-enforced layers that contain it. Connection state, like
     * [AgentProfileReceived]: the host re-sends it on every connect, so
     * there is no history to keep.
     */
    data class DashboardSnapshotReceived(val html: String) : ConnectionEvent()
    /**
     * Server's reply to [AgentConnection.querySessionStatus] — whether a
     * previously-started session is still running after a reconnect.
     * [status] is one of "running" | "connected" | "not_found" (a registry
     * miss) — see ws_router/session.py's SESSION_STATUS branch and
     * ActiveSessionRegistry's own doc for the full state machine those
     * values come from.
     */
    data class SessionStatusReceived(val sessionId: String?, val status: String) : ConnectionEvent()
    data class ConnectionError(val message: String) : ConnectionEvent()
    data object Disconnected : ConnectionEvent()
    data object Reconnecting : ConnectionEvent()
    data object Waiting : ConnectionEvent()
    data object Ping : ConnectionEvent()
    data object Unknown : ConnectionEvent()
}
