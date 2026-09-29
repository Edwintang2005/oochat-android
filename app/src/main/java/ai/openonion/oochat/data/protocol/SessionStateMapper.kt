package ai.openonion.oochat.data.protocol

import ai.openonion.oochat.domain.model.SessionSnapshot

/** Maps the wire-protocol [SessionState] DTO to the domain-owned [SessionSnapshot]. */
fun SessionState.toDomain(): SessionSnapshot = SessionSnapshot(
    sessionId = sessionId,
    turn = turn,
    messageCount = messages?.size ?: 0
)
