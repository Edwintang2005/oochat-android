package ai.openonion.oochat.util

/** One matched line from a grep/ripgrep-style tool result. */
data class GrepMatch(val file: String, val line: Int, val content: String)

/**
 * Parses a [ChatItem.ToolCall][ai.openonion.oochat.domain.model.ChatItem.ToolCall]'s
 * `result` string for grep-family tools into structured matches.
 *
 * The wire protocol only ever carries the tool's raw stdout as a single
 * string (same as oo-chat-web's grep-card.tsx, which parses client-side
 * too) — there is no structured match list sent over the wire.
 */
object GrepResultParser {

    /** ripgrep's default `--with-filename --line-number` format: "path:line:content". */
    private val RIPGREP_LINE = Regex("""^(.+?):(\d+):(.*)$""")

    fun parse(result: String, tool: String): List<GrepMatch> {
        if (result.isBlank()) return emptyList()

        val lines = result.split("\n").filter { it.isNotBlank() }

        // glob/find results are typically a bare list of file paths, one
        // per line, with no line-number/content component.
        if (tool.equals("glob", ignoreCase = true) || tool.equals("find", ignoreCase = true)) {
            return lines.map { GrepMatch(file = it.trim(), line = 0, content = "") }
        }

        val matches = lines.mapNotNull { line ->
            RIPGREP_LINE.matchEntire(line)?.let { m ->
                GrepMatch(file = m.groupValues[1], line = m.groupValues[2].toIntOrNull() ?: 0, content = m.groupValues[3])
            }
        }

        // Format wasn't recognized (e.g. a tool/mode that doesn't emit
        // file:line:content) — fall back to one match carrying the raw text
        // rather than silently dropping the result.
        return matches.ifEmpty { listOf(GrepMatch(file = "", line = 0, content = result)) }
    }
}
