package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.data.repository.AgentRepository
import ai.openonion.oochat.data.repository.MessageRepository
import ai.openonion.oochat.data.repository.SessionRepository
import ai.openonion.oochat.domain.model.AgentProfile
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ChatMessage
import ai.openonion.oochat.domain.model.ChatSession
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ServerTranscriptEntry
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.sessionUsageTotals
import ai.openonion.oochat.domain.model.stableAssistantId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ConversationHistoryUseCase], extracted from
 * [ai.openonion.oochat.ui.chat.ChatViewModel] (session +
 * message persistence, drawer session-list watching).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationHistoryUseCaseTest {

    private class FakeAgentRepository : AgentRepository {
        val agents = mutableListOf<AgentProfile>()
        override fun getAllAgents(): Flow<List<AgentProfile>> = throw NotImplementedError()
        override fun getActiveAgents(): Flow<List<AgentProfile>> = throw NotImplementedError()
        override suspend fun getAgentById(id: String): AgentProfile? = agents.find { it.id == id }
        override suspend fun getAgentByAddress(address: String): AgentProfile? = agents.find { it.address == address }
        override suspend fun createAgent(agent: AgentProfile): AgentProfile { agents += agent; return agent }
        override suspend fun updateAgent(agent: AgentProfile) {}
        override suspend fun deleteAgent(agentId: String) {}
        override suspend fun updateLastConnected(agentId: String) {}
        override suspend fun getDefaultAgent(): AgentProfile? = agents.firstOrNull()
        override suspend fun reorderAgents(orderedIds: List<String>) {}
    }

    private class FakeSessionRepository : SessionRepository {
        val sessions = mutableMapOf<String, ChatSession>()
        val sessionsByAgent = mutableMapOf<String, MutableStateFlow<List<ChatSession>>>()
        var createCount = 0
        var renamedTitles = mutableListOf<Pair<String, String>>()
        var updatedMessageInfo = mutableListOf<Triple<String, Int, String?>>()

        override fun getAllSessions(): Flow<List<ChatSession>> = throw NotImplementedError()

        override fun getSessionsByAgent(agentId: String): Flow<List<ChatSession>> =
            sessionsByAgent.getOrPut(agentId) { MutableStateFlow(sessions.values.filter { it.agentId == agentId }) }

        override suspend fun getSessionById(id: String): ChatSession? = sessions[id]

        override suspend fun createSession(agentId: String, title: String, id: String): ChatSession {
            createCount++
            val session = ChatSession(
                id = id,
                agentId = agentId,
                title = title,
                createdAt = 0L,
                updatedAt = 0L
            )
            sessions[session.id] = session
            sessionsByAgent.getOrPut(agentId) { MutableStateFlow(emptyList()) }.value =
                sessions.values.filter { it.agentId == agentId }
            return session
        }

        /**
         * Set to park the placeholder sweep mid-flight, which is the only
         * suspension inside ensureActiveSession a test can reach. Without it
         * the fakes never actually suspend, so the post-suspension re-check
         * has no reachable race and is untestable.
         */
        var deleteGate: CompletableDeferred<Unit>? = null

        override suspend fun deleteSession(sessionId: String) {
            deleteGate?.await()
            sessions.remove(sessionId)
        }

        override suspend fun renameSession(sessionId: String, newTitle: String) {
            renamedTitles += sessionId to newTitle
            sessions[sessionId]?.let { sessions[sessionId] = it.copy(title = newTitle) }
        }

        override suspend fun updateMessageInfo(sessionId: String, count: Int, preview: String?) {
            updatedMessageInfo += Triple(sessionId, count, preview)
            sessions[sessionId]?.let { sessions[sessionId] = it.copy(messageCount = count, lastMessagePreview = preview) }
        }
    }

    private class FakeMessageRepository : MessageRepository {
        val messages = mutableListOf<ChatMessage>()
        var deleteCallsForSession = mutableListOf<String>()

        /**
         * Paged reads, so "there is nothing left to fetch" can be asserted as
         * *not asking* rather than as an empty answer — a dropped guard returns
         * empty either way. Delegates so the interface's own slicing, which is
         * what mirrors the DAO's LIMIT/OFFSET, stays the thing under test.
         */
        var pageQueries = 0

        override suspend fun getMessagesListBySession(sessionId: String): List<ChatMessage> =
            messages.filter { it.sessionId == sessionId }

        override suspend fun getMessagesPageBySession(sessionId: String, limit: Int, offset: Int): List<ChatMessage> {
            pageQueries++
            return super.getMessagesPageBySession(sessionId, limit, offset)
        }

        override suspend fun createMessage(message: ChatMessage) { messages += message }
        override suspend fun deleteMessagesBySession(sessionId: String) {
            deleteCallsForSession += sessionId
            messages.removeAll { it.sessionId == sessionId }
        }
        override suspend fun getMessageCount(sessionId: String): Int = messages.count { it.sessionId == sessionId }
        override suspend fun existsById(id: String): Boolean = messages.any { it.id == id }
        override suspend fun getOwningSessionId(id: String): String? =
            messages.firstOrNull { it.id == id }?.sessionId
        override suspend fun getSessionIdByUserContent(content: String): String? =
            messages.lastOrNull { it.role == Role.USER && it.content == content }?.sessionId
    }

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var agentRepo: FakeAgentRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var useCase: ConversationHistoryUseCase

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        agentRepo = FakeAgentRepository()
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        val fakePersistence = FakePersistenceTransaction(messageRepo, sessionRepo)
        useCase = ConversationHistoryUseCase(agentRepo, sessionRepo, messageRepo, fakePersistence)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun testAgent(id: String = "agent-1", address: String = "0xabc") =
        AgentProfile(
            id = id, address = address, name = "Test", description = null,
            serverUrl = "https://x.com", apiKey = null, avatarUrl = null,
            createdAt = 0L, lastConnectedAt = null, isActive = true, connectionMode = "relay"
        )

    /**
     * One CONNECT-replayed transcript entry. [question] is only what the
     * entry answered in the scenario being set up — the merge keys on content
     * alone, so it is documentation for the reader, not input.
     */
    private fun entry(content: String, @Suppress("UNUSED_PARAMETER") question: String? = null) =
        ServerTranscriptEntry(content = content)

    // ── ensureActiveSession ────────────────────────────────────────

    @Test
    fun `ensureActiveSession defers session creation when the agent has none`() = runTest(testDispatcher) {
        // Lazy creation: no Room row is written just from loading the
        // screen — only once something is actually persisted into it (see
        // the "materializes on the first persisted message" test below).
        agentRepo.agents += testAgent()

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(0, sessionRepo.createCount)
        assertNull(useCase.activeSessionId.value)
    }

    @Test
    fun `ensureActiveSession's deferred session materializes on the first persisted message`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))

        assertEquals(1, sessionRepo.createCount)
        assertEquals(useCase.sessions.value.first().id, useCase.activeSessionId.value)
    }

    @Test
    fun `ensureActiveSession sweeps pre-existing empty New conversation rows but keeps real ones`() = runTest(testDispatcher) {
        // Regression coverage for clutter left by the old eager-creation
        // behavior (or any other now-closed path): a session with zero
        // messages and the untouched default title is never worth keeping.
        agentRepo.agents += testAgent()
        val stale1 = sessionRepo.createSession("agent-1", "New conversation")
        val stale2 = sessionRepo.createSession("agent-1", "New conversation")
        val real = sessionRepo.createSession("agent-1", "Existing conversation")
        sessionRepo.sessions[real.id] = sessionRepo.sessions.getValue(real.id).copy(messageCount = 1)

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertNull(sessionRepo.sessions[stale1.id])
        assertNull(sessionRepo.sessions[stale2.id])
        assertEquals(real.id, useCase.activeSessionId.value)
    }

    @Test
    fun `ensureActiveSession resumes the most recent existing session instead of creating a new one`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        val existing = sessionRepo.createSession("agent-1", "Existing conversation")

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(existing.id, useCase.activeSessionId.value)
        assertEquals(1, sessionRepo.createCount)
    }

    @Test
    fun `ensureActiveSession is a no-op for the same agent when a session is already pending`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        assertNull(useCase.activeSessionId.value)

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        // The second call must not re-derive/duplicate the pending state,
        // and definitely must not materialize a session on its own — only
        // persistMessage does that.
        assertNull(useCase.activeSessionId.value)
        assertEquals(0, sessionRepo.createCount)
    }

    @Test
    fun `ensureActiveSession does nothing for an unknown agent address`() = runTest(testDispatcher) {
        useCase.ensureActiveSession("0xdoesnotexist", scope = backgroundScope)
        advanceUntilIdle()

        assertNull(useCase.activeSessionId.value)
        assertEquals(0, sessionRepo.createCount)
    }

    // ── ensureActiveSession: resume-on-open ──────────────────────────

    @Test
    fun `ensureActiveSession returns the previous conversation's persisted rows`() = runTest(testDispatcher) {
        // The launch-time bug this covers: the session id resolved fine, but
        // nothing loaded its rows, so the screen rendered "No messages yet."
        // over a Room table that still held the whole conversation.
        agentRepo.agents += testAgent()
        val previous = sessionRepo.createSession("agent-1", "Yesterday's chat")
        messageRepo.createMessage(ChatMessage(id = "m1", sessionId = previous.id, role = Role.USER, content = "hi", timestamp = 100L))
        messageRepo.createMessage(ChatMessage(id = "m2", sessionId = previous.id, role = Role.ASSISTANT, content = "hello", timestamp = 200L))

        val resume = useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertTrue(resume is SessionResume.Resumed)
        resume as SessionResume.Resumed
        assertEquals(previous.id, resume.sessionId)
        assertEquals(listOf("hi", "hello"), resume.items.map {
            when (it) {
                is ChatItem.User -> it.content
                is ChatItem.Turn -> it.agent?.content.orEmpty()
                else -> ""
            }
        })
        assertEquals(previous.id, useCase.activeSessionId.value)
        assertEquals(mapOf("m1" to 100L, "m2" to 200L), useCase.itemTimestamps.value)
    }

    @Test
    fun `switching agents resumes the new agent's conversation, not the previous one's`() = runTest(testDispatcher) {
        // The post-suspension re-check used to back off here, so the caller was
        // told nothing had changed and left the first agent's chat on screen —
        // and, because that session stayed active, persisted the next message
        // into the wrong agent's conversation.
        agentRepo.agents += testAgent(id = "agent-1", address = "0xabc")
        agentRepo.agents += testAgent(id = "agent-2", address = "0xdef")
        val first = sessionRepo.createSession("agent-1", "Agent one's chat")
        messageRepo.createMessage(ChatMessage(id = "m1", sessionId = first.id, role = Role.USER, content = "one", timestamp = 100L))
        val second = sessionRepo.createSession("agent-2", "Agent two's chat")
        messageRepo.createMessage(ChatMessage(id = "m2", sessionId = second.id, role = Role.USER, content = "two", timestamp = 100L))

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val switched = useCase.ensureActiveSession("0xdef", scope = backgroundScope)
        advanceUntilIdle()

        assertTrue("switching agents must resolve the new agent, got $switched", switched is SessionResume.Resumed)
        switched as SessionResume.Resumed
        assertEquals(second.id, switched.sessionId)
        assertEquals(listOf("two"), switched.items.filterIsInstance<ChatItem.User>().map { it.content })
        assertEquals(second.id, useCase.activeSessionId.value)
    }

    @Test
    fun `a conversation started while the sweep is suspended is not overwritten`() = runTest(testDispatcher) {
        // The race the re-check exists for, and the reason the fix compares
        // against an entry snapshot rather than dropping the guard: nothing
        // else in this suite reaches it, because the fakes otherwise never
        // suspend where production's Room flow does.
        agentRepo.agents += testAgent()
        sessionRepo.createSession("agent-1", "New conversation")
        val gate = CompletableDeferred<Unit>()
        sessionRepo.deleteGate = gate

        var resume: SessionResume? = null
        backgroundScope.launch { resume = useCase.ensureActiveSession("0xabc", scope = backgroundScope) }
        runCurrent()

        // Parked inside the sweep; startNewSession now resolves this same agent.
        useCase.startNewSession()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("a racing startNewSession must win, got $resume", resume is SessionResume.Unchanged)
    }

    @Test
    fun `a second call for the same agent still backs off once it is resolved`() = runTest(testDispatcher) {
        // The guard the fix must not break: re-entering for the agent already
        // resolved is a genuine no-op, and is what stops a recomposition or a
        // reconnect from rebuilding the conversation under the user.
        agentRepo.agents += testAgent()
        sessionRepo.createSession("agent-1", "Same agent")

        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val again = useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertTrue("re-entering for the resolved agent should be a no-op, got $again", again is SessionResume.Unchanged)
    }

    @Test
    fun `ensureActiveSession starts fresh when the previous conversation no longer exists`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        val previous = sessionRepo.createSession("agent-1", "Deleted chat")
        useCase.deleteSession(previous.id)
        sessionRepo.sessionsByAgent.getValue("agent-1").value = emptyList()

        val resume = useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(SessionResume.Started, resume)
        assertNull(useCase.activeSessionId.value)
    }

    @Test
    fun `forceNew still starts fresh even when a previous conversation exists`() = runTest(testDispatcher) {
        // The explicit "New conversation" action must keep overriding the
        // resume-by-default behaviour.
        agentRepo.agents += testAgent()
        val previous = sessionRepo.createSession("agent-1", "Yesterday's chat")
        messageRepo.createMessage(ChatMessage(id = "m1", sessionId = previous.id, role = Role.USER, content = "hi", timestamp = 100L))

        val resume = useCase.ensureActiveSession("0xabc", scope = backgroundScope, forceNew = true)
        advanceUntilIdle()

        assertEquals(SessionResume.Started, resume)
        assertNull(useCase.activeSessionId.value)
        assertTrue(useCase.itemTimestamps.value.isEmpty())
    }

    @Test
    fun `a second ensureActiveSession for the same agent reports Unchanged, not a reload`() = runTest(testDispatcher) {
        // A reconnect blip must not re-push the same rows at the caller,
        // which would displace whatever arrived live in the meantime.
        agentRepo.agents += testAgent()
        val previous = sessionRepo.createSession("agent-1", "Yesterday's chat")
        messageRepo.createMessage(ChatMessage(id = "m1", sessionId = previous.id, role = Role.USER, content = "hi", timestamp = 100L))
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val second = useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(SessionResume.Unchanged, second)
    }

    @Test
    fun `ensureActiveSession reports Unchanged for an unknown agent instead of clearing the screen`() = runTest(testDispatcher) {
        val resume = useCase.ensureActiveSession("0xdoesnotexist", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(SessionResume.Unchanged, resume)
    }

    // ── mergeServerTranscript ────────────────────────────────────────

    @Test
    fun `mergeServerTranscript takes nothing when the local copy already holds the transcript`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        val reply = ChatItem.Agent(id = stableAssistantId("hello"), content = "hello")
        useCase.persistMessage(reply)
        val before = messageRepo.messages.size

        val fetched = useCase.mergeServerTranscript(listOf(entry("hello")), serverTurn = 3)

        assertTrue(fetched.isEmpty())
        assertEquals("no churn when the local copy is current", before, messageRepo.messages.size)
    }

    @Test
    fun `mergeServerTranscript persists and returns only the entries the local copy lacks`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.Agent(id = stableAssistantId("hello"), content = "hello"))
        useCase.persistMessage(ChatItem.User(id = "u-again", content = "one more please"))
        val sessionId = useCase.activeSessionId.value!!

        val fetched = useCase.mergeServerTranscript(
            listOf(entry("hello"), entry("and one more", question = "one more please")),
            serverTurn = 4
        )

        assertEquals(1, fetched.size)
        assertEquals("and one more", (fetched.single() as ChatItem.Turn).agent?.content)
        assertEquals(
            listOf("hello", "one more please", "and one more"),
            messageRepo.messages.filter { it.sessionId == sessionId }.map { it.content }
        )
    }

    @Test
    fun `mergeServerTranscript leaves a reply that belongs to another conversation where it is`() = runTest(testDispatcher) {
        // Messages are keyed by content hash and inserted with REPLACE, so
        // re-persisting one here would MOVE it out of the session that owns
        // it. Two conversations can still say the same thing, so an identical
        // reply already filed elsewhere must stay where it is.
        agentRepo.agents += testAgent()
        val older = sessionRepo.createSession("agent-1", "Older chat")
        messageRepo.createMessage(
            ChatMessage(id = stableAssistantId("old reply"), sessionId = older.id, role = Role.ASSISTANT, content = "old reply", timestamp = 1L)
        )
        useCase.ensureActiveSession("0xabc", scope = backgroundScope, forceNew = true)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "new thread"))
        val currentId = useCase.activeSessionId.value!!

        val fetched = useCase.mergeServerTranscript(
            listOf(entry("old reply"), entry("brand new reply", question = "new thread")),
            serverTurn = 9
        )

        assertEquals(1, fetched.size)
        assertEquals("brand new reply", (fetched.single() as ChatItem.Turn).agent?.content)
        assertEquals(
            "the older conversation keeps its own reply",
            older.id,
            messageRepo.messages.single { it.content == "old reply" }.sessionId
        )
        assertTrue(messageRepo.messages.any { it.sessionId == currentId && it.content == "brand new reply" })
    }

    @Test
    fun `mergeServerTranscript matches a local reply stored under a non content-derived id`() = runTest(testDispatcher) {
        // Not every persisted reply carries a stableAssistantId — OUTPUT-,
        // image- and pre-hash rows don't — and re-fetching those would
        // duplicate the bubble on every launch.
        agentRepo.agents += testAgent()
        val previous = sessionRepo.createSession("agent-1", "Yesterday's chat")
        messageRepo.createMessage(
            ChatMessage(id = "legacy-row-id", sessionId = previous.id, role = Role.ASSISTANT, content = "hello", timestamp = 1L)
        )
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val fetched = useCase.mergeServerTranscript(listOf(entry("hello")), serverTurn = 2)

        assertTrue(fetched.isEmpty())
        assertEquals(1, messageRepo.messages.size)
    }

    @Test
    fun `mergeServerTranscript never materializes a session just to hold server history`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        assertNull(useCase.activeSessionId.value)

        val fetched = useCase.mergeServerTranscript(listOf(entry("server-only reply")), serverTurn = 1)

        assertTrue(fetched.isEmpty())
        assertEquals(0, sessionRepo.createCount)
        assertTrue(messageRepo.messages.isEmpty())
    }


    @Test
    fun `a server-side compaction that drops history still merges the surviving tail`() = runTest(testDispatcher) {
        // The agent's auto_compact plugin collapses its context to
        // "system + summary + last 5" once usage passes 90%, so the replayed
        // transcript can be far SHORTER than what this conversation already
        // holds. The old positional scheme read that as "every stored offset
        // now points past the end of the stream" and dropped the lot; a
        // per-conversation session has no offset to invalidate, so the only
        // question left is freshness.
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "the question that survived compaction"))
        useCase.persistMessage(ChatItem.Agent(id = stableAssistantId("an early reply"), content = "an early reply"))
        val sessionId = useCase.activeSessionId.value!!

        // Post-compaction the server replays a summary plus a short tail —
        // nothing like the full history it held before.
        val fetched = useCase.mergeServerTranscript(
            listOf(
                entry("Context compacted: earlier turns summarised."),
                entry("the reply after compaction", question = "the question that survived compaction")
            ),
            serverTurn = 12
        )

        assertEquals(
            "a compacted transcript must still deliver what the local copy lacks",
            listOf("Context compacted: earlier turns summarised.", "the reply after compaction"),
            fetched.map { (it as ChatItem.Turn).agent?.content }
        )
        assertTrue(
            "the pre-compaction reply must not be dropped from the local record",
            messageRepo.messages.any { it.sessionId == sessionId && it.content == "an early reply" }
        )
    }

    @Test
    fun `a conversation's id is settled before its row exists, and the row uses it`() = runTest(testDispatcher) {
        // The id is what the connection talks on, so it cannot wait for the
        // first message: minting it up front is what lets a brand-new
        // conversation CONNECT on a session of its own.
        val fresh = ConversationHistoryUseCase(
            agentRepo, sessionRepo, messageRepo,
            FakePersistenceTransaction(messageRepo, sessionRepo),
            newConversationId = { "minted-id" }
        )
        agentRepo.agents += testAgent()
        fresh.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        assertEquals("the id must exist before any row does", "minted-id", fresh.conversationId)
        assertNull("but the conversation must not be persisted yet", fresh.activeSessionId.value)

        fresh.persistMessage(ChatItem.User(id = "u1", content = "first message"))

        assertEquals("the row must be created under the minted id", "minted-id", fresh.activeSessionId.value)
        assertEquals("minted-id", sessionRepo.sessions.values.single().id)
    }

    @Test
    fun `each new conversation mints a distinct id`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        val first = useCase.conversationId

        useCase.startNewSession()
        val second = useCase.conversationId

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals("two conversations must never share a server session", first, second)
    }

    // ── startNewSession ──────────────────────────────────────────────

    @Test
    fun `startNewSession returns false when no agent session has ever been established`() = runTest(testDispatcher) {
        val started = useCase.startNewSession()

        assertFalse(started)
        assertEquals(0, sessionRepo.createCount)
    }

    @Test
    fun `startNewSession defers creating the next session until something is persisted into it`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))
        val firstSessionId = useCase.activeSessionId.value
        assertEquals(1, sessionRepo.createCount)
        assertTrue(useCase.itemTimestamps.value.isNotEmpty())

        val started = useCase.startNewSession()

        assertTrue(started)
        assertNull("must not create a Room row until something is actually persisted", useCase.activeSessionId.value)
        assertEquals(1, sessionRepo.createCount)
        assertTrue(useCase.itemTimestamps.value.isEmpty())

        // Sending nothing would leave no trace at all; sending something
        // materializes a genuinely new, second session.
        useCase.persistMessage(ChatItem.User(id = "u2", content = "second conversation"))
        assertEquals(2, sessionRepo.createCount)
        assertNotEquals(firstSessionId, useCase.activeSessionId.value)
    }

    // ── switchToSession ──────────────────────────────────────────────

    @Test
    fun `switchToSession maps persisted messages back to ChatItems and sets active session`() = runTest(testDispatcher) {
        val session = sessionRepo.createSession("agent-1", "Old conversation")
        messageRepo.createMessage(ChatMessage(id = "m1", sessionId = session.id, role = Role.USER, content = "hi", timestamp = 100L, images = listOf("file:///a.jpg")))
        messageRepo.createMessage(ChatMessage(id = "m2", sessionId = session.id, role = Role.ASSISTANT, content = "hello", timestamp = 200L))

        val items = useCase.switchToSession(session.id)

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.User)
        assertTrue(items[1] is ChatItem.Turn)
        assertEquals(listOf("file:///a.jpg"), (items[0] as ChatItem.User).images)
        assertEquals(session.id, useCase.activeSessionId.value)
        assertEquals(mapOf("m1" to 100L, "m2" to 200L), useCase.itemTimestamps.value)
    }

    @Test
    fun `deleting a session activated via switchToSession with no prior agent context degrades to a safe no-op, not a crash`() = runTest(testDispatcher) {
        // Edge case surfaced by the SessionResolution refactor: switchToSession
        // (unlike ensureActiveSession) never sets currentSessionAgentId, so
        // deleteSession's re-pend has no agent to attribute the next session
        // to. materializePendingSession must degrade to "nothing to persist
        // into" here rather than crash or silently misattribute — the exact
        // behavior this class had before the refactor too (currentSessionAgentId
        // was equally null in this scenario pre-refactor).
        val session = sessionRepo.createSession("agent-1", "Old conversation")
        useCase.switchToSession(session.id)
        assertEquals(session.id, useCase.activeSessionId.value)

        val wasActive = useCase.deleteSession(session.id)
        assertTrue(wasActive)
        assertNull(useCase.activeSessionId.value)

        // No agent context to materialize a replacement session under —
        // persisting now must not throw, and must not silently create a
        // session for the wrong (or no) agent.
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))
        assertNull("nothing to persist into without an agent context", useCase.activeSessionId.value)
    }

    // ── deleteSession ────────────────────────────────────────────────

    @Test
    fun `deleteSession removes the row and returns false when it wasn't active`() = runTest(testDispatcher) {
        val session = sessionRepo.createSession("agent-1", "Old conversation")

        val wasActive = useCase.deleteSession(session.id)

        assertFalse(wasActive)
        assertNull(sessionRepo.sessions[session.id])
    }

    @Test
    fun `deleteSession resets active-session tracking and returns true when it was active`() = runTest(testDispatcher) {
        val session = sessionRepo.createSession("agent-1", "Old conversation")
        useCase.switchToSession(session.id)
        assertEquals(session.id, useCase.activeSessionId.value)

        val wasActive = useCase.deleteSession(session.id)

        assertTrue(wasActive)
        assertNull(sessionRepo.sessions[session.id])
        assertNull(useCase.activeSessionId.value)
        assertTrue(useCase.itemTimestamps.value.isEmpty())
    }

    @Test
    fun `a message sent right after deleting the active session is still persisted, into a fresh session`() = runTest(testDispatcher) {
        // Regression test: an earlier cut of deleteSession cleared
        // activeSessionId without setting pendingNewSessionAgentId, so
        // resolveActiveSessionId()/persistMessage() both fell through to
        // null — the deleted conversation's replacement never happened, and
        // anything sent right after deleting the open session silently
        // never made it into Room (found by automated review).
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))
        val deletedId = useCase.activeSessionId.value!!

        val wasActive = useCase.deleteSession(deletedId)
        assertTrue(wasActive)

        useCase.persistMessage(ChatItem.User(id = "u2", content = "still here"))

        val newId = useCase.activeSessionId.value
        assertNotNull("a fresh session must have materialized", newId)
        assertNotEquals(deletedId, newId)
        assertNull(sessionRepo.sessions[deletedId])
    }

    @Test
    fun `deleteSession works for a session belonging to a different, non-active agent`() = runTest(testDispatcher) {
        // The drawer lists every agent's sessions (observeAllAgentSessions),
        // not just the currently connected agent's — deleting one of those
        // must not require this use case to already be "on" that agent.
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))
        val activeId = useCase.activeSessionId.value!!

        val otherAgentSession = sessionRepo.createSession("some-other-agent", "Unrelated conversation")
        val wasActive = useCase.deleteSession(otherAgentSession.id)

        assertFalse(wasActive)
        assertNull(sessionRepo.sessions[otherAgentSession.id])
        assertEquals("still the real active session, untouched", activeId, useCase.activeSessionId.value)
    }

    // ── renameSession ────────────────────────────────────────────────

    @Test
    fun `renameSession delegates to the repository and does not touch the active session`() = runTest(testDispatcher) {
        val session = sessionRepo.createSession("agent-1", "Old title")
        useCase.switchToSession(session.id)

        useCase.renameSession(session.id, "New title")

        assertEquals("New title", sessionRepo.sessions[session.id]?.title)
        assertEquals("renaming must not move which session is active", session.id, useCase.activeSessionId.value)
    }

    // ── persistMessage ───────────────────────────────────────────────

    @Test
    fun `persistMessage no-ops when there is no active session`() = runTest(testDispatcher) {
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))

        assertTrue(messageRepo.messages.isEmpty())
    }

    @Test
    fun `persistMessage writes a User item as a USER-role message`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.User(id = "u1", content = "hello"))

        val saved = messageRepo.messages.single()
        assertEquals(Role.USER, saved.role)
        assertEquals("hello", saved.content)
    }

    @Test
    fun `persistMessage saves an image-only User item (blank content) and its images`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.User(id = "u1", content = "", images = listOf("file:///a.jpg")))

        val saved = messageRepo.messages.single()
        assertEquals(Role.USER, saved.role)
        assertEquals(listOf("file:///a.jpg"), saved.images)
    }

    @Test
    fun `persistMessage carries Agent images through to the saved ChatMessage`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.Agent(id = "a1", content = "", images = listOf("https://example.com/img.png")))

        val saved = messageRepo.messages.single()
        assertEquals(Role.ASSISTANT, saved.role)
        assertEquals(listOf("https://example.com/img.png"), saved.images)
    }

    @Test
    fun `persistMessage renames a still-default-titled session from the first user message`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.User(id = "u1", content = "What's the weather like today?"))

        assertEquals(1, sessionRepo.renamedTitles.size)
        assertEquals("What's the weather like today?", sessionRepo.renamedTitles.first().second)
    }

    @Test
    fun `persistMessage extracts agent content from a Turn item`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val turn = ChatItem.Turn(id = "t1", agent = ChatItem.Agent(id = "t1", content = "the answer"))
        useCase.persistMessage(turn)

        val saved = messageRepo.messages.single()
        assertEquals(Role.ASSISTANT, saved.role)
        assertEquals("the answer", saved.content)
    }

    @Test
    fun `a reloaded turn renders with the same footer it had live`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        val thinking = ChatItem.Thinking(
            id = "t1",
            status = ThinkingStatus.DONE,
            model = "gemini-2.5-flash",
            durationMs = 2100.0,
            tokensTotal = 46,
            costUsd = 0.0071,
            contextPercent = 12.5
        )
        useCase.persistMessage(
            ChatItem.Turn(id = "t1", thinking = thinking, agent = ChatItem.Agent(id = "t1", content = "the answer"))
        )
        val sessionId = useCase.activeSessionId.value!!

        val reloaded = useCase.switchToSession(sessionId).single() as ChatItem.Turn
        assertEquals("the answer", reloaded.agent?.content)
        assertEquals(thinking, reloaded.thinking)
    }

    @Test
    fun `a reloaded conversation still adds up to its session usage totals`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(
            ChatItem.Turn(
                id = "t1",
                thinking = ChatItem.Thinking(
                    id = "t1",
                    status = ThinkingStatus.DONE,
                    tokensTotal = 46,
                    costUsd = 0.0071,
                    contextPercent = 12.5
                ),
                agent = ChatItem.Agent(id = "t1", content = "the answer")
            )
        )
        val sessionId = useCase.activeSessionId.value!!

        // sessionUsageTotals walks Turn items, so a reload that minted Agent
        // emptied the status bar out — see ChatItem.sessionUsageTotals.
        val totals = useCase.switchToSession(sessionId).sessionUsageTotals()
        assertEquals(46, totals.totalTokens)
        assertEquals(0.0071, totals.totalCostUsd, 1e-9)
        assertEquals(12.5, totals.latestContextPercent!!, 1e-9)
    }

    @Test
    fun `an assistant row written before turn metadata existed reloads without a footer`() = runTest(testDispatcher) {
        val session = sessionRepo.createSession("agent-1", "Older than this change")
        messageRepo.createMessage(
            ChatMessage(id = "m1", sessionId = session.id, role = Role.ASSISTANT, content = "hello", timestamp = 100L)
        )

        val reloaded = useCase.switchToSession(session.id).single() as ChatItem.Turn
        assertEquals("hello", reloaded.agent?.content)
        assertNull("nothing was recorded, so nothing is claimed", reloaded.thinking)
    }

    @Test
    fun `persistMessage skips a Turn with no agent content yet`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ChatItem.Turn(id = "t1", agent = null))

        assertTrue(messageRepo.messages.isEmpty())
    }

    @Test
    fun `persistMessage skips a Thinking item entirely`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()

        useCase.persistMessage(ai.openonion.oochat.domain.model.ChatItem.Thinking(
            id = "th1",
            status = ai.openonion.oochat.domain.model.ThinkingStatus.RUNNING
        ))

        assertTrue(messageRepo.messages.isEmpty())
    }

    // ── clearActiveSession ───────────────────────────────────────────

    @Test
    fun `clearActiveSession deletes messages and resets message info for the active session`() = runTest(testDispatcher) {
        agentRepo.agents += testAgent()
        useCase.ensureActiveSession("0xabc", scope = backgroundScope)
        advanceUntilIdle()
        useCase.persistMessage(ChatItem.User(id = "u1", content = "hi"))
        val activeId = useCase.activeSessionId.value!!

        useCase.clearActiveSession()

        assertEquals(listOf(activeId), messageRepo.deleteCallsForSession)
        assertTrue(messageRepo.messages.none { it.sessionId == activeId })
        assertEquals(Triple(activeId, 0, null), sessionRepo.updatedMessageInfo.last())
    }

    @Test
    fun `clearActiveSession is a no-op when there is no active session`() = runTest(testDispatcher) {
        useCase.clearActiveSession()

        assertTrue(messageRepo.deleteCallsForSession.isEmpty())
    }

    // ── conversation paging ──────────────────────────────────────────
    //
    // The window is bookkeeping with no failure mode of its own: get the
    // offset wrong and rows are silently skipped or served twice, and nothing
    // throws. So these assert on row ids rather than counts — a count is
    // satisfied by the wrong page.

    /** Row ids encode their position, so a page's contents name the window it came from. */
    private fun rowId(row: Int, prefix: String = "row") = "$prefix-%04d".format(row)

    private fun rowIds(rows: IntRange, prefix: String = "row") = rows.map { rowId(it, prefix) }

    private suspend fun seedConversation(sessionId: String, count: Int, prefix: String = "row") {
        repeat(count) { row ->
            messageRepo.createMessage(
                ChatMessage(
                    id = rowId(row, prefix),
                    sessionId = sessionId,
                    role = if (row % 2 == 0) Role.USER else Role.ASSISTANT,
                    content = "row $row",
                    timestamp = 1_000L + row
                )
            )
        }
    }

    /**
     * A persisted conversation of [rows] messages, resumed through the same
     * public path the chat screen uses — loadSessionItems, which arms the
     * window, is private and only reachable this way.
     */
    private suspend fun seedAndResume(
        scope: CoroutineScope,
        rows: Int,
        prefix: String = "row"
    ): SessionResume.Resumed {
        agentRepo.agents += testAgent()
        val session = sessionRepo.createSession("agent-1", "Saved chat")
        seedConversation(session.id, rows, prefix)
        return useCase.ensureActiveSession("0xabc", scope = scope) as SessionResume.Resumed
    }

    @Test
    fun `a conversation shorter than one page loads whole and reports nothing older`() = runTest(testDispatcher) {
        val resume = seedAndResume(backgroundScope, rows = 5)
        advanceUntilIdle()

        assertEquals(rowIds(0..4), resume.items.map { it.id })
        assertFalse(useCase.hasOlderMessages.value)
        assertEquals(emptyList<ChatItem>(), useCase.loadOlderItems())
    }

    @Test
    fun `a conversation of exactly one page loads whole and reports nothing older`() = runTest(testDispatcher) {
        val resume = seedAndResume(backgroundScope, rows = CONVERSATION_PAGE_SIZE)
        advanceUntilIdle()

        assertEquals(rowIds(0 until CONVERSATION_PAGE_SIZE), resume.items.map { it.id })
        assertFalse("the first row is on screen, so nothing is above it", useCase.hasOlderMessages.value)
    }

    @Test
    fun `a conversation longer than one page loads its newest rows, not its oldest`() = runTest(testDispatcher) {
        val total = CONVERSATION_PAGE_SIZE * 2 + 50
        val resume = seedAndResume(backgroundScope, rows = total)
        advanceUntilIdle()

        assertEquals(rowIds(total - CONVERSATION_PAGE_SIZE until total), resume.items.map { it.id })
        assertTrue(useCase.hasOlderMessages.value)
    }

    @Test
    fun `one row past a full page leaves exactly that row to page back in`() = runTest(testDispatcher) {
        // The tightest the off-by-one gets: the window has to start at row 1,
        // and the single row below it has to still be reachable.
        val resume = seedAndResume(backgroundScope, rows = CONVERSATION_PAGE_SIZE + 1)
        advanceUntilIdle()

        assertEquals(rowIds(1..CONVERSATION_PAGE_SIZE), resume.items.map { it.id })
        assertTrue(useCase.hasOlderMessages.value)

        assertEquals(listOf(rowId(0)), useCase.loadOlderItems().map { it.id })
        assertFalse(useCase.hasOlderMessages.value)
    }

    @Test
    fun `paging back returns the rows directly below the window, with no gap and no overlap`() = runTest(testDispatcher) {
        val total = CONVERSATION_PAGE_SIZE * 2 + 50
        val resume = seedAndResume(backgroundScope, rows = total)
        advanceUntilIdle()
        val window = resume.items.map { it.id }

        val older = useCase.loadOlderItems().map { it.id }

        assertEquals(
            rowIds(total - CONVERSATION_PAGE_SIZE * 2 until total - CONVERSATION_PAGE_SIZE),
            older
        )
        val union = older + window
        assertEquals("no row is served twice", union.size, union.distinct().size)
        assertEquals("no row is skipped", rowIds(total - CONVERSATION_PAGE_SIZE * 2 until total), union)
    }

    @Test
    fun `paging back repeatedly walks to the first row and then reports nothing older`() = runTest(testDispatcher) {
        val total = CONVERSATION_PAGE_SIZE * 2 + 50
        val resume = seedAndResume(backgroundScope, rows = total)
        advanceUntilIdle()
        val seen = resume.items.map { it.id }.toMutableList()

        var pages = 0
        while (useCase.hasOlderMessages.value) {
            val older = useCase.loadOlderItems().map { it.id }
            assertTrue("a flagged older page came back empty", older.isNotEmpty())
            seen.addAll(0, older)
            assertTrue("paging is not converging on the first row", ++pages <= 10)
        }

        // Equality against the whole conversation in order is the strongest
        // form of "no gap, no overlap" available: it fails on a skipped row, a
        // repeated one, and a mis-ordered page alike.
        assertEquals(rowIds(0 until total), seen)
        assertEquals(emptyList<ChatItem>(), useCase.loadOlderItems())
    }

    @Test
    fun `asking for older rows with the whole conversation loaded never touches the repository`() = runTest(testDispatcher) {
        seedAndResume(backgroundScope, rows = 5)
        advanceUntilIdle()
        val queriesAfterLoad = messageRepo.pageQueries

        assertEquals(emptyList<ChatItem>(), useCase.loadOlderItems())

        assertFalse(useCase.hasOlderMessages.value)
        assertEquals("nothing is left to fetch, so nothing is fetched", queriesAfterLoad, messageRepo.pageQueries)
    }

    @Test
    fun `rows dropped off the front of the list stay reachable by paging back`() = runTest(testDispatcher) {
        val total = CONVERSATION_PAGE_SIZE + 100
        val resume = seedAndResume(backgroundScope, rows = total)
        advanceUntilIdle()
        assertEquals(rowIds(100 until total), resume.items.map { it.id })

        // The screen trimmed its own list to stay under its cap. Unless the
        // window follows, those rows sit above the offset and the next page
        // fetches from below them — gone until the conversation is reopened.
        useCase.noteHeadDropped(50)
        val older = useCase.loadOlderItems().map { it.id }

        assertEquals(rowIds(0 until 150), older)
        assertTrue("the dropped rows came back", older.containsAll(rowIds(100 until 150)))
    }

    @Test
    fun `a head drop before any conversation is loaded is ignored`() = runTest(testDispatcher) {
        useCase.noteHeadDropped(10)

        assertFalse("there is no window to move", useCase.hasOlderMessages.value)
        assertEquals(emptyList<ChatItem>(), useCase.loadOlderItems())
    }

    @Test
    fun `an older page keeps the loaded window's timestamps instead of replacing them`() = runTest(testDispatcher) {
        val total = CONVERSATION_PAGE_SIZE + 10
        val resume = seedAndResume(backgroundScope, rows = total)
        advanceUntilIdle()
        val window = resume.items.map { it.id }

        val older = useCase.loadOlderItems().map { it.id }

        // Both pages are on screen together, so both still need their times.
        val timestamps = useCase.itemTimestamps.value
        assertTrue("the older page brought its own timestamps", timestamps.keys.containsAll(older))
        assertTrue("the window on screen kept its timestamps", timestamps.keys.containsAll(window))
        assertEquals(1_000L, timestamps[rowId(0)])
    }

    @Test
    fun `switching to a shorter conversation re-arms the window instead of inheriting the old offset`() = runTest(testDispatcher) {
        // Resume-then-switch is the drawer's own path: ensureActiveSession
        // picks the conversation on connect, switchToSession moves off it.
        seedAndResume(backgroundScope, rows = CONVERSATION_PAGE_SIZE * 2, prefix = "old")
        advanceUntilIdle()
        assertTrue(useCase.hasOlderMessages.value)
        val short = sessionRepo.createSession("agent-1", "Short chat")
        seedConversation(short.id, 3, prefix = "new")

        val items = useCase.switchToSession(short.id)

        assertEquals(rowIds(0..2, prefix = "new"), items.map { it.id })
        assertFalse("the short conversation is loaded whole", useCase.hasOlderMessages.value)
        assertEquals(
            "the previous conversation's offset did not come along",
            emptyList<ChatItem>(),
            useCase.loadOlderItems()
        )
    }

    @Test
    fun `switching to a longer conversation flags the rows it left behind`() = runTest(testDispatcher) {
        // The other direction: a window that stayed on the short conversation's
        // "nothing older" would strand everything above the new one's tail.
        seedAndResume(backgroundScope, rows = 3, prefix = "new")
        advanceUntilIdle()
        assertFalse(useCase.hasOlderMessages.value)
        val long = sessionRepo.createSession("agent-1", "Long chat")
        val total = CONVERSATION_PAGE_SIZE + 25
        seedConversation(long.id, total, prefix = "old")

        val items = useCase.switchToSession(long.id)

        assertEquals(rowIds(total - CONVERSATION_PAGE_SIZE until total, prefix = "old"), items.map { it.id })
        assertTrue(useCase.hasOlderMessages.value)
        assertEquals(rowIds(0 until 25, prefix = "old"), useCase.loadOlderItems().map { it.id })
    }

    @Test
    fun `starting a fresh conversation forgets the previous one's older pages`() = runTest(testDispatcher) {
        seedAndResume(backgroundScope, rows = CONVERSATION_PAGE_SIZE * 2)
        advanceUntilIdle()
        assertTrue(useCase.hasOlderMessages.value)

        assertTrue(useCase.startNewSession())

        assertFalse("a conversation with no rows has none above it either", useCase.hasOlderMessages.value)
        assertEquals(emptyList<ChatItem>(), useCase.loadOlderItems())
    }
}

