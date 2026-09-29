package ai.openonion.oochat.util

/**
 * One-line "what is this actually doing" summary of a tool call's
 * arguments. Shared by every place that shows a tool without (or before)
 * its own dedicated per-tool card body: [ai.openonion.oochat.ui.chat.components.ToolCallBubble]
 * (the generic fallback for a tool with no dedicated card),
 * [ai.openonion.oochat.ui.chat.components.ApprovalCard] (the decision
 * gate — the whole point of this being visible is letting the user approve
 * something they can actually read), and its post-decision
 * ApprovalConfirmationBar receipt.
 *
 * Picks the same "headline" argument the dedicated cards (BashCallCard,
 * FileCallCard, FileDiffCallCard, GrepCallCard in ToolCallCards.kt) already
 * surface as their title, so a tool reads the same before, during, and
 * after approval. Falls back to a compact `key=value, key2=value2` list for
 * anything without a known headline arg — mirrors oo-chat-web's
 * generic-card.tsx `formatArgs()`, adapted to show keys too since this app
 * has no per-tool-kind icon to carry that context on its own.
 *
 * No secret redaction here, by design: hiding argument values would defeat
 * the fix this exists for (letting the user see what they're approving
 * instead of approving blind — see the fix's own commit message). This is
 * a different threat model than [LogSanitizer], which exists to keep
 * plaintext content out of an unencrypted on-disk log file the user isn't
 * actively looking at. The web reference's own `redact()` only masks a
 * value the user just typed into a dedicated login-credential modal, a
 * browser-automation echo-back guard this app has no equivalent surface
 * for (its AskUser has no credential-fields variant) — there is nothing to
 * port.
 */
private const val DEFAULT_MAX_LENGTH = 80

fun summarizeToolCall(toolName: String, args: Map<String, String>?, maxLength: Int = DEFAULT_MAX_LENGTH): String? {
    if (args.isNullOrEmpty()) return null

    val baseTool = toolName.substringBefore(':').lowercase()
    val headline = when (baseTool) {
        "bash", "shell", "run", "run_background" -> args["command"]
        "write", "read", "read_file", "edit" -> args["file_path"] ?: args["path"] ?: args["filename"]
        "grep", "glob", "search", "find" -> args["pattern"]
        else -> null
    }?.takeIf { it.isNotBlank() }

    val summary = headline ?: args.entries
        .filter { it.value.isNotBlank() }
        .joinToString(", ") { (key, value) -> "$key=$value" }
        .takeIf { it.isNotBlank() }
        ?: return null

    return if (summary.length > maxLength) summary.take(maxLength - 1) + "…" else summary
}
