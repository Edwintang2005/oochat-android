package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatEventReducer], extracted from
 * [ai.openonion.oochat.ui.chat.ChatViewModel]'s event collector.
 * These previously had no dedicated coverage of their own — only indirect
 * exercise through [ai.openonion.oochat.ui.chat.ChatViewModelTest]'s
 * broader-scope cases.
 */
class ChatEventReducerTest {

    // ── ChatItemReceived ─────────────────────────────────────────────

    @Test
    fun `ChatItemReceived appends the item and marks it for persistence`() {
        val item = ChatItem.User(id = "u1", content = "hello")

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(item), emptySet())

        assertEquals(listOf(item), result.chatItems)
        assertEquals(item, result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived filters out an ignored id`() {
        val item = ChatItem.User(id = "u1", content = "hello")

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(item), setOf("u1"))

        assertTrue(result.chatItems.isEmpty())
        assertNull(result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived appends a mode_changed item like any other status item`() {
        val item = ChatItem.ModeChangedItem(id = "mode-1", mode = "plan", triggeredBy = "agent")

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(item), emptySet())

        assertEquals(listOf(item), result.chatItems)
        assertEquals(item, result.itemToPersist)
    }

    @Test
    fun `two mode_changed items with distinct ids both stay in the list`() {
        // ProtocolParser mints a fresh id per mode_changed frame (the server
        // never sends one), so a checkpoint exit followed by a fresh entry
        // must show as two separate dividers, not dedupe into one.
        val entered = ChatItem.ModeChangedItem(id = "mode-1", mode = "ulw", triggeredBy = "user")
        val exited = ChatItem.ModeChangedItem(id = "mode-2", mode = "safe", triggeredBy = "ulw_checkpoint")

        val afterFirst = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(entered), emptySet())
        val afterSecond = ChatEventReducer.reduce(afterFirst.chatItems, ChatEvent.ChatItemReceived(exited), emptySet())

