package ai.openonion.oochat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffUtilTest {

    @Test
    fun `identical strings produce no hunks`() {
        val hunks = DiffUtil.diffLines("line1\nline2\nline3", "line1\nline2\nline3")

        assertTrue(hunks.isEmpty())
    }

    @Test
    fun `pure addition produces a single ADDED line`() {
        val hunks = DiffUtil.diffLines("a\nb", "a\nb\nc")

        val added = hunks.flatMap { it.lines }.filter { it.type == DiffLineType.ADDED }
        assertEquals(1, added.size)
        assertEquals("c", added.first().content)
    }

    @Test
    fun `pure removal produces a single REMOVED line`() {
        val hunks = DiffUtil.diffLines("a\nb\nc", "a\nb")

        val removed = hunks.flatMap { it.lines }.filter { it.type == DiffLineType.REMOVED }
        assertEquals(1, removed.size)
        assertEquals("c", removed.first().content)
    }

    @Test
    fun `single-line replacement produces one removed and one added line`() {
        val hunks = DiffUtil.diffLines("hello world", "hello there")

        val lines = hunks.flatMap { it.lines }
        assertTrue(lines.any { it.type == DiffLineType.REMOVED && it.content == "hello world" })
        assertTrue(lines.any { it.type == DiffLineType.ADDED && it.content == "hello there" })
    }

    @Test
    fun `unchanged lines surrounding a change are kept as context up to contextLines`() {
        val old = (1..10).joinToString("\n") { "line$it" }
        val new = (1..10).joinToString("\n") { if (it == 5) "CHANGED" else "line$it" }

        val hunks = DiffUtil.diffLines(old, new, contextLines = 2)

        assertEquals(1, hunks.size)
        val context = hunks[0].lines.filter { it.type == DiffLineType.CONTEXT }
        assertEquals(4, context.size)
    }

    @Test
    fun `far-apart changes produce separate hunks`() {
        val old = (1..20).joinToString("\n") { "line$it" }
        val new = (1..20).joinToString("\n") {
            when (it) { 2 -> "CHANGED_A"; 18 -> "CHANGED_B"; else -> "line$it" }
        }

        val hunks = DiffUtil.diffLines(old, new, contextLines = 2)

        assertEquals(2, hunks.size)
    }
}
