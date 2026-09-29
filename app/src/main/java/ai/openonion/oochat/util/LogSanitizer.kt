package ai.openonion.oochat.util

/**
 * Centralizes log redaction for [FileLogger] output. [FileLogger] writes to
 * a plaintext, unencrypted file (readable via `adb shell run-as` or backup
 * extraction). Routing every content-adjacent log line through this component
 * means a future call site defaults to a safe summary instead of an author
 * deciding ad hoc how many characters of raw content are "probably fine" to log.
 */
object LogSanitizer {

    /** Structural-only summary of user-authored free text — never the content itself. */
    fun contentSummary(content: String): String = "len=${content.length}"

    private val TYPE_FIELD_REGEX = Regex(""""type"\s*:\s*"([^"]*)"""")

    /**
     * Best-effort extraction of just the `type` field from a raw JSON
     * protocol frame, for log lines that previously dumped the raw frame
     * text (which can contain session ids, addresses, and assistant/tool
     * output). Falls back to a length-only summary if no `type` field is
     * found or the input isn't JSON-shaped. Must never throw — this runs
     * on the hot path of every inbound WebSocket frame, including
     * malformed ones from a buggy or hostile relay.
     */
    fun jsonTypeSummary(raw: String): String {
        val type = runCatching { TYPE_FIELD_REGEX.find(raw)?.groupValues?.get(1) }.getOrNull()
        return if (type != null) "type=$type len=${raw.length}" else "len=${raw.length}"
    }

    /**
     * First line only of a kotlinx-serialization failure message. That line is
     * the diagnostic — offending field, path, expected type; everything after
     * it is the decoder's own `JSON input:` copy of the frame, which is the
     * whole frame verbatim under 200 chars and a 60-char window otherwise.
     * Appending the raw message to a sanitised summary undoes the sanitising.
     */
    fun decodeErrorSummary(message: String?): String =
        message?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifEmpty { "no detail" }
}
