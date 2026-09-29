package ai.openonion.oochat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrepResultParserTest {

    @Test
    fun `blank result returns no matches`() {
        assertTrue(GrepResultParser.parse("", "grep").isEmpty())
    }

    @Test
    fun `ripgrep-style lines are parsed into file line and content`() {
        val result = "src/Foo.kt:12:val x = 1\nsrc/Bar.kt:34:val y = 2"

        val matches = GrepResultParser.parse(result, "grep")

        assertEquals(2, matches.size)
        assertEquals(GrepMatch("src/Foo.kt", 12, "val x = 1"), matches[0])
        assertEquals(GrepMatch("src/Bar.kt", 34, "val y = 2"), matches[1])
    }

    @Test
    fun `glob results are treated as bare file paths`() {
        val result = "src/Foo.kt\nsrc/Bar.kt"

        val matches = GrepResultParser.parse(result, "glob")

        assertEquals(2, matches.size)
        assertEquals(GrepMatch("src/Foo.kt", 0, ""), matches[0])
    }

    @Test
    fun `find results are treated as bare file paths`() {
        val matches = GrepResultParser.parse("a.txt\nb.txt", "find")

        assertEquals(listOf(GrepMatch("a.txt", 0, ""), GrepMatch("b.txt", 0, "")), matches)
    }

    @Test
    fun `unrecognized format falls back to a single raw match`() {
        val result = "no matches found for pattern"

        val matches = GrepResultParser.parse(result, "search")

        assertEquals(1, matches.size)
        assertEquals(result, matches[0].content)
    }
}
