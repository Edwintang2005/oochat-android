package ai.openonion.oochat.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatItemIsInProgressTest {

    @Test
    fun `a RUNNING Thinking item is in progress, DONE and ERROR are not`() {
        assertTrue(ChatItem.Thinking(id = "t", status = ThinkingStatus.RUNNING).isInProgress())
        assertFalse(ChatItem.Thinking(id = "t", status = ThinkingStatus.DONE).isInProgress())
        assertFalse(ChatItem.Thinking(id = "t", status = ThinkingStatus.ERROR).isInProgress())
    }

    @Test
    fun `a RUNNING ToolCall item is in progress, DONE and ERROR are not`() {
        assertTrue(ChatItem.ToolCall(id = "tc", name = "search", status = ToolStatus.RUNNING).isInProgress())
        assertFalse(ChatItem.ToolCall(id = "tc", name = "search", status = ToolStatus.DONE).isInProgress())
        assertFalse(ChatItem.ToolCall(id = "tc", name = "search", status = ToolStatus.ERROR).isInProgress())
    }

    @Test
    fun `a Turn is in progress while its thinking is RUNNING or nothing has opened it`() {
        assertTrue(
            ChatItem.Turn(id = "turn", thinking = ChatItem.Thinking(id = "t", status = ThinkingStatus.RUNNING))
                .isInProgress()
        )
        assertTrue(ChatItem.Turn(id = "turn", agent = null).isInProgress())
        assertFalse(
            ChatItem.Turn(
                id = "turn",
                thinking = ChatItem.Thinking(id = "t", status = ThinkingStatus.DONE),
                agent = ChatItem.Agent(id = "a", content = "done")
            ).isInProgress()
        )
    }

    @Test
    fun `a settled Turn that produced no reply is finished, not in flight`() {
        // The shape a reloaded turn_thinking row decodes to. Reading it as in
        // flight is what left the composer showing Stop on a freshly opened app.
        assertFalse(
            ChatItem.Turn(id = "turn", thinking = ChatItem.Thinking(id = "turn", status = ThinkingStatus.DONE))
                .isInProgress()
        )
        assertFalse(
            ChatItem.Turn(id = "turn", thinking = ChatItem.Thinking(id = "turn", status = ThinkingStatus.ERROR))
                .isInProgress()
        )
    }

    @Test
    fun `terminal and action-required item types are never in progress`() {
        assertFalse(ChatItem.User(id = "u", content = "hi").isInProgress())
        assertFalse(ChatItem.Agent(id = "a", content = "hi").isInProgress())
        assertFalse(ChatItem.AskUser(id = "ask", text = "?", options = emptyList(), multiSelect = false).isInProgress())
        assertFalse(
            ChatItem.ApprovalNeeded(id = "ap", tool = "x", arguments = emptyMap()).isInProgress()
        )
    }
}
