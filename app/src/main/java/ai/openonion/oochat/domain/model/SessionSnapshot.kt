package ai.openonion.oochat.domain.model

/**
 * Domain-owned view of server session state, decoupled from
 * [ai.openonion.oochat.data.protocol.SessionState] — the
 * `@Serializable` wire DTO with `@SerialName` JSON annotations that mirrors
 * the server's exact response shape. [ConnectionState] and [ChatEvent]
 * embed this instead, so a server-side JSON field rename doesn't ripple
 * into core domain models.
 *
 * Only carries what a domain consumer would plausibly need (session
 * identity, turn count, how many messages the server replayed) — not the
 * full per-message transcript, which is a wire-protocol/persistence
 * concern owned by [ai.openonion.oochat.data.protocol.SessionState]
 * and [ai.openonion.oochat.data.repository.SessionStore].
 */
data class SessionSnapshot(
    val sessionId: String?,
    val turn: Int?,
    val messageCount: Int
)
