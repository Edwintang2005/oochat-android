package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatItemSessionUsageTotalsTest {

    @Test
    fun `an empty list totals zero tokens, zero cost, and no context reading`() {
        val totals = emptyList<ChatItem>().sessionUsageTotals()

        assertEquals(0, totals.totalTokens)
        assertEquals(0.0, totals.totalCostUsd, 0.0)
        assertNull(totals.latestContextPercent)
    }

    @Test
    fun `a RUNNING turn contributes nothing to tokens or cost`() {
        val items = listOf(
            ChatItem.Turn(
                id = "turn-1",
                thinking = ChatItem.Thinking(
                    id = "t1",
                    status = ThinkingStatus.RUNNING,
                    tokensTotal = 999,
                    costUsd = 1.23
                )
            )
        )

        val totals = items.sessionUsageTotals()

        assertEquals(0, totals.totalTokens)
        assertEquals(0.0, totals.totalCostUsd, 0.0)
    }

    @Test
    fun `multiple DONE turns sum their tokens and cost`() {
        val items = listOf(
            ChatItem.Turn(
                id = "turn-1",
                thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, tokensTotal = 500, costUsd = 0.01)
            ),
            ChatItem.Turn(
                id = "turn-2",
                thinking = ChatItem.Thinking(id = "t2", status = ThinkingStatus.DONE, tokensTotal = 734, costUsd = 0.0071)
            )
        )

        val totals = items.sessionUsageTotals()

        assertEquals(1234, totals.totalTokens)
        assertEquals(0.0171, totals.totalCostUsd, 0.0001)
    }

    @Test
    fun `context percent takes the latest reading, not the largest`() {
        val items = listOf(
            ChatItem.Turn(
                id = "turn-1",
                thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, contextPercent = 82.0)
            ),
            ChatItem.Turn(
                id = "turn-2",
                thinking = ChatItem.Thinking(id = "t2", status = ThinkingStatus.DONE, contextPercent = 40.0)
            )
        )

        val totals = items.sessionUsageTotals()

        assertEquals(40.0, totals.latestContextPercent!!, 0.0)
    }

    @Test
    fun `a DONE turn missing cost contributes zero cost but still counts its tokens`() {
        val items = listOf(
            ChatItem.Turn(
                id = "turn-1",
                thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE, tokensTotal = 400, costUsd = null)
            )
        )

        val totals = items.sessionUsageTotals()

        assertEquals(400, totals.totalTokens)
        assertEquals(0.0, totals.totalCostUsd, 0.0)
    }
}
