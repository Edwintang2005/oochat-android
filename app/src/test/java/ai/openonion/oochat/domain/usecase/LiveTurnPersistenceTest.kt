package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.mapper.MessageMapper
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.model.ChatEventReducer
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.Role
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.isInProgress
import ai.openonion.oochat.domain.model.sessionUsageTotals
import ai.openonion.oochat.domain.model.stableAssistantId
import ai.openonion.oochat.network.ConnectionEvent
import ai.openonion.oochat.network.ProtocolParser
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real frame sequence — `llm_call` → `llm_result` → `assistant` →
 * `OUTPUT` — through [ProtocolParser] and [ChatEventReducer], persists what
 * the reducer says to persist, and reloads.
 *
 * Exists because the hand-built `Turn(thinking, agent)` the other persistence
 * tests use is a shape this pipeline never produces for an ordinary reply:
 * the metadata lands on a Turn under the server's frame id and the text on a
 * different Turn under a content-derived id. Testing the unit with an input
 * the system cannot generate is what let a completely unwritten footer pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveTurnPersistenceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var database: AppDatabase
    private lateinit var transaction: PersistenceTransaction

    private val agentId = "agent-1"
    private val sessionId = "session-1"

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        transaction = PersistenceTransaction(database)

        runBlocking {
            database.agentDao().insertAgent(
                AgentEntity(
                    id = agentId,
                    address = "0xabc",
                    name = "Test Agent",
                    serverUrl = "https://example.com",
                    createdAt = 0L
                )
            )
            database.sessionDao().insertSession(
                ChatSessionEntity(
                    id = sessionId,
                    agentId = agentId,
                    title = "Existing",
                    createdAt = 0L,
                    updatedAt = 0L,
                    messageCount = 0,
                    lastMessagePreview = null
                )
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // Carries fromSessionSnapshot across: it is the whole difference between
    // a live reply and the server replaying one it sent earlier.
    private fun toChatEvent(e: ConnectionEvent): ChatEvent? = when (e) {
        is ConnectionEvent.ChatItemReceived -> ChatEvent.ChatItemReceived(e.item, e.fromSessionSnapshot)
        is ConnectionEvent.ChatItemUpdated -> ChatEvent.ChatItemUpdated(e.item)
        is ConnectionEvent.OutputReceived -> ChatEvent.OutputReceived(e.result, null)
        else -> null
    }

    /** Runs the frames through parser + reducer, persisting exactly what the reducer nominates. */
    private suspend fun play(frames: List<String>): List<ChatItem> {
        var list = emptyList<ChatItem>()
        for (frame in frames) {
            val parsed = ProtocolParser.parse(frame, outstandingInputIds = setOf("inp-1"), isDirect = false)
            // session_sync fans out one event per assistant message, exactly
            // as AgentConnection does — the earlier ones are the old replies.
            val events = (parsed.extraEvents + parsed.event).mapNotNull(::toChatEvent)
            for (event in events) {
                val result = ChatEventReducer.reduce(list, event, emptySet(), emptySet())
                list = result.chatItems
                result.itemToPersist?.let {
                    transaction.persistMessageAtomically(
                        sessionId = sessionId,
                        item = it,
                        onTitleUpdate = { _, _ -> },
                        onPreviewUpdate = { _, _, _ -> }
                    )
                }
            }
        }
        return list
    }

    /** The reload path, without standing up the whole use case. */
    private suspend fun reload(): List<ChatItem> =
        database.messageDao().getMessagesListBySession(sessionId)
            .map { MessageMapper.toDomain(it) }
            .mapNotNull { msg ->
                if (msg.itemType != TranscriptItemCodec.TURN_ITEM_TYPE) {
                    msg.payload?.let { return@mapNotNull TranscriptItemCodec.decode(it) }
                }
                when (msg.role) {
                    Role.USER -> ChatItem.User(id = msg.id, content = msg.content)
                    Role.ASSISTANT, Role.SYSTEM -> ChatItem.Turn(
                        id = msg.id,
                        thinking = msg.payload?.let { TranscriptItemCodec.decodeTurnThinking(it) },
                        agent = ChatItem.Agent(id = msg.id, content = msg.content)
                    )
                }
            }

    private val reply = "Hello there! What can I do for you?"

    /** llm_call → llm_result → OUTPUT, with no `assistant` frame in between. */
    private val ordinaryTurn = listOf(
        """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
        """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
        """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
    )

    /** The same turn, but the server also streams the text as an `assistant` frame. */
    private val ordinaryTurnWithAssistantFrame = listOf(
        """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
        """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
        """{"type":"assistant","content":"$reply"}""",
        """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
    )

    @Test
    fun `one llm turn becomes exactly one list item`() = runTest(testDispatcher) {
        val live = play(ordinaryTurn)

        // The property that was silently false: the footer and the reply were
        // two adjacent Turns under different ids, so the footer rendered above
        // the bubble instead of under it.
        assertEquals("one turn must be one item", 1, live.size)
        val turn = live.single() as ChatItem.Turn
        assertEquals(reply, turn.agent?.content)
        assertEquals(46, turn.thinking?.tokensTotal)
        assertEquals(ThinkingStatus.DONE, turn.thinking?.status)
        // The content hash wins, so a later session_sync replay dedupes.
        assertEquals(stableAssistantId(reply), turn.id)
    }

    @Test
    fun `an assistant frame ahead of OUTPUT still yields one item`() = runTest(testDispatcher) {
        val live = play(ordinaryTurnWithAssistantFrame)

        assertEquals(1, live.size)
        val turn = live.single() as ChatItem.Turn
        assertEquals(reply, turn.agent?.content)
        assertEquals(46, turn.thinking?.tokensTotal)
    }

    private val olderReply = "Sure — I checked the logs and found nothing unusual."

    /** A session_sync frame replaying one earlier exchange. */
    private fun sessionSync(vararg assistantMessages: String): String {
        val msgs = assistantMessages.joinToString(",") {
            """{"role":"user","content":"earlier question"},{"role":"assistant","content":"$it"}"""
        }
        return """{"type":"session_sync","session":{"messages":[$msgs]}}"""
    }

    @Test
    fun `a replay arriving mid-turn does not get absorbed by the pending turn`() = runTest(testDispatcher) {
        // The real device ordering: session_sync streams after every trace
        // entry, so a replay of an OLDER reply lands milliseconds after
        // llm_call, while this turn's own reply is still ~3s away.
        val live = play(
            listOf(
                sessionSync(olderReply),
                """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
                sessionSync(olderReply),
                """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
                """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
            )
        )

        // Two turns, both intact: the failure mode is one quietly absorbing
        // the other, which shows up as a count of 2 rather than 3, or 1.
        assertEquals(2, live.size)

        val older = live.first() as ChatItem.Turn
        assertEquals(olderReply, older.agent?.content)
        assertEquals(stableAssistantId(olderReply), older.id)
        assertNull("the older reply must not wear this turn's metadata", older.thinking)

        val current = live.last() as ChatItem.Turn
        assertEquals(reply, current.agent?.content)
        assertEquals(46, current.thinking?.tokensTotal)
        assertEquals(ThinkingStatus.DONE, current.thinking?.status)
    }

    @Test
    fun `a sync delivering this turn's reply before OUTPUT still gets the footer`() = runTest(testDispatcher) {
        // The ordering that actually lost the metadata: the sync fans out the
        // older reply first, which used to consume the pending turn, so the
        // new reply appended footer-less and the tokens vanished with it.
        val live = play(
            listOf(
                sessionSync(olderReply),
                """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
                """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
                sessionSync(olderReply, reply),
                """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
            )
        )

        assertEquals(2, live.size)
        val older = live.first() as ChatItem.Turn
        assertNull("the older reply must not wear this turn's metadata", older.thinking)

        val current = live.last() as ChatItem.Turn
        assertEquals(reply, current.agent?.content)
        assertEquals("the footer must survive a sync that beat OUTPUT to it", 46, current.thinking?.tokensTotal)
    }

    @Test
    fun `a footerless reply already on screen adopts the footer at OUTPUT`() = runTest(testDispatcher) {
        // The sync lands this turn's reply *before* llm_call, so the reply sits
        // ahead of the pending turn with no footer of its own. Replacing the
        // placeholder with a copy would leave two rows sharing an id and
        // dedupeUI would keep the footerless original, binning the metadata.
        val live = play(
            listOf(
                sessionSync(olderReply, reply),
                """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
                """{"type":"llm_result","id":"call-7","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
                """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
            )
        )

        assertEquals(2, live.size)
        val current = live.single { (it as ChatItem.Turn).agent?.content == reply } as ChatItem.Turn
        assertEquals("the reply already on screen must pick up the footer", 46, current.thinking?.tokensTotal)
    }

    @Test
    fun `the pending turn stays on screen while a replay arrives`() = runTest(testDispatcher) {
        // The "flashing" thinking indicator: the pending turn was consumed
        // 11ms after it appeared, so nothing read as in-progress until OUTPUT.
        val midTurn = play(
            listOf(
                """{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}""",
                sessionSync(olderReply)
            )
        )

        val pending = midTurn.filterIsInstance<ChatItem.Turn>().single { it.agent == null }
        assertEquals(ThinkingStatus.RUNNING, pending.thinking?.status)
        assertTrue("the turn must still read as in flight", pending.isInProgress())
    }

    @Test
    fun `a session_sync replay of the same reply does not duplicate the turn`() = runTest(testDispatcher) {
        var live = play(ordinaryTurn)
        // A replay carries only the text, so it can only produce the content
        // hash — which is why that id had to be the one that survives.
        val replay = ChatItem.Turn(
            id = stableAssistantId(reply),
            agent = ChatItem.Agent(id = stableAssistantId(reply), content = reply)
        )
        live = ChatEventReducer.reduce(live, ChatEvent.ChatItemReceived(replay), emptySet(), emptySet()).chatItems

        assertEquals(1, live.size)
        assertEquals("the replay must not wipe the footer", 46, (live.single() as ChatItem.Turn).thinking?.tokensTotal)
    }

    @Test
    fun `an ordinary live turn writes its footer metadata to the database`() = runTest(testDispatcher) {
        play(ordinaryTurn)

        // The device symptom: item_type empty and payload null on a message
        // written by the current build.
        val rows = database.messageDao().getMessagesListBySession(sessionId)
        val row = rows.single()
        assertEquals(TranscriptItemCodec.TURN_ITEM_TYPE, row.itemType)

        val restored = TranscriptItemCodec.decodeTurnThinking(row.payload!!)!!
        assertEquals(ThinkingStatus.DONE, restored.status)
        assertEquals("gemini-2.5-flash", restored.model)
        assertEquals(46, restored.tokensTotal)
        assertEquals(0.0071, restored.costUsd!!, 1e-9)
        assertEquals(12.5, restored.contextPercent!!, 1e-9)
    }

    @Test
    fun `a reloaded live turn shows what it showed live`() = runTest(testDispatcher) {
        val live = play(ordinaryTurn)
        val reloaded = reload()

        fun shape(items: List<ChatItem>) = items.map { item ->
            val t = item as ChatItem.Turn
            t.id to (t.thinking?.tokensTotal to t.agent?.content?.takeIf { it.isNotBlank() })
        }
        assertEquals(shape(live), shape(reloaded))
        assertEquals(1, reloaded.size)
    }

    @Test
    fun `a reloaded live turn still adds up to its session usage totals`() = runTest(testDispatcher) {
        play(ordinaryTurn)

        val totals = reload().sessionUsageTotals()
        assertEquals(46, totals.totalTokens)
        assertEquals(0.0071, totals.totalCostUsd, 1e-9)
        assertEquals(12.5, totals.latestContextPercent!!, 1e-9)
    }

    @Test
    fun `the reply itself is still a plain assistant row`() = runTest(testDispatcher) {
        play(ordinaryTurn)

        // One row, not two: the metadata annotates the reply rather than
        // taking a transcript row of its own, so the message stays searchable,
        // counted and available as the drawer preview.
        val row = database.messageDao().getMessagesListBySession(sessionId).single()
        assertEquals(Role.ASSISTANT.name, row.role)
        assertEquals(reply, row.content)
    }

    @Test
    fun `a turn that errors before any reply keeps its failed footer`() = runTest(testDispatcher) {
        play(
            listOf(
                """{"type":"llm_call","id":"call-9","model":"gemini-2.5-flash"}""",
                """{"type":"llm_result","id":"call-9","status":"error","model":"gemini-2.5-flash","duration_ms":400.0}"""
            )
        )

        val restored = reload().filterIsInstance<ChatItem.Turn>().single()
        assertEquals(ThinkingStatus.ERROR, restored.thinking?.status)
        assertNotNull("a failed turn is worth restoring even with no numbers", restored.thinking?.model)
    }

    /**
     * A turn that calls a tool: two `llm_call`s, one reply. The first call's
     * footer never gets a reply of its own — its "reply" was the tool call —
     * so it stays a footer-only row, which is what the device database held.
     */
    private val toolUsingTurn = listOf(
        """{"type":"llm_call","id":"call-1","model":"gemini-2.5-flash"}""",
        """{"type":"llm_result","id":"call-1","status":"success","model":"gemini-2.5-flash","duration_ms":300.0,"usage":{"input_tokens":10,"output_tokens":5,"cost":0.001},"context_percent":5.0}""",
        """{"type":"tool_call","tool_id":"tool-1","name":"search","args":{"q":"logs"}}""",
        """{"type":"tool_result","tool_id":"tool-1","name":"search","status":"success","result":"ok"}""",
        """{"type":"llm_call","id":"call-2","model":"gemini-2.5-flash"}""",
        """{"type":"llm_result","id":"call-2","status":"success","model":"gemini-2.5-flash","duration_ms":2100.0,"usage":{"input_tokens":30,"output_tokens":16,"cost":0.0071},"context_percent":12.5}""",
        """{"type":"OUTPUT","result":"$reply","input_id":"inp-1"}"""
    )

    @Test
    fun `a reloaded tool-using turn leaves the composer usable`() = runTest(testDispatcher) {
        play(toolUsingTurn)

        val reloaded = reload()
        // The device report: opened the app, touched nothing, and the send
        // button was a Stop button — isAgentWorking is exactly this predicate.
        assertFalse(
            "a settled conversation must not reload as an agent still working",
            reloaded.any { it.isInProgress() }
        )
        assertTrue(
            "the intermediate call's footer is still transcript worth keeping",
            reloaded.filterIsInstance<ChatItem.Turn>().any { it.agent == null && it.thinking != null }
        )
    }

    @Test
    fun `a tool-using turn counts both of its llm calls once each`() = runTest(testDispatcher) {
        val live = play(toolUsingTurn)

        // The footer-only row is a second llm call, not a duplicate of the
        // reply's — retiring it would *lose* 15 tokens, not stop a double count.
        assertEquals(61, live.sessionUsageTotals().totalTokens)
        assertEquals(61, reload().sessionUsageTotals().totalTokens)
    }

    @Test
    fun `a turn still open on llm_call alone reads as in progress`() = runTest(testDispatcher) {
        val live = play(listOf("""{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}"""))

        assertTrue("nothing has completed this turn yet", live.single().isInProgress())
    }

    @Test
    fun `an in-flight turn is not persisted as a spinner that can never finish`() = runTest(testDispatcher) {
        // Only llm_call — the process dies before llm_result lands.
        play(listOf("""{"type":"llm_call","id":"call-7","model":"gemini-2.5-flash"}"""))

        assertTrue(
            "a RUNNING turn must not reach the database",
            database.messageDao().getMessagesListBySession(sessionId).isEmpty()
        )
    }
}
