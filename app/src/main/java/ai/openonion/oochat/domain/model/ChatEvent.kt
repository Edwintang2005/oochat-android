package ai.openonion.oochat.domain.model

/**
 * Domain-level chat events.
 *
 * These are the events exposed by the repository to the ViewModel.
 * They abstract away the network-layer ConnectionEvent types.
 */
sealed class ChatEvent {
    /** Agent sent a new chat item */
    data class ChatItemReceived(
        val item: ChatItem,
        /** See ConnectionEvent.ChatItemReceived.fromSessionSnapshot. */
        val fromSessionSnapshot: Boolean = false,
        /** See ConnectionEvent.ChatItemReceived.answeredQuestion. */
        val answeredQuestion: String? = null
    ) : ChatEvent()

    /** Agent updated an existing chat item */
    data class ChatItemUpdated(val item: ChatItem) : ChatEvent()

    /** Agent sent final output */
    data class OutputReceived(val result: String, val session: SessionSnapshot?) : ChatEvent()

    /** Agent is waiting for user input */
    data object Waiting : ChatEvent()

    /**
     * The transcript the server replayed on CONNECT, assistant entries only
     * (the local [ChatItem.User] stays the source of truth for outgoing
     * text). Deliberately not folded by [ChatEventReducer]: only the resumed
     * conversation's own rows can say which of these entries are actually
     * new — see [ai.openonion.oochat.domain.usecase.SessionFreshness].
     * The session belongs to this conversation alone, so every entry does.
     */
    data class ServerTranscriptReceived(val entries: List<ServerTranscriptEntry>, val turn: Int?) : ChatEvent()

    /**
     * The server sent a post-connection "ERROR" frame (e.g. "Insufficient
     * ConnectOnion Credits") — a business-logic rejection, not a transport
     * failure. Deliberately NOT a [ConnectionState] transition: verified
     * against production, this server keeps the WebSocket open (PING/PONG
     * continues) after sending one of these, so treating it as a fatal
     * disconnect (as [ConnectionState.Error] does) misrepresents a perfectly
     * healthy connection. Mirrors the reference web client
     * (connectonion/react's `useAgentForHuman`), which surfaces its `error`
     * as a message independent of `connectionState` for exactly this reason.
     */
    data class ConnectionErrorOccurred(val message: String) : ChatEvent()

    /**
     * Reply to [ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract.querySessionStatus] —
     * whether the server is still running a turn that looked abandoned when
     * the socket dropped. [status] is one of "running" | "connected" |
     * "not_found" — see [ai.openonion.oochat.network.ConnectionEvent.SessionStatusReceived]'s
     * own doc. Not a [ChatItem]: a connection-layer diagnostic, not
     * something rendered in the message list.
     */
    data class SessionStatusReceived(val sessionId: String?, val status: String) : ChatEvent()
}

/**
 * One assistant entry of the CONNECT-replayed transcript.
 *
 * Content only. It used to carry the user message it answered too, on the
 * theory that the merge would need it to confirm the asker — but
 * ConversationHistoryUseCase.mergeServerTranscript keys purely on content
 * (the session belongs to one conversation, so attribution is already
 * settled), and nothing else ever read the field. Since the transcript is
 * held for late subscribers, that pinned every user message in the replayed
 * history alive for as long as the connection lasted.
 */
data class ServerTranscriptEntry(
    val content: String
)
