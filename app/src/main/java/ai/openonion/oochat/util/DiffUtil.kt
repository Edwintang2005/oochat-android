package ai.openonion.oochat.util

/** One line of a computed diff, tagged with how it changed. */
data class DiffLine(val type: DiffLineType, val content: String)

enum class DiffLineType { ADDED, REMOVED, CONTEXT }

/** A contiguous run of [DiffLine]s, with the line numbers it starts at in each file. */
data class DiffHunk(val oldStart: Int, val newStart: Int, val lines: List<DiffLine>)

/**
 * Computes unified-diff-style hunks from two full-file/full-block strings.
 *
 * The `edit` tool's wire args only ever carry `old_string`/`new_string` as
 * plain text (see oo-chat-web's file-diff-card.tsx) — hunks are computed
 * client-side there too, so this mirrors that rather than expecting
 * structured diff data over the wire.
 */
object DiffUtil {

    fun diffLines(oldString: String, newString: String, contextLines: Int = 3): List<DiffHunk> {
        val oldLines = oldString.split("\n")
        val newLines = newString.split("\n")

        val ops = lcsOps(oldLines, newLines)
        if (ops.all { it.type == DiffLineType.CONTEXT }) return emptyList()

        return groupIntoHunks(ops, contextLines)
    }

    /** One diffed line plus its 1-based position in the old/new file (0 when not present in that file). */
    private data class Op(val type: DiffLineType, val content: String, val oldLine: Int, val newLine: Int)

    /** Classic O(n*m) LCS table, then backtrack to produce a line-level edit script. */
    private fun lcsOps(oldLines: List<String>, newLines: List<String>): List<Op> {
        val n = oldLines.size
        val m = newLines.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (oldLines[i] == newLines[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldLines[i] == newLines[j] -> {
                    ops += Op(DiffLineType.CONTEXT, oldLines[i], i + 1, j + 1)
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops += Op(DiffLineType.REMOVED, oldLines[i], i + 1, 0)
                    i++
                }
                else -> {
                    ops += Op(DiffLineType.ADDED, newLines[j], 0, j + 1)
                    j++
                }
            }
        }
        while (i < n) { ops += Op(DiffLineType.REMOVED, oldLines[i], i + 1, 0); i++ }
        while (j < m) { ops += Op(DiffLineType.ADDED, newLines[j], 0, j + 1); j++ }
        return ops
    }

    private fun groupIntoHunks(ops: List<Op>, contextLines: Int): List<DiffHunk> {
        // Indices of ops that are part of a change (added/removed), each
        // expanded by `contextLines` on either side, then merged where runs
        // overlap or touch — the standard unified-diff hunk grouping.
        val changeIndices = ops.indices.filter { ops[it].type != DiffLineType.CONTEXT }
        if (changeIndices.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        for (idx in changeIndices) {
            val start = maxOf(0, idx - contextLines)
            val end = minOf(ops.lastIndex, idx + contextLines)
            val last = ranges.lastOrNull()
            if (last != null && start <= last.last + 1) {
                ranges[ranges.lastIndex] = last.first..maxOf(last.last, end)
            } else {
                ranges += start..end
            }
        }

        return ranges.map { range ->
            val hunkOps = ops.subList(range.first, range.last + 1)
            val oldStart = hunkOps.firstOrNull { it.oldLine > 0 }?.oldLine ?: 0
            val newStart = hunkOps.firstOrNull { it.newLine > 0 }?.newLine ?: 0
            DiffHunk(
                oldStart = oldStart,
                newStart = newStart,
                lines = hunkOps.map { DiffLine(it.type, it.content) }
            )
        }
    }
}
