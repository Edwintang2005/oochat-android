package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A turn cut off by a dropped socket is force-failed on the spot, and the
 * reply the server had already produced comes back in its replay as a row of
 * its own. Reloaded, that used to read as "Failed" sitting directly above the
 * answer it says never arrived.
 */
class ResolveFalseFailuresTest {

    private fun failedTurn(id: String) = ChatItem.Turn(
        id = id,
        thinking = ChatItem.Thinking(id = id, status = ThinkingStatus.ERROR, model = "gemini-2.5-pro")
    )

    private fun status(items: List<ChatItem>, id: String) =
        (items.first { it.id == id } as ChatItem.Turn).thinking?.status

    @Test
    fun `a failed turn answered by the next row is not failed`() {
        val healed = listOf(
            ChatItem.User(id = "u1", content = "你好"),
            failedTurn("t1"),
            ChatItem.Agent(id = "a1", content = "你好！我是一个网络自动化助手。")
        ).resolveFalseFailures()

        assertEquals(ThinkingStatus.DONE, status(healed, "t1"))
    }

    @Test
    fun `a failed turn with nothing after it keeps saying failed`() {
        val healed = listOf(
            ChatItem.User(id = "u1", content = "你好"),
            failedTurn("t1")
        ).resolveFalseFailures()

        assertEquals(ThinkingStatus.ERROR, status(healed, "t1"))
    }

    @Test
    fun `a failed turn followed by an empty reply keeps saying failed`() {
        val healed = listOf(
            failedTurn("t1"),
            ChatItem.Agent(id = "a1", content = "   ")
        ).resolveFalseFailures()

        assertEquals(ThinkingStatus.ERROR, status(healed, "t1"))
    }

    @Test
    fun `a failed turn followed by the user asking again keeps saying failed`() {
        // The user gave up and retyped rather than the server answering.
        val healed = listOf(
            failedTurn("t1"),
            ChatItem.User(id = "u2", content = "在吗")
        ).resolveFalseFailures()

        assertEquals(ThinkingStatus.ERROR, status(healed, "t1"))
    }

    @Test
    fun `a turn that carries its own reply is left alone`() {
        // Already whole: nothing to heal, and its ERROR is its own verdict.
        val turn = ChatItem.Turn(
            id = "t1",
            thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.ERROR),
            agent = ChatItem.Agent(id = "t1", content = "partial answer")
        )
        val healed = listOf(turn, ChatItem.Agent(id = "a1", content = "later reply")).resolveFalseFailures()

        assertEquals(ThinkingStatus.ERROR, status(healed, "t1"))
    }
}