/**
 * Mirrors the real [PersistenceTransaction.persistMessageAtomically] routing
 * logic (item shape -> role/content/images, default-title rename trigger)
 * but against the fake repositories this test file already asserts against,
 * since the real implementation writes through raw Room DAOs that bypass
 * [MessageRepository]/[SessionRepository] entirely.
 */
class FakePersistenceTransaction(
    private val messageRepo: MessageRepository,
    private val sessionRepo: SessionRepository
) : PersistenceTransaction(FakeDatabaseForTest()) {
    override suspend fun persistMessageAtomically(
        sessionId: String,
        item: ChatItem,
        onTitleUpdate: suspend (String, String) -> Unit,
        onPreviewUpdate: suspend (String, Int, String?) -> Unit
    ) {
        val (role, content, images) = when (item) {
            is ChatItem.User -> Triple(Role.USER, item.content, item.images)
            is ChatItem.Agent -> Triple(Role.ASSISTANT, item.content, item.images)
            is ChatItem.Turn -> {
                val agent = item.agent ?: return
                Triple(Role.ASSISTANT, agent.content, agent.images)
            }
            else -> return
        }
        if (content.isBlank() && images.isNullOrEmpty()) return

        val now = 0L
        val turnThinking = (item as? ChatItem.Turn)?.thinking
            ?.let { TranscriptItemCodec.encodeTurnThinking(it) }
        messageRepo.createMessage(
            ChatMessage(
                id = item.id,
                sessionId = sessionId,
                role = role,
                content = content,
                timestamp = now,
                images = images,
                itemType = turnThinking?.let { TranscriptItemCodec.TURN_ITEM_TYPE },
                payload = turnThinking
            )
        )

        val session = sessionRepo.getSessionById(sessionId)
        if (session != null && role == Role.USER && session.title == "New conversation") {
            val newTitle = content.trim().take(40).ifBlank { "New conversation" }
            sessionRepo.renameSession(sessionId, newTitle)
            onTitleUpdate(sessionId, newTitle)
        }

        val count = messageRepo.getMessageCount(sessionId)
        val preview = content.take(80)
        sessionRepo.updateMessageInfo(sessionId, count, preview)
        onPreviewUpdate(sessionId, count, preview)
    }
}

class FakeDatabaseForTest : ai.openonion.oochat.data.local.db.AppDatabase() {
    override fun agentDao() = throw NotImplementedError()
    // persistMessageAtomically is overridden to a no-op in FakePersistenceTransaction,
    // but PersistenceTransaction's own constructor eagerly calls messageDao()/sessionDao()
    // to populate its private vals, so these must return an object rather than throw.
    override fun sessionDao(): ai.openonion.oochat.data.local.db.dao.SessionDao = NoOpSessionDao
    override fun messageDao(): ai.openonion.oochat.data.local.db.dao.MessageDao = NoOpMessageDao
    override fun sessionStateDao() = throw NotImplementedError()
    override fun pendingMessageDao() = throw NotImplementedError()
    override fun clearAllTables() = throw NotImplementedError()
    // RoomDatabase's own constructor eagerly assigns `invalidationTracker = createInvalidationTracker()`
    // (see androidx.room.RoomDatabase), so this can't throw — unlike createOpenHelper, which is only
    // invoked from init(configuration), never reached when a fake is built via a bare constructor call.
    override fun createInvalidationTracker(): androidx.room.InvalidationTracker = androidx.room.InvalidationTracker(this)
    override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = throw NotImplementedError()
}
