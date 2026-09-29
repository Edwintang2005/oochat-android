package ai.openonion.oochat.domain.model

/**
 * Turns assistant content into a stable id, so repeated `session_sync`
 * replays of the same reply collapse to one ChatItem instead of "shifting"
 * down the list each turn. Lives here (not in ProtocolParser, where it used
 * to be) since both ProtocolParser and ChatEventReducer need it and domain
 * is the layer both can depend on.
 */
fun stableAssistantId(content: String): String {
    val trimmed = content.trim()
    // SHA-256, first 16 hex chars — plenty of buckets, short id.
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(trimmed.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder("asst-")
    for (i in 0 until 8) {
        hex.append(String.format("%02x", digest[i]))
    }
    return hex.toString()
}
