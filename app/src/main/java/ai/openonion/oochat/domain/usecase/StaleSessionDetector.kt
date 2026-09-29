package ai.openonion.oochat.domain.usecase

/**
 * Heuristic for "the server rejected because of a stale session".
 *
 * The current server response is
 *   `{"type":"ERROR","error":"Session is already attached to another connection"}`
 * — match on the substring so we don't break on minor wording
 * changes, but stay tight enough to avoid false positives on
 * unrelated errors.
 *
 * Lives in its own top-level object so the use case can call it
 * internally and the test can exercise it without standing up a
 * KeyManager / SessionStore.
 */
internal object StaleSessionDetector {
    fun isStaleSessionError(message: String): Boolean {
        val m = message.lowercase()
        return "session" in m && ("already" in m || "attach" in m)
    }
}
