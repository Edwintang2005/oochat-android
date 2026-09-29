package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.chat.markdown.InlineSpan
import ai.openonion.oochat.ui.chat.markdown.InlineStyle
import ai.openonion.oochat.ui.chat.markdown.MdBlock
import ai.openonion.oochat.ui.chat.markdown.parseInlineSpans
import ai.openonion.oochat.ui.chat.markdown.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Markdown parsers backing [MessageBubble]'s text rendering:
 * [parseMarkdownBlocks] at block level (headings, lists, code fences, quotes,
 * rules, paragraphs) and [parseInlineSpans] at inline level (bold, italic,
 * code, links). Pure-Kotlin logic, no Compose runtime needed.
 */
class MessageBubbleMarkdownTest {

    @Test
    fun `plain text becomes a single paragraph`() {
        val blocks = parseMarkdownBlocks("Hello world")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.Paragraph("Hello world"), blocks[0])
    }

    @Test
    fun `multiple paragraphs separated by blank lines`() {
        val blocks = parseMarkdownBlocks("First paragraph.\n\nSecond paragraph.")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertEquals("Second paragraph.", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `multi-line paragraph is joined with newlines preserved`() {
        val blocks = parseMarkdownBlocks("Line one\nLine two")
        assertEquals(1, blocks.size)
        assertEquals("Line one\nLine two", (blocks[0] as MdBlock.Paragraph).text)
    }

    @Test
    fun `headings at all three levels are recognized`() {
        val blocks = parseMarkdownBlocks("# H1\n\n## H2\n\n### H3")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, "H1"), blocks[0])
        assertEquals(MdBlock.Heading(2, "H2"), blocks[1])
        assertEquals(MdBlock.Heading(3, "H3"), blocks[2])
    }

    @Test
    fun `unordered list groups consecutive dash items`() {
        val blocks = parseMarkdownBlocks("- one\n- two\n- three")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.ListItems
        assertEquals(false, list.ordered)
        assertEquals(listOf("one", "two", "three"), list.items)
    }

    @Test
    fun `unordered list accepts asterisk markers`() {
        val blocks = parseMarkdownBlocks("* alpha\n* beta")
        val list = blocks[0] as MdBlock.ListItems
        assertEquals(listOf("alpha", "beta"), list.items)
    }

    @Test
    fun `ordered list groups consecutive numbered items`() {
        val blocks = parseMarkdownBlocks("1. first\n2. second\n3. third")
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.ListItems
        assertEquals(true, list.ordered)
        assertEquals(listOf("first", "second", "third"), list.items)
    }

    @Test
    fun `code fence becomes a single code block and is not split into paragraphs`() {
        val blocks = parseMarkdownBlocks("```\nval x = 1\nprintln(x)\n```")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.CodeBlock("val x = 1\nprintln(x)"), blocks[0])
    }

    @Test
    fun `code fence with language tag still yields code content only`() {
        val blocks = parseMarkdownBlocks("```kotlin\nfun foo() {}\n```")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.CodeBlock("fun foo() {}"), blocks[0])
    }

    @Test
    fun `blockquote strips the leading angle bracket per line`() {
        val blocks = parseMarkdownBlocks("> line one\n> line two")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.Quote("line one\nline two"), blocks[0])
    }

    @Test
    fun `horizontal rule is recognized for both dash and asterisk forms`() {
        assertEquals(listOf(MdBlock.Rule), parseMarkdownBlocks("---"))
        assertEquals(listOf(MdBlock.Rule), parseMarkdownBlocks("***"))
    }

    @Test
    fun `mixed document produces blocks in source order`() {
        val text = """
            # Title
            Intro paragraph.

            - item a
            - item b

            ```
            code here
            ```

            > a quote
        """.trimIndent()

        val blocks = parseMarkdownBlocks(text)

        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.ListItems)
        assertTrue(blocks[3] is MdBlock.CodeBlock)
        assertTrue(blocks[4] is MdBlock.Quote)
    }

    @Test
    fun `blank input yields no blocks`() {
        assertEquals(emptyList<MdBlock>(), parseMarkdownBlocks(""))
    }

    // --- Inline spans -----------------------------------------------------
    // Four independent regex passes used to double-emit: `**hi**` matched bold
    // AND italic (as `*hi`), rendering "hi*hi*". The visible text is asserted
    // alongside the styling, since that regression only showed up in the text.

    private fun visibleText(text: String) = parseInlineSpans(text).joinToString("") { it.text }

    @Test
    fun `bold markers are consumed once and not re-matched as italic`() {
        assertEquals(listOf(InlineSpan(InlineStyle.BOLD, "hi")), parseInlineSpans("**hi**"))
        assertEquals("hi", visibleText("**hi**"))
    }

    @Test
    fun `single asterisks are italic`() {
        assertEquals(listOf(InlineSpan(InlineStyle.ITALIC, "italic")), parseInlineSpans("*italic*"))
    }

    @Test
    fun `bold and italic in one line keep their own runs`() {
        assertEquals(
            listOf(
                InlineSpan(InlineStyle.BOLD, "bold"),
                InlineSpan(InlineStyle.PLAIN, " and "),
                InlineSpan(InlineStyle.ITALIC, "italic")
            ),
            parseInlineSpans("**bold** and *italic*")
        )
        assertEquals("bold and italic", visibleText("**bold** and *italic*"))
    }

    @Test
    fun `triple asterisks are bold and italic together`() {
        assertEquals(listOf(InlineSpan(InlineStyle.BOLD_ITALIC, "both")), parseInlineSpans("***both***"))
    }

    @Test
    fun `inline code keeps its content verbatim`() {
        assertEquals(listOf(InlineSpan(InlineStyle.CODE, "code")), parseInlineSpans("`code`"))
        // Asterisks inside a code span are literal, not italic markers.
        assertEquals(listOf(InlineSpan(InlineStyle.CODE, "*x*")), parseInlineSpans("`*x*`"))
    }

    @Test
    fun `link renders its label and drops the url`() {
        assertEquals(listOf(InlineSpan(InlineStyle.LINK, "text")), parseInlineSpans("[text](url)"))
        assertEquals("see docs now", visibleText("see [docs](https://x.y) now"))
    }

    @Test
    fun `a lone asterisk inside bold stays part of the bold run`() {
        assertEquals(listOf(InlineSpan(InlineStyle.BOLD, "2 * 3")), parseInlineSpans("**2 * 3**"))
    }

    @Test
    fun `unmatched markers are left as plain text`() {
        assertEquals(listOf(InlineSpan(InlineStyle.PLAIN, "5 * 3 = 15")), parseInlineSpans("5 * 3 = 15"))
        assertEquals(listOf(InlineSpan(InlineStyle.PLAIN, "**unclosed")), parseInlineSpans("**unclosed"))
    }

    @Test
    fun `plain text passes through as one span`() {
        assertEquals(listOf(InlineSpan(InlineStyle.PLAIN, "Hello world")), parseInlineSpans("Hello world"))
        assertEquals(emptyList<InlineSpan>(), parseInlineSpans(""))
    }

    @Test
    fun `adjacent bold runs keep the text between them`() {
        assertEquals(
            listOf(
                InlineSpan(InlineStyle.BOLD, "a"),
                InlineSpan(InlineStyle.PLAIN, "b"),
                InlineSpan(InlineStyle.BOLD, "c")
            ),
            parseInlineSpans("**a**b**c**")
        )
        assertEquals("abc", visibleText("**a**b**c**"))
    }
}
