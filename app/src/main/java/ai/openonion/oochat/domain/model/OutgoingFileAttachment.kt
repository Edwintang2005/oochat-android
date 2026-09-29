package ai.openonion.oochat.domain.model

/**
 * A non-image file the user attached to an outgoing message: [name] plus a
 * base64 data URL.
 *
 * Deliberately a plain domain-layer mirror of
 * [ai.openonion.oochat.data.protocol.FileAttachment] rather than that
 * type itself — `domain/` isn't allowed to depend on wire-protocol types
 * (see ArchitectureGuardTest's "domain layer does not import ... wire-protocol
 * types"). [ai.openonion.oochat.data.repository.ConnectionRepositoryImpl]
 * maps this to the wire type right before it reaches [ai.openonion.oochat.network.AgentConnection].
 */
data class OutgoingFileAttachment(
    val name: String,
    val data: String
)
