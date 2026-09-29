package ai.openonion.oochat.domain.model

/**
 * The agent's own self-report of what it currently is, sent once by the
 * server right after CONNECT clears the trust gate (`AGENT_PROFILE` frame —
 * see ws_router/connect.py's handle_connect, ~line 147). A superset of the
 * public `/info` endpoint: [skills] only exists on the operator's own
 * machine and is never exposed there.
 *
 * This is connection-scoped state, not persisted data: it describes the
 * agent this socket is talking to right now, and is re-sent on every
 * CONNECT — see [ai.openonion.oochat.data.repository.ConnectionRepository.agentProfile]'s
 * doc for why it lives as connection state rather than a [ChatEvent] or a
 * Room row. Not [AgentProfile] (that's the locally-saved contact-book
 * entry for an agent this app knows about) — this is what the live socket
 * just told us, and per the reference web client
 * (`ref-web/app/[address]/[sessionId]/page.tsx`), it overrides any cached
 * profile because "it is what the agent actually is".
 *
 * [balanceUsd] is the *agent's own* operating balance, shown beside its
 * name in the chat top bar. Only managed-key (`co/…`) agents publish one, so
 * it is absent more often than not. This is the only balance the app shows
 * now: the account-scoped card it used to sit next to is gone, because
 * credits belong to the agent you connect to —
 * conflating the two would show the wrong figure there.
 */
data class AgentLiveProfile(
    val sessionId: String?,
    val name: String?,
    val address: String?,
    val model: String?,
    val tools: List<String> = emptyList(),
    val skills: List<AgentSkill> = emptyList(),
    val balanceUsd: Double? = null
)
