package ai.openonion.oochat.ui.chat.markdown

import ai.openonion.oochat.ui.theme.chatBody
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

object MarkdownRenderer {
    @Composable
    fun RenderBlocks(
        text: String,
        textColor: Color,
        modifier: Modifier = Modifier
    ) {
        val blocks = remember(text) { parseMarkdownBlocks(text) }
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { MarkdownBlockContent(it, textColor) }
        }
    }
}

// ─── Block-level Markdown ────────────────────────────────────────────────

/**
 * A single block-level Markdown element, matching the constructs styled by
 * the Figma design's `.md-bubble` CSS (headings, lists, code fences,
 * blockquotes, horizontal rules, paragraphs). Inline styling (bold/italic/
 * inline-code/links) within a block's text is applied separately by
 * [parseInlineMarkdown] at render time.
 */
internal sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class ListItems(val items: List<String>, val ordered: Boolean) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    object Rule : MdBlock()
}

@Composable
internal fun MarkdownBlockContent(block: MdBlock, textColor: Color) {
    // Size for inline `code` spans — matches the fenced code block's size so
    // inline and block code read as one family. A [SpanStyle] can't take a
    // whole [TextStyle], so this is passed down as a bare TextUnit. Neither
    // this nor any of the style tokens below need manual scaling: ConnectOnionTheme
    // overrides LocalDensity from the user's font-size preference, so every `.sp`
    // value here is measured against that scaled density automatically.
    val codeFontSize = MaterialTheme.typography.bodySmall.fontSize
    when (block) {
        is MdBlock.Heading -> {
            val baseStyle = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.labelLarge
            }
            Text(
                text = parseInlineMarkdown(block.text, textColor, codeFontSize),
                style = baseStyle,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }

        is MdBlock.Paragraph -> {
            Text(
                text = parseInlineMarkdown(block.text, textColor, codeFontSize),
                style = MaterialTheme.typography.chatBody,
                color = textColor
            )
        }

        is MdBlock.ListItems -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row {
                        Text(
                            text = if (block.ordered) "${index + 1}." else "•",
                            style = MaterialTheme.typography.chatBody,
                            color = textColor,
                            modifier = Modifier.width(22.dp)
                        )
                        Text(
                            text = parseInlineMarkdown(item, textColor, codeFontSize),
                            style = MaterialTheme.typography.chatBody,
                            color = textColor
                        )
                    }
                }
            }
        }

        is MdBlock.CodeBlock -> {
            // Tint derived from textColor (already theme-correct: onSurface —
            // dark in light mode, light in dark mode) rather than a fixed
            // black overlay, which read as nearly invisible against the
            // already-dark surfaceContainer in dark mode. Both bubbles now
            // render onSurface text, so one alpha covers both.
            val codeBackground = textColor.copy(alpha = 0.10f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(codeBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = textColor
                )
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(textColor.copy(alpha = 0.4f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = parseInlineMarkdown(block.text, textColor, codeFontSize),
                    style = MaterialTheme.typography.chatBody.copy(fontStyle = FontStyle.Italic),
                    color = textColor.copy(alpha = 0.85f)
                )
            }
        }

        MdBlock.Rule -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(textColor.copy(alpha = 0.25f))
            )
        }
    }
}

private val UNORDERED_ITEM = Regex("""^[-*]\s+(.*)$""")
private val ORDERED_ITEM = Regex("""^\d+\.\s+(.*)$""")

/**
 * Split raw Markdown into [MdBlock]s: consecutive non-blank, non-special lines
 * form a paragraph; ```-fenced lines form a code block (rendered verbatim,
 * never inline-parsed); `#`/`##`/`###` lines are headings; `-`/`*` or `1.`
 * lines are grouped into a list; `>` lines are grouped into a blockquote;
 * `---`/`***` alone on a line is a horizontal rule.
 */
