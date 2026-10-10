package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningGroupsTest {

    private fun user(id: String) = ChatItem.User(id = id, content = "q $id")
    private fun thought(id: String, kind: String = "reflect") =
        ChatItem.Thinking(id = id, status = ThinkingStatus.DONE, content = "thought $id", kind = kind)
    private fun tool(id: String) = ChatItem.ToolCall(id = id, name = "bash", status = ToolStatus.DONE)
    private fun reply(id: String) = ChatItem.Turn(id = id, agent = ChatItem.Agent(id = id, content = "a $id"))

    @Test
    fun `a turn's thoughts fold into its newest one, oldest first`() {
        val items = listOf(user("u1"), thought("t1", "intent"), tool("c1"), thought("t2"), tool("c2"), thought("t3"), reply("r1"))

        val grouped = items.groupReasoningByTurn()

        assertEquals(listOf("u1", "c1", "c2", "t3", "r1"), grouped.visible.map { it.id })
        val group = grouped.groups.getValue("t3")
        assertEquals(listOf("t1", "t2"), group.earlier.map { it.id })
        assertEquals("the key is the turn's first thought, stable as more arrive", "t1", group.key)
    }

    @Test
    fun `turns are grouped separately`() {
        val items = listOf(user("u1"), thought("t1"), thought("t2"), user("u2"), thought("t3"), thought("t4"))

        val grouped = items.groupReasoningByTurn()

        assertEquals(listOf("u1", "t2", "u2", "t4"), grouped.visible.map { it.id })
        assertEquals(listOf("t1"), grouped.groups.getValue("t2").earlier.map { it.id })
        assertEquals(listOf("t3"), grouped.groups.getValue("t4").earlier.map { it.id })
    }

    @Test
    fun `the group key survives a newer thought arriving`() {
        val before = listOf(user("u1"), thought("t1"), thought("t2")).groupReasoningByTurn()
        val after = listOf(user("u1"), thought("t1"), thought("t2"), thought("t3")).groupReasoningByTurn()

        assertEquals(before.groups.getValue("t2").key, after.groups.getValue("t3").key)
    }

    @Test
    fun `a single thought, or none, leaves the list as it is`() {
        val one = listOf(user("u1"), thought("t1"), reply("r1"))
        val none = listOf(user("u1"), reply("r1"))

        assertSame(one, one.groupReasoningByTurn().visible)
        assertSame(none, none.groupReasoningByTurn().visible)
        assertTrue(one.groupReasoningByTurn().groups.isEmpty())
    }

    @Test
    fun `status thinking items are not reasoning and are never folded`() {
        val status = ChatItem.Thinking(id = "e1", status = ThinkingStatus.ERROR, content = "Connection lost")
        val items = listOf(user("u1"), status, thought("t1"), thought("t2"))

        assertEquals(listOf("u1", "e1", "t2"), items.groupReasoningByTurn().visible.map { it.id })
    }

    @Test
    fun `thoughts before the first user message still form a turn`() {
        // A page of history can start mid-turn.
        val items = listOf(thought("t1"), thought("t2"), user("u1"))

        assertEquals(listOf("t2", "u1"), items.groupReasoningByTurn().visible.map { it.id })
    }
}
