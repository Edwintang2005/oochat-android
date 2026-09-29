package ai.openonion.oochat.data.local

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import ai.openonion.oochat.data.protocol.FileAttachment
import ai.openonion.oochat.network.AgentConnection
import ai.openonion.oochat.network.PendingMessageSink
import ai.openonion.oochat.network.QueuedMessage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * That the persisted outbox stays bounded in bytes, not just in rows, and
 * that a queued attachment never ends up inside the row.
 *
 * A 10MB file (FileAttachmentStoreImpl.MAX_FILE_BYTES) is ~13.3MB of base64.
 * Stored in the row it is far past SQLite's 2MB CursorWindow, where the read
 * throws and takes every other queued message with it — and reaching that is
 * ordinary, not exotic: the first attachment-bearing message in a new
 * conversation is queued by design, because switchConversation drops the
 * ready flag before the INPUT is sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPendingMessageSinkTest {

    private lateinit var db: AppDatabase
    private lateinit var spillDir: File
    private lateinit var sink: RoomPendingMessageSink

    private val agent = "0xagent"
    private val session = "sess-1"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        spillDir = File(context.cacheDir, "pending-attachments-test-${System.nanoTime()}")
        sink = RoomPendingMessageSink(db.pendingMessageDao(), spillDir)
    }

    @After
    fun tearDown() {
        db.close()
        spillDir.deleteRecursively()
    }

    /** [PendingMessageSink.drain] as a list, for assertions about order and contents. */
    private fun PendingMessageSink.drainAll(sessionId: String? = session): List<QueuedMessage> =
        buildList { drain(agent, sessionId) { add(it) } }

    private fun file(name: String, bytes: Int) =
        FileAttachment(name = name, data = "data:application/pdf;base64," + "A".repeat(bytes))

    @Test
    fun `a queued message round-trips its images and files`() {
        val files = listOf(file("report.pdf", 64))
        sink.enqueue(agent, session, "see attached", listOf("data:image/png;base64,AAAA"), files)

        val drained = sink.drainAll()

        assertEquals(1, drained.size)
        assertEquals("see attached", drained.single().prompt)
        assertEquals(listOf("data:image/png;base64,AAAA"), drained.single().images)
        assertEquals(files, drained.single().files)
    }

    @Test
    fun `the attachment never lands in the row, so a big one cannot break the read`() {
        // The row is what a cursor has to materialize. Keeping a multi-megabyte
        // payload out of it is the whole reason for the spill file.
        sink.enqueue(agent, session, "big one", null, listOf(file("big.pdf", 4 * 1024 * 1024)))

        val row = db.pendingMessageDao().oldestClaimableForAgent(agent, session, Long.MAX_VALUE)!!
        assertNull("images stayed in the row", row.imagesJson)
        assertNull("files stayed in the row", row.filesJson)
        assertEquals(listOf("big one"), sink.drainAll().map { it.prompt })
    }

    @Test
    fun `the queue is bounded by attachment bytes, not only by row count`() {
        // Well under MAX_PENDING_MESSAGES rows, well over the byte budget:
        // capping rows alone let this grow to hundreds of megabytes.
        val eachBytes = (AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1
        sink.enqueue(agent, session, "first", null, listOf(file("a.pdf", eachBytes)))
        sink.enqueue(agent, session, "second", null, listOf(file("b.pdf", eachBytes)))

        val drained = sink.drainAll()

        assertEquals(
            "two attachments over the budget must not both stay queued",
            listOf("second"),
            drained.map { it.prompt }
        )
    }

    @Test
    fun `evicting a row takes its spilled attachment with it`() {
        val eachBytes = (AgentConnection.MAX_PENDING_ATTACHMENT_BYTES / 2).toInt() + 1
        sink.enqueue(agent, session, "first", null, listOf(file("a.pdf", eachBytes)))
        sink.enqueue(agent, session, "second", null, listOf(file("b.pdf", eachBytes)))

        assertEquals(
            "the evicted message's bytes are still on disk: ${spillDir.listFiles()?.map { it.name }}",
            1,
            spillDir.listFiles().orEmpty().size
        )
    }

    @Test
    fun `draining deletes the spill as it goes, leaving nothing behind`() {
        sink.enqueue(agent, session, "one", null, listOf(file("a.pdf", 1024)))
        sink.enqueue(agent, session, "two", null, listOf(file("b.pdf", 1024)))

        assertEquals(listOf("one", "two"), sink.drainAll().map { it.prompt })

        assertTrue(
            "spilled attachments outlived the drain: ${spillDir.listFiles()?.map { it.name }}",
            spillDir.listFiles().orEmpty().isEmpty()
        )
        assertEquals(0, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `only one message's attachments are resident at a time`() {
        // The point of the callback: the old read-and-clear decoded every
        // queued attachment before sending any of them.
        repeat(3) { i -> sink.enqueue(agent, session, "m-$i", null, listOf(file("f-$i.pdf", 1024))) }

        val remainingWhileDraining = mutableListOf<Int>()
        sink.drain(agent, session) { remainingWhileDraining += db.pendingMessageDao().countForAgent(agent) }

        assertEquals(
            "the queue must shrink one message at a time as each is handed over",
            listOf(2, 1, 0),
            remainingWhileDraining
        )
    }

    @Test
    fun `a message queued in another session stays put`() {
        sink.enqueue(agent, "sess-other", "meant for the other conversation", null, null)
        sink.enqueue(agent, null, "queued before any session existed", null, null)

        assertEquals(
            listOf("queued before any session existed"),
            sink.drainAll(session).map { it.prompt }
        )
        assertEquals(1, db.pendingMessageDao().countForAgent(agent))
    }

    @Test
    fun `a row written by an older build still carries its attachments in the columns`() {
        // Rows already in the outbox at upgrade time have no spill file. They
        // have to keep working, or an upgrade silently drops queued sends.
        db.pendingMessageDao().insert(
            PendingMessageEntity(
                agentAddress = agent,
                sessionId = session,
                prompt = "queued before the upgrade",
                imagesJson = """["data:image/png;base64,AAAA"]""",
                filesJson = """[{"name":"legacy.pdf","data":"data:application/pdf;base64,BBBB"}]""",
                createdAt = 0L
            )
        )

        val drained = sink.drainAll().single()

        assertEquals(listOf("data:image/png;base64,AAAA"), drained.images)
        assertEquals("legacy.pdf", drained.files?.single()?.name)
    }

    @Test
    fun `clear discards the rows and their spilled attachments`() {
        sink.enqueue(agent, session, "one", null, listOf(file("a.pdf", 1024)))

        sink.clear(agent)

        assertEquals(0, db.pendingMessageDao().countForAgent(agent))
        assertTrue(spillDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a spill whose row is gone is swept rather than left on disk forever`() {
        // Process death between claiming a row and reading its file would
        // otherwise strand the bytes, still counting against the byte bound.
        spillDir.mkdirs()
        File(spillDir, "999999.json").writeText("""{"images":["orphan"]}""")
        sink.enqueue(agent, session, "one", null, null)

        sink.drainAll()

        assertTrue(
            "an orphaned spill survived the drain: ${spillDir.listFiles()?.map { it.name }}",
            spillDir.listFiles().orEmpty().isEmpty()
        )
    }
}