internal fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.isEmpty() -> i++

            trimmed.startsWith("```") -> {
                i++
                val codeLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines += lines[i]
                    i++
                }
                if (i < lines.size) i++ // consume closing fence
                blocks += MdBlock.CodeBlock(codeLines.joinToString("\n"))
            }

            trimmed.startsWith("### ") -> { blocks += MdBlock.Heading(3, trimmed.removePrefix("### ")); i++ }
            trimmed.startsWith("## ") -> { blocks += MdBlock.Heading(2, trimmed.removePrefix("## ")); i++ }
            trimmed.startsWith("# ") -> { blocks += MdBlock.Heading(1, trimmed.removePrefix("# ")); i++ }

            trimmed == "---" || trimmed == "***" -> { blocks += MdBlock.Rule; i++ }

            trimmed.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += MdBlock.Quote(quoteLines.joinToString("\n"))
            }

            UNORDERED_ITEM.matches(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && UNORDERED_ITEM.matches(lines[i].trim())) {
                    items += UNORDERED_ITEM.find(lines[i].trim())!!.groupValues[1]
                    i++
                }
                blocks += MdBlock.ListItems(items, ordered = false)
            }

            ORDERED_ITEM.matches(trimmed) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && ORDERED_ITEM.matches(lines[i].trim())) {
                    items += ORDERED_ITEM.find(lines[i].trim())!!.groupValues[1]
                    i++
                }
                blocks += MdBlock.ListItems(items, ordered = true)
            }

            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size &&
                    lines[i].trim().isNotEmpty() &&
                    !lines[i].trim().startsWith("```") &&
                    !lines[i].trim().startsWith("#") &&
                    !lines[i].trim().startsWith(">") &&
                    lines[i].trim() != "---" &&
                    lines[i].trim() != "***" &&
                    !UNORDERED_ITEM.matches(lines[i].trim()) &&
                    !ORDERED_ITEM.matches(lines[i].trim())
                ) {
                    paraLines += lines[i]
                    i++
                }
                blocks += MdBlock.Paragraph(paraLines.joinToString("\n"))
            }
        }
    }

    return blocks
}

/**
 * Inline Markdown within a single block: **bold**, *italic*, `code`, and
 * [links](url). Kept separate from block parsing so code-block contents are
 * never re-interpreted as inline markup.
 */
private fun parseInlineMarkdown(text: String, textColor: Color, codeFontSize: TextUnit): AnnotatedString {
    return buildAnnotatedString {
        parseInlineSpans(text).forEach { span ->
            val style = when (span.style) {
                InlineStyle.PLAIN -> null
                InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                InlineStyle.BOLD_ITALIC -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )
                // The background is derived from textColor (theme-correct in
                // both modes) rather than a fixed black overlay — see the
                // matching comment on the block-level code path.
                InlineStyle.CODE -> SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeFontSize,
                    background = textColor.copy(alpha = 0.10f)
                )
                InlineStyle.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
            }
            if (style == null) append(span.text) else withStyle(style) { append(span.text) }
        }
    }
}

/** Inline styles [parseInlineSpans] can emit. */
internal enum class InlineStyle { PLAIN, BOLD, ITALIC, BOLD_ITALIC, CODE, LINK }

/** [text] is the visible run, delimiters already stripped. */
internal data class InlineSpan(val style: InlineStyle, val text: String)

private val INLINE_PATTERN =
    Regex("""\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)]\((.+?)\)""")

/**
 * Splits [text] into styled runs in a single left-to-right scan.
 *
 * One combined alternation — not four independent `findAll` passes — is what
 * keeps the scan non-overlapping: the old passes let `**hi**` match bold *and*
 * italic (as `*hi`), so the inner text was emitted twice and the write cursor
 * walked backwards. Alternatives are ordered longest-delimiter first so
 * `***x***` and `**x**` win over `*x*` at the same position.
 */
internal fun parseInlineSpans(text: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var index = 0
    INLINE_PATTERN.findAll(text).forEach { match ->
        if (match.range.first > index) {
            spans += InlineSpan(InlineStyle.PLAIN, text.substring(index, match.range.first))
        }
        val groups = match.groupValues
        spans += when {
            groups[1].isNotEmpty() -> InlineSpan(InlineStyle.BOLD_ITALIC, groups[1])
            groups[2].isNotEmpty() -> InlineSpan(InlineStyle.BOLD, groups[2])
            groups[3].isNotEmpty() -> InlineSpan(InlineStyle.ITALIC, groups[3])
            groups[4].isNotEmpty() -> InlineSpan(InlineStyle.CODE, groups[4])
            else -> InlineSpan(InlineStyle.LINK, groups[5])
        }
        index = match.range.last + 1
    }
    if (index < text.length) {
        spans += InlineSpan(InlineStyle.PLAIN, text.substring(index))
    }
    return spans
}
