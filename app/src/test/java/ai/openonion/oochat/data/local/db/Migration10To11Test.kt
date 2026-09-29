package ai.openonion.oochat.data.local.db

import ai.openonion.oochat.data.local.db.entity.SessionStateEntity
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manual migration test for [AppDatabase.MIGRATION_10_11] — the move from one
 * server session per agent to one per conversation.
 *
 * Two things it has to prove about an existing install: the local transcript
 * survives untouched (non-negotiable), and the old agent-keyed session row is
 * gone rather than silently re-attached to some conversation that never owned
 * it. Built the same way as [Migration8To9Test]: a real v10 database on disk
 * (schema copied verbatim from app/schemas/.../10.json), opened through
 * [AppDatabase] so Room's real migration chain runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration10To11Test {

    private val dbName = "migration-10-11-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun seedV10Database() {
        context.getDatabasePath(dbName).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `agent_profiles` (`id` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `server_url` TEXT NOT NULL, `api_key` TEXT, `avatar_url` TEXT, `created_at` INTEGER NOT NULL, `last_connected_at` INTEGER, `is_active` INTEGER NOT NULL, `connection_mode` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `last_message_preview` TEXT, `server_message_offset` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`agent_id`) REFERENCES `agent_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `model` TEXT, `duration_ms` INTEGER, `images` TEXT, `files` TEXT, `voice_path` TEXT, `voice_duration_seconds` REAL, `voice_transcript_status` TEXT, `item_type` TEXT, `payload` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `session_states` (`agent_address` TEXT NOT NULL, `session_id` TEXT, `turn` INTEGER, `messages_json` TEXT, `trace_json` TEXT, `last_updated` INTEGER NOT NULL, PRIMARY KEY(`agent_address`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `agent_address` TEXT NOT NULL, `prompt` TEXT NOT NULL, `images_json` TEXT, `files_json` TEXT, `created_at` INTEGER NOT NULL)"
                        )
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_profiles_address` ON `agent_profiles` (`address`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_profiles_is_active` ON `agent_profiles` (`is_active`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_agent_id` ON `chat_sessions` (`agent_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_updated_at` ON `chat_sessions` (`updated_at`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id` ON `chat_messages` (`session_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_messages_agent_address` ON `pending_messages` (`agent_address`)")

                        // Two conversations with one agent, each with its own
                        // transcript — the shape the offset scheme existed to
                        // tell apart, and the shape most at risk here.
                        db.execSQL(
                            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                                "VALUES ('agent-1', '0xabc', 'Old Agent', 'https://example.com', 1000, 1, 'RELAY')"
                        )
                        db.execSQL(
                            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count, server_message_offset) " +
                                "VALUES ('session-1', 'agent-1', 'First chat', 1000, 1000, 2, 0)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count, server_message_offset) " +
                                "VALUES ('session-2', 'agent-1', 'Second chat', 2000, 2000, 1, 6)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-1', 'session-1', 'USER', 'question in the first chat', 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-2', 'session-1', 'ASSISTANT', 'answer in the first chat', 1100)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-3', 'session-2', 'USER', 'question in the second chat', 2000)"
                        )
                        db.execSQL(
                            "INSERT INTO pending_messages (agent_address, prompt, images_json, created_at) " +
                                "VALUES ('0xabc', 'queued before the upgrade', NULL, 1500)"
                        )
                        // The one agent-keyed server session both conversations
                        // were sharing before this migration.
                        db.execSQL(
                            "INSERT INTO session_states (agent_address, session_id, turn, messages_json, last_updated) " +
                                "VALUES ('0xabc', 'server-session-old', 4, '[]', 2000)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("seedV10Database always creates at version 10; onUpgrade should not run here")
                    }
                })
                .build()
        )
        helper.writableDatabase.close()
    }

    /** Production's own migration chain, so a migration left out of it fails here. */
    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `every conversation survives the rebuild of chat_sessions`() = runTest {
        seedV10Database()
        val db = openMigrated()

        val sessions = db.sessionDao().getSessionById("session-1")
        assertNotNull("the first conversation was lost in the table rebuild", sessions)
        assertEquals("First chat", sessions!!.title)
        assertEquals(2, sessions.messageCount)

        val second = db.sessionDao().getSessionById("session-2")
        assertNotNull("the second conversation was lost in the table rebuild", second)
        assertEquals("Second chat", second!!.title)
        assertEquals(2000L, second.createdAt)

        db.close()
    }

    @Test
    fun `local transcripts survive intact, in both conversations`() = runTest {
        seedV10Database()
        val db = openMigrated()

        // The non-negotiable one: server-side continuity may be dropped by
        // this migration, the user's own transcript may not.
        val first = db.messageDao().getMessagesListBySession("session-1")
        assertEquals(2, first.size)
        assertEquals(
            listOf("question in the first chat", "answer in the first chat"),
            first.sortedBy { it.timestamp }.map { it.content }
        )

        val second = db.messageDao().getMessagesListBySession("session-2")
        assertEquals(1, second.size)
        assertEquals("question in the second chat", second.single().content)

        db.close()
    }

    @Test
    fun `the old agent-keyed server session is dropped rather than handed to a conversation`() = runTest {
        seedV10Database()
        val db = openMigrated()

        // An agent-keyed row covers every conversation at once, so there is no
        // conversation it can honestly be assigned to. Giving it to one would
        // reinstate exactly the mixing this change removes.
        assertNull(db.sessionStateDao().getSession("session-1"))
        assertNull(db.sessionStateDao().getSession("session-2"))
        assertNull(db.sessionStateDao().getSession("0xabc"))

        db.close()
    }

    @Test
    fun `a per-conversation session written after the migration round-trips`() = runTest {
        seedV10Database()
        val db = openMigrated()

        db.sessionStateDao().saveSession(
            SessionStateEntity(conversationId = "session-1", sessionId = "sid-for-1", turn = 1)
        )
        db.sessionStateDao().saveSession(
            SessionStateEntity(conversationId = "session-2", sessionId = "sid-for-2", turn = 9)
        )

        assertEquals("sid-for-1", db.sessionStateDao().getSession("session-1")?.sessionId)
        assertEquals("sid-for-2", db.sessionStateDao().getSession("session-2")?.sessionId)

        db.close()
    }

    @Test
    fun `a session for a conversation that does not exist can be written, then swept`() = runTest {
        seedV10Database()
        val db = openMigrated()

        // No foreign key: a conversation is connected and has a session well
        // before its row is created on the first message, and a parent
        // constraint rejected every one of those saves — see
        // SessionStateEntity. The sweep is what replaces CASCADE.
        db.sessionStateDao().saveSession(
            SessionStateEntity(conversationId = "not-yet-a-conversation", sessionId = "sid-pending")
        )
        db.sessionStateDao().saveSession(
            SessionStateEntity(conversationId = "session-2", sessionId = "sid-for-2")
        )
        assertEquals("sid-pending", db.sessionStateDao().getSession("not-yet-a-conversation")?.sessionId)

        db.sessionDao().deleteSessionById("session-1")
        assertEquals(1, db.sessionStateDao().deleteOrphaned())

        assertNull("the orphan must be swept", db.sessionStateDao().getSession("not-yet-a-conversation"))
        assertEquals(
            "a live conversation's session must survive the sweep",
            "sid-for-2",
            db.sessionStateDao().getSession("session-2")?.sessionId
        )

        db.close()
    }

    @Test
    fun `a message queued before the upgrade survives and names no session`() = runTest {
        // MIGRATION_11_12 adds pending_messages.session_id so a queued send
        // is not flushed into whichever conversation the socket reconnects
        // on. Rows written before it name no session, which is how they were
        // already treated — they flush on the first session that comes back.
        seedV10Database()
        val db = openMigrated()

        val pending = db.pendingMessageDao().getAllForAgent("0xabc")
        assertEquals(1, pending.size)
        assertEquals("queued before the upgrade", pending.single().prompt)
        assertNull("a pre-migration row names no session", pending.single().sessionId)

        db.close()
    }
}
