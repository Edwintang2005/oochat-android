package ai.openonion.oochat.data.local.db

import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Storing a turn's footer metadata needed no schema change: `item_type` and
 * `payload` have existed on `chat_messages` since MIGRATION_7_8, and a turn
 * row now fills them while keeping its own role/content. This pins that —
 * a real v10 database on disk opens at v10 with its transcript intact and no
 * migration run, which is what makes the change safe to ship alongside the
 * unmerged branch that mints its own v11.
 *
 * Seeds by hand with the framework SQLite helper, like [Migration8To9Test].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnMetadataSchemaCompatTest {

    private val dbName = "turn-metadata-v10-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /** Builds the v10 database on disk by hand — no Room involved yet. */
    private fun seedV10Database() {
        context.getDatabasePath(dbName).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // CREATE statements copied from
                        // app/schemas/ai.openonion.oochat.data.local.db.AppDatabase/10.json.
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

                        db.execSQL(
                            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                                "VALUES ('agent-1', '0xabc', 'Old Agent', 'https://example.com', 1000, 1, 'RELAY')"
                        )
                        db.execSQL(
                            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count, server_message_offset) " +
                                "VALUES ('session-1', 'agent-1', 'Old Session', 1000, 1000, 2, 0)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-1', 'session-1', 'USER', 'what did you find?', 1000)"
                        )
                        // An assistant reply from before turn metadata was
                        // stored: real content, no item_type, no payload.
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-2', 'session-1', 'ASSISTANT', 'three matches', 2000)"
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

    // The shared chain, not a hand-listed copy of it: this test hard-coded the
    // migrations up to 9_10 and so broke the moment a 10_11 landed — the exact
    // drift ALL_MIGRATIONS exists to make impossible.
    private fun openDatabase(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `an existing v10 transcript opens untouched, with no footer claimed for it`() = runTest {
        seedV10Database()
        val db = openDatabase()

        val messages = db.messageDao().getMessagesListBySession("session-1")
        assertEquals(2, messages.size)
        assertEquals("what did you find?", messages[0].content)
        assertEquals("three matches", messages[1].content)
        assertNull("a pre-existing reply has no stored metadata", messages[1].payload)
        assertNull(messages[1].itemType)

        db.close()
    }

    @Test
    fun `a turn written into that same database round-trips its metadata`() = runTest {
        seedV10Database()
        val db = openDatabase()

        val payload = TranscriptItemCodec.encodeTurnThinking(
            ai.openonion.oochat.domain.model.ChatItem.Thinking(
                id = "msg-3",
                status = ai.openonion.oochat.domain.model.ThinkingStatus.DONE,
                model = "gemini-2.5-flash",
                tokensTotal = 46
            )
        )!!
        db.messageDao().insertMessage(
            ChatMessageEntity(
                id = "msg-3",
                sessionId = "session-1",
                role = "ASSISTANT",
                content = "and one more",
                timestamp = 3000,
                itemType = TranscriptItemCodec.TURN_ITEM_TYPE,
                payload = payload
            )
        )

        val saved = db.messageDao().getMessagesListBySession("session-1").first { it.id == "msg-3" }
        val thinking = TranscriptItemCodec.decodeTurnThinking(saved.payload!!)
        assertEquals("gemini-2.5-flash", thinking?.model)
        assertEquals(46, thinking?.tokensTotal)
        // The row is still a plain assistant message, so it stays counted.
        assertEquals("and one more", saved.content)
        assertEquals(3, db.messageDao().getMessageCount("session-1"))

        db.close()
    }
}