        assertEquals(listOf(entered, exited), afterSecond.chatItems)
    }

    @Test
    fun `ChatItemReceived deduplicates against an existing item with the same id`() {
        val existing = ChatItem.User(id = "u1", content = "hello")
        val replay = ChatItem.User(id = "u1", content = "hello")

        val result = ChatEventReducer.reduce(listOf(existing), ChatEvent.ChatItemReceived(replay), emptySet())

        assertEquals(1, result.chatItems.size)
    }

    @Test
    fun `ChatItemReceived replaces an existing unanswered OnboardRequired card instead of appending`() {
        // Server omits an id on this event type, so ProtocolParser mints a
        // fresh random one each time onboarding is (re-)triggered — the
        // reducer must not treat those as distinct items.
        val first = ChatItem.OnboardRequired(id = "generated-1", methods = listOf("invite_code"))
        val second = ChatItem.OnboardRequired(id = "generated-2", methods = listOf("invite_code", "payment"))

        val result = ChatEventReducer.reduce(listOf(first), ChatEvent.ChatItemReceived(second), emptySet())

        assertEquals(listOf(second), result.chatItems)
        assertEquals(second, result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived appends OnboardRequired when none exists yet`() {
        val item = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(item), emptySet())

        assertEquals(listOf(item), result.chatItems)
    }

    @Test
    fun `ChatItemReceived OnboardingFailed replaces an existing OnboardRequired card in place`() {
        // The bug this test guards against: server rejected the invite code,
        // and the previous revision left the OnboardRequired card stuck on
        // "Verifying…" with `submitted = true` permanently. The reducer
        // must swap the failed item in at the same index so the chat list
        // shows one gate card throughout its lifecycle (required → failed
        // → required → success), not appending a sibling.
        val required = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        val user = ChatItem.User(id = "u1", content = "hi")
        val failed = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")

        val result = ChatEventReducer.reduce(
            listOf(user, required),
            ChatEvent.ChatItemReceived(failed),
            emptySet()
        )

        assertEquals(listOf(user, failed), result.chatItems)
        assertEquals(failed, result.itemToPersist)
    }

    @Test
    fun `a rejection and its re-prompt leave one card whose id never changes`() {
        // The whole rejection round trip in one place. AgentConnection carries
        // the card id across the re-prompt; the reducer must land it back on
        // the same row, because a changed id is a changed LazyColumn key and
        // that rebuilds the row (and its keyboard) under the user.
        val required = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))
        val failed = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        val rePrompt = ChatItem.OnboardRequired(id = "ob1", methods = listOf("invite_code"))

        val afterFailure = ChatEventReducer.reduce(
            listOf(required),
            ChatEvent.ChatItemReceived(failed),
            emptySet()
        )
        val afterRePrompt = ChatEventReducer.reduce(
            afterFailure.chatItems,
            ChatEvent.ChatItemReceived(rePrompt),
            emptySet()
        )

        assertEquals(listOf(rePrompt), afterRePrompt.chatItems)
        assertEquals("ob1", afterRePrompt.chatItems.single().id)
    }

    @Test
    fun `a re-prompt under a new id replaces the card rather than duplicating it`() {
        // Why the id has to be pinned upstream in AgentConnection and cannot be
        // left to the reducer: the OnboardRequired branch matches by type, so a
        // re-prompt under the server's new id still lands on the one card — but
        // it lands there wearing a different id, and MessageListScreen keys its
        // LazyColumn on exactly that. One card, new key, rebuilt row.
        val failed = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        val rePrompt = ChatItem.OnboardRequired(id = "ob2", methods = listOf("invite_code"))

        val result = ChatEventReducer.reduce(
            listOf(failed),
            ChatEvent.ChatItemReceived(rePrompt),
            emptySet()
        )

        assertEquals(listOf(rePrompt), result.chatItems)
        assertEquals("ob2", result.chatItems.single().id)
    }

    @Test
    fun `ChatItemReceived OnboardSuccess drops both OnboardRequired and OnboardingFailed`() {
        // Success must NOT leave an earlier failure card orphaned in the
        // chat list. A follow-up reconnect that re-emits OnboardRequired
        // should start from a clean slate, not inherit a stale failure.
        val user = ChatItem.User(id = "u1", content = "hi")
        val failed = ChatItem.OnboardingFailed(id = "ob1", reason = "Invalid invite code")
        val success = ChatItem.OnboardSuccess(id = "ob1", level = "contact", message = "Welcome")

        val result = ChatEventReducer.reduce(
            listOf(user, failed),
            ChatEvent.ChatItemReceived(success),
            emptySet()
        )

        assertEquals(listOf(user, success), result.chatItems)
        assertEquals(success, result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived keeps OnboardRequired's original position when replaced`() {
        val before = ChatItem.User(id = "u1", content = "hi")
        val first = ChatItem.OnboardRequired(id = "generated-1", methods = listOf("invite_code"))
        val second = ChatItem.OnboardRequired(id = "generated-2", methods = listOf("invite_code"))

        val result = ChatEventReducer.reduce(listOf(before, first), ChatEvent.ChatItemReceived(second), emptySet())

        assertEquals(listOf(before, second), result.chatItems)
    }

    @Test
    fun `ChatItemReceived appends a DiffPreviewItem like any other informational card`() {
        val item = ChatItem.DiffPreviewItem(id = "d1", path = "src/App.tsx", preview = "+added")

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(item), emptySet())

        assertEquals(listOf(item), result.chatItems)
        assertEquals(item, result.itemToPersist)
    }

    // ── ChatItemReceived: agent_image merge ───────────────────────────

    @Test
    fun `ChatItemReceived merges an agent_image marker into the currently-running Turn`() {
        val runningTurn = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING)
        )
        val imageMarker = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))

        val result = ChatEventReducer.reduce(listOf(runningTurn), ChatEvent.ChatItemReceived(imageMarker), emptySet())

        assertEquals(1, result.chatItems.size)
        val mergedTurn = result.chatItems.single() as ChatItem.Turn
        assertEquals("turn-1", mergedTurn.id)
        assertEquals(listOf("https://example.com/a.png"), mergedTurn.agent?.images)
        assertEquals(mergedTurn, result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived accumulates a second agent_image onto a Turn that already has one`() {
        val runningTurn = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING),
            agent = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))
        )
        val secondImage = ChatItem.Agent(id = "img-2", content = "", images = listOf("https://example.com/b.png"))

        val result = ChatEventReducer.reduce(listOf(runningTurn), ChatEvent.ChatItemReceived(secondImage), emptySet())

        val mergedTurn = result.chatItems.single() as ChatItem.Turn
        assertEquals(listOf("https://example.com/a.png", "https://example.com/b.png"), mergedTurn.agent?.images)
    }

    @Test
    fun `ChatItemReceived does not duplicate a redelivered agent_image already merged onto the running Turn`() {
        // Same image URL arriving twice (reconnect/redelivery — see
        // ProtocolParser's own comment on the "agent_image" case) must not
        // duplicate within the merged Turn's image list.
        val runningTurn = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING),
            agent = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))
        )
        val redelivered = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))

        val result = ChatEventReducer.reduce(listOf(runningTurn), ChatEvent.ChatItemReceived(redelivered), emptySet())

        val mergedTurn = result.chatItems.single() as ChatItem.Turn
        assertEquals(listOf("https://example.com/a.png"), mergedTurn.agent?.images)
        assertNull("a pure no-op redelivery has nothing new to persist", result.itemToPersist)
    }

    @Test
    fun `ChatItemReceived falls back to a standalone Agent item when no Turn is in flight`() {
        val imageMarker = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemReceived(imageMarker), emptySet())

        assertEquals(listOf(imageMarker), result.chatItems)
    }

    @Test
    fun `ChatItemReceived does not treat a normal non-blank Agent reply as an agent_image marker`() {
        val runningTurn = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING)
        )
        val normalReply = ChatItem.Agent(id = "reply-1", content = "hello there")

        val result = ChatEventReducer.reduce(listOf(runningTurn), ChatEvent.ChatItemReceived(normalReply), emptySet())

        // Appended standalone, exactly like before — must not be swallowed
        // into the running Turn just because a Turn happens to be in flight.
        assertEquals(listOf(runningTurn, normalReply), result.chatItems)
    }

    // ── ChatItemUpdated ──────────────────────────────────────────────

    @Test
    fun `ChatItemUpdated preserves a Turn's already-merged agent when the update carries none`() {
        // Mirrors llm_result: it rebuilds the Turn from scratch with only a
        // new thinking status, agent defaults to null. Without preserving
        // the existing agent, this would silently wipe out an agent_image
        // already merged onto this same Turn id.
        val turnWithImage = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING),
            agent = ChatItem.Agent(id = "img-1", content = "", images = listOf("https://example.com/a.png"))
        )
        val llmResultUpdate = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.DONE)
        )

        val result = ChatEventReducer.reduce(listOf(turnWithImage), ChatEvent.ChatItemUpdated(llmResultUpdate), emptySet())

        val updatedTurn = result.chatItems.single() as ChatItem.Turn
        assertEquals(ThinkingStatus.DONE, updatedTurn.thinking?.status)
        assertEquals(listOf("https://example.com/a.png"), updatedTurn.agent?.images)
    }

    @Test
    fun `ChatItemUpdated preserves a ToolCall's args when tool_result carries none`() {
        // Real bug, found on-device: ProtocolParser's "tool_result" branch
        // never sets `args` (the server doesn't repeat them), so the naive
        // replace here wiped the command/path the RUNNING "tool_call" item
        // had — every finished tool card lost the one thing that said what
        // it actually did, leaving just its bare name.
        val running = ChatItem.ToolCall(
            id = "tool-1",
            name = "bash",
            args = mapOf("command" to "ls -la"),
            status = ToolStatus.RUNNING
        )
        val toolResultUpdate = ChatItem.ToolCall(
            id = "tool-1",
            name = "bash",
            status = ToolStatus.DONE,
            result = "total 0"
        )

        val result = ChatEventReducer.reduce(listOf(running), ChatEvent.ChatItemUpdated(toolResultUpdate), emptySet())

        val updated = result.chatItems.single() as ChatItem.ToolCall
        assertEquals(mapOf("command" to "ls -la"), updated.args)
        assertEquals(ToolStatus.DONE, updated.status)
        assertEquals("total 0", updated.result)
    }

    @Test
    fun `ChatItemUpdated does not resurrect args once cleared on purpose`() {
        // Guards the merge from becoming a blanket "OR the old value in" —
        // it must only fill a null args, not override a deliberately
        // different (even empty) one from the update itself.
        val running = ChatItem.ToolCall(
            id = "tool-1",
            name = "bash",
            args = mapOf("command" to "ls -la"),
            status = ToolStatus.RUNNING
        )
        val update = ChatItem.ToolCall(
            id = "tool-1",
            name = "bash",
            args = emptyMap(),
            status = ToolStatus.DONE
        )

        val result = ChatEventReducer.reduce(listOf(running), ChatEvent.ChatItemUpdated(update), emptySet())

        val updated = result.chatItems.single() as ChatItem.ToolCall
        assertEquals(emptyMap<String, String>(), updated.args)
    }

    @Test
    fun `ChatItemUpdated replaces an existing item by id in place`() {
        val original = ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)
        val updated = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE)

        val result = ChatEventReducer.reduce(listOf(original), ChatEvent.ChatItemUpdated(updated), emptySet())

        assertEquals(listOf(updated), result.chatItems)
        assertEquals(updated, result.itemToPersist)
    }

    @Test
    fun `ChatItemUpdated appends when no item with the same id exists yet`() {
        val updated = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE)

        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.ChatItemUpdated(updated), emptySet())

        assertEquals(listOf(updated), result.chatItems)
    }

    @Test
    fun `ChatItemUpdated filters out an ignored id`() {
        val original = ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING)
        val updated = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE)

        val result = ChatEventReducer.reduce(listOf(original), ChatEvent.ChatItemUpdated(updated), setOf("t1"))

        // Unchanged — the ignored update is dropped, original item stays as-is.
        assertEquals(listOf(original), result.chatItems)
        assertNull(result.itemToPersist)
    }

    // ── OutputReceived ───────────────────────────────────────────────

    @Test
    fun `OutputReceived creates a Turn with a content-derived stable id`() {
        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.OutputReceived("hello world", null), emptySet())

        assertEquals(1, result.chatItems.size)
        val turn = result.chatItems.single() as ChatItem.Turn
        assertEquals(stableAssistantId("hello world"), turn.id)
        assertEquals("hello world", turn.agent?.content)
        assertSame(turn, result.itemToPersist)
    }

    @Test
    fun `OutputReceived trims the result before hashing and comparing`() {
        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.OutputReceived("  hello  ", null), emptySet())

        val turn = result.chatItems.single() as ChatItem.Turn
        assertEquals("hello", turn.agent?.content)
        assertEquals(stableAssistantId("hello"), turn.id)
    }

    @Test
    fun `OutputReceived does not duplicate when an Agent bubble with the same content already exists`() {
        val existing = ChatItem.Agent(id = "already-there", content = "same reply")

        val result = ChatEventReducer.reduce(listOf(existing), ChatEvent.OutputReceived("same reply", null), emptySet())

        assertEquals(listOf(existing), result.chatItems)
        assertNull(result.itemToPersist)
    }

    @Test
    fun `OutputReceived does not duplicate when a Turn with the same agent content already exists`() {
        // This is the "other half" of the session_sync dedup bug class
        // documented in ProtocolParserSessionSyncTest.kt: a live
        // llm_call/llm_result flow's OutputReceived must not create a
        // second Turn once a session_sync replay (or a prior OutputReceived)
        // already rendered a Turn with the same content-derived id.
        val stableId = stableAssistantId("same reply")
        val existingTurn = ChatItem.Turn(
            id = stableId,
            agent = ChatItem.Agent(id = stableId, content = "same reply")
        )

        val result = ChatEventReducer.reduce(listOf(existingTurn), ChatEvent.OutputReceived("same reply", null), emptySet())

        assertEquals(listOf(existingTurn), result.chatItems)
        assertNull(result.itemToPersist)
    }

    @Test
    fun `OutputReceived resolves a dangling live-flow Turn placeholder left by llm_result`() {
        // Real bug, found on-device: llm_call creates a Turn placeholder
        // (thinking=RUNNING, agent=null) under the server's frame id, and
        // llm_result deliberately leaves agent null. OUTPUT carries the reply
        // and no id to correlate it back, so the placeholder used to be left
        // behind — never given an agent, so isInProgress()'s `agent == null`
        // arm read it as running forever and the Stop-button gate locked the
        // input bar after every ordinary conversation.
        //
        // OUTPUT now folds its reply into that placeholder instead, so the
        // turn is one item and the question cannot arise.
        val danglingId = "call-abc123"
        val dangling = ChatItem.Turn(
            id = danglingId,
            thinking = ChatItem.Thinking(id = danglingId, status = ThinkingStatus.DONE),
            agent = null
        )

        val result = ChatEventReducer.reduce(listOf(dangling), ChatEvent.OutputReceived("final reply", null), emptySet())

        val turn = result.chatItems.filterIsInstance<ChatItem.Turn>().single()
        assertTrue("the turn must no longer read as in-progress", !turn.isInProgress())
        assertNotNull(turn.agent)
        assertEquals("final reply", turn.agent?.content)
        // The content hash wins, so a later session_sync replay dedupes.
        assertEquals(stableAssistantId("final reply"), turn.id)
    }

    @Test
    fun `OutputReceived filters out a previously-cleared content id`() {
        val ignoredId = stableAssistantId("cleared content")

        val result = ChatEventReducer.reduce(
            emptyList(),
            ChatEvent.OutputReceived("cleared content", null),
            setOf(ignoredId)
        )

        assertTrue(result.chatItems.isEmpty())
        assertNull(result.itemToPersist)
    }

    @Test
    fun `OutputReceived with blank result creates nothing`() {
        val result = ChatEventReducer.reduce(emptyList(), ChatEvent.OutputReceived("   ", null), emptySet())

        assertTrue(result.chatItems.isEmpty())
        assertNull(result.itemToPersist)
    }

    // ── Waiting ──────────────────────────────────────────────────────

    @Test
    fun `Waiting is a no-op`() {
        val existing = listOf(ChatItem.User(id = "u1", content = "hi"))

        val result = ChatEventReducer.reduce(existing, ChatEvent.Waiting, emptySet())

        assertEquals(existing, result.chatItems)
        assertNull(result.itemToPersist)
    }

    // ── ConnectionErrorOccurred ──────────────────────────────────────

    @Test
    fun `ConnectionErrorOccurred flips a RUNNING standalone Thinking item to ERROR with the message as content`() {
        val running = ChatItem.Thinking(id = "t1", status = ThinkingStatus.RUNNING, model = "gemini-3-flash-preview")

        val result = ChatEventReducer.reduce(
            listOf(running),
            ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"),
            emptySet()
        )

        val updated = result.chatItems.single() as ChatItem.Thinking
        assertEquals(ThinkingStatus.ERROR, updated.status)
        assertEquals("Insufficient ConnectOnion Credits", updated.content)
        assertEquals("gemini-3-flash-preview", updated.model)
        assertEquals(updated, result.itemToPersist)
    }

    @Test
    fun `ConnectionErrorOccurred flips a RUNNING Turn's thinking to ERROR with the message as content`() {
        val runningTurn = ChatItem.Turn(
            id = "turn-1",
            thinking = ChatItem.Thinking(id = "turn-1", status = ThinkingStatus.RUNNING)
        )

        val result = ChatEventReducer.reduce(
            listOf(runningTurn),
            ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"),
            emptySet()
        )

        val updated = result.chatItems.single() as ChatItem.Turn
        assertEquals(ThinkingStatus.ERROR, updated.thinking?.status)
        assertEquals("Insufficient ConnectOnion Credits", updated.thinking?.content)
        assertEquals(updated, result.itemToPersist)
    }

    @Test
    fun `ConnectionErrorOccurred attaches to a Turn whose thinking already finished but whose agent never arrived`() {
        // Regression test: llm_result sets thinking DONE but deliberately
        // leaves agent null (see ChatItemUpdated's own comment); the real
        // reply is a separate OUTPUT that never came because the connection
        // just died. isInProgress()'s OR (RUNNING thinking || agent == null)
        // means this Turn was still dangling — an earlier cut of this
        // branch only matched `thinking?.status == RUNNING`, so it missed
        // this exact shape and left the Turn reading as in-progress
        // forever (found by automated review; same bug class as the
        // OutputReceived dangling-placeholder fix, reached via this event
        // instead).
        val dangling = ChatItem.Turn(
            id = "turn-2",
            thinking = ChatItem.Thinking(id = "turn-2", status = ThinkingStatus.DONE),
            agent = null
        )

        val result = ChatEventReducer.reduce(
            listOf(dangling),
            ChatEvent.ConnectionErrorOccurred("Connection lost"),
            emptySet()
        )

        val updated = result.chatItems.single() as ChatItem.Turn
        assertEquals(ThinkingStatus.ERROR, updated.thinking?.status)
        assertNotNull("agent must be populated so isInProgress() stops reading this as running", updated.agent)
        assertEquals("Connection lost", updated.agent?.content)
        assertTrue("must no longer read as in-progress", !updated.isInProgress())
        assertEquals(updated, result.itemToPersist)
    }

    @Test
    fun `ConnectionErrorOccurred with nothing RUNNING appends a standalone failed Thinking item`() {
        val existing = ChatItem.User(id = "u1", content = "hi")

        val result = ChatEventReducer.reduce(
            listOf(existing),
            ChatEvent.ConnectionErrorOccurred("Insufficient ConnectOnion Credits"),
            emptySet()
        )

        assertEquals(2, result.chatItems.size)
        val appended = result.chatItems.last() as ChatItem.Thinking
        assertEquals(ThinkingStatus.ERROR, appended.status)
        assertEquals("Insufficient ConnectOnion Credits", appended.content)
        assertEquals(appended, result.itemToPersist)
    }

    // ── Pending gates stay pinned to the bottom ──────────────────────

    private val approval = ChatItem.ApprovalNeeded(id = "gate1", tool = "bash", arguments = emptyMap())

    @Test
    fun `new content slots above an unanswered approval card`() {
        val earlier = ChatItem.Agent(id = "a1", content = "let me check")
        val current = listOf(earlier, approval)
        val incoming = ChatItem.Agent(id = "a2", content = "meanwhile, some commentary")

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.ChatItemReceived(incoming),
            ignoredIds = emptySet(),
            resolvedGateIds = emptySet()
        )

        assertEquals(listOf(earlier, incoming, approval), result.chatItems)
        assertEquals(incoming, result.itemToPersist)
    }

    @Test
    fun `OutputReceived also slots above an unanswered approval card`() {
        val current = listOf(approval)

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.OutputReceived("the final answer", null),
            ignoredIds = emptySet(),
            resolvedGateIds = emptySet()
        )

        assertEquals(2, result.chatItems.size)
        assertTrue(result.chatItems[0] is ChatItem.Turn)
        assertSame(approval, result.chatItems[1])
    }

    @Test
    fun `answered approval card no longer holds the bottom`() {
        val current = listOf(approval)
        val incoming = ChatItem.Agent(id = "a2", content = "running it now")

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.ChatItemReceived(incoming),
            ignoredIds = emptySet(),
            resolvedGateIds = setOf("gate1")
        )

        assertEquals(listOf(approval, incoming), result.chatItems)
    }

    @Test
    fun `a second gate appends after the first instead of jumping it`() {
        val current = listOf(approval)
        val second = ChatItem.AskUser(id = "gate2", text = "which one?", options = listOf("a"), multiSelect = false)

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.ChatItemReceived(second),
            ignoredIds = emptySet(),
            resolvedGateIds = emptySet()
        )

        assertEquals(listOf(approval, second), result.chatItems)
    }

    @Test
    fun `transcript already below a gate is never reordered retroactively`() {
        // A build without pinning left content under the gate; moving it now
        // would shuffle messages the user has already read.
        val stranded = ChatItem.Agent(id = "a1", content = "arrived before this fix shipped")
        val current = listOf(approval, stranded)
        val incoming = ChatItem.Agent(id = "a2", content = "newest")

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.ChatItemReceived(incoming),
            ignoredIds = emptySet(),
            resolvedGateIds = emptySet()
        )

        assertEquals(listOf(approval, stranded, incoming), result.chatItems)
    }

    // ── SessionStatusReceived ────────────────────────────────────────

    @Test
    fun `SessionStatusReceived is a no-op on the chat list`() {
        // Not a chat-list mutation: ChatViewModel handles the revert-a-
        // false-failure decision itself, same shape as ServerTranscriptReceived.
        val current = listOf(ChatItem.User(id = "u1", content = "hi"))

        val result = ChatEventReducer.reduce(
            current,
            ChatEvent.SessionStatusReceived(sessionId = "sess-1", status = "running"),
            emptySet()
        )

        assertSame(current, result.chatItems)
        assertNull(result.itemToPersist)
    }
}
