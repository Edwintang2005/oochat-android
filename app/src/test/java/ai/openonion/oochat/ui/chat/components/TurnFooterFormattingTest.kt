package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ThinkingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a turn's footer says, and how each number in it is formatted. A finished
 * turn is numbers only — no state word, no model, no context reading — while
 * running and failed still lead with a word.
 */
class TurnFooterFormattingTest {

    @Test
    fun `a context percentage is rounded, not rescaled`() {
        // core/agent.py's context_percent is documented "(0-100)" and
        // computed as input_tokens / limit * 100, and the reference web
        // client only rounds it. Multiplying by 100 again turned a third of
        // the window into "3295% ctx".
        assertEquals(33, formatContextPercent(32.95))
        assertEquals(0, formatContextPercent(0.3295))
        assertEquals(100, formatContextPercent(99.6))
    }

    @Test
    fun `token counts switch to thousands with one decimal`() {
        assertEquals("950", formatTokenCount(950))
        assertEquals("1.0k", formatTokenCount(1000))
        assertEquals("1.2k", formatTokenCount(1234))
        assertEquals("128.0k", formatTokenCount(128_000))
    }

    @Test
    fun `a sub-cent call keeps enough places to stay non-zero`() {
        // Most single calls cost fractions of a cent; two decimal places
        // would report every one of them as "$0.00".
        assertEquals("$0.0071", formatCostUsd(0.00711))
        assertEquals("$0.0001", formatCostUsd(0.0001))
        assertEquals("$0.42", formatCostUsd(0.42))
        assertEquals("$12.30", formatCostUsd(12.3))
    }

    private fun thinking(
        status: ThinkingStatus = ThinkingStatus.DONE,
        model: String? = "gemini-2.5-flash",
        durationMs: Double? = 2000.0,
        tokensTotal: Int? = 46,
        costUsd: Double? = null,
        contextPercent: Double? = null
    ) = ChatItem.Thinking(
        id = "t1",
        status = status,
        model = model,
        durationMs = durationMs,
        tokensTotal = tokensTotal,
        costUsd = costUsd,
        contextPercent = contextPercent
    )

    @Test
    fun `a finished turn says neither Done nor its model name`() {
        // The tick already says "done", and the model is permanently in the
        // top bar on every screen — so the line is the numbers and nothing else.
        assertEquals("2s · 46 tokens", turnFooterLabel(thinking()))
    }

    @Test
    fun `a cost that would print as four zeros is dropped, not shown`() {
        assertEquals("2s · 46 tokens", turnFooterLabel(thinking(costUsd = 0.00004)))
        assertEquals("2s · 46 tokens · $0.0001", turnFooterLabel(thinking(costUsd = 0.0001)))
    }

    @Test
    fun `the context reading never reaches the footer at all`() {
        // Context is surfaced once, in the session usage strip on the input
        // bar. The footer neither prints it nor draws a ring for it.
        assertEquals("2s · 46 tokens", turnFooterLabel(thinking(contextPercent = 4.2)))
        assertEquals("2s · 46 tokens", turnFooterLabel(thinking(contextPercent = 92.0)))
    }

    @Test
    fun `a running turn takes the design's word, and a failed one still names itself`() {
        assertEquals(
            "Generating… · gemini-2.5-flash · 2s · 46 tokens",
            turnFooterLabel(thinking(status = ThinkingStatus.RUNNING))
        )
        // A failure is worth announcing, so it keeps its word and its model.
        assertEquals(
            "Failed · gemini-2.5-flash · 2s · 46 tokens",
            turnFooterLabel(thinking(status = ThinkingStatus.ERROR))
        )
    }

    @Test
    fun `a finished turn with no metadata at all says nothing`() {
        // What a row persisted before turn metadata was stored decodes to. The
        // footer drops the text and leaves the tick standing alone.
        assertEquals(
            "",
            turnFooterLabel(thinking(model = null, durationMs = null, tokensTotal = null))
        )
    }
}
