package ai.openonion.oochat.data.local.db

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
 * Manual migration test for [AppDatabase.MIGRATION_8_9] (chat_messages.files,
 * pending_messages.files_json — see AppDatabase's own doc). No
 * `androidx.room:room-testing` dependency exists in this module, so this
 * hand-builds a real v8 database with the framework SQLite helper (schema
 * copied verbatim from app/schemas/.../8.json) and then opens it through
 * [AppDatabase] itself, letting Room's real migration chain run — the same
 * approach [PersistenceTransactionTest]/[RoomRobolectricSpikeTest] use for
 * exercising a real Room database under Robolectric, just seeded at v8
 * first instead of created fresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration8To9Test {

    private val dbName = "migration-8-9-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /** Builds the v8 database on disk by hand — no Room involved yet. */
    private fun seedV8Database() {
        context.getDatabasePath(dbName).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // CREATE TABLE statements copied from
                        // app/schemas/ai.openonion.oochat.data.local.db.AppDatabase/8.json,
                        // `${TABLE_NAME}` substituted for the literal name.
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `agent_profiles` (`id` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `server_url` TEXT NOT NULL, `api_key` TEXT, `avatar_url` TEXT, `created_at` INTEGER NOT NULL, `last_connected_at` INTEGER, `is_active` INTEGER NOT NULL, `connection_mode` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `last_message_preview` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`agent_id`) REFERENCES `agent_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `model` TEXT, `duration_ms` INTEGER, `images` TEXT, `voice_path` TEXT, `voice_duration_seconds` REAL, `voice_transcript_status` TEXT, `item_type` TEXT, `payload` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `session_states` (`agent_address` TEXT NOT NULL, `session_id` TEXT, `turn` INTEGER, `messages_json` TEXT, `trace_json` TEXT, `last_updated` INTEGER NOT NULL, PRIMARY KEY(`agent_address`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `agent_address` TEXT NOT NULL, `prompt` TEXT NOT NULL, `images_json` TEXT, `created_at` INTEGER NOT NULL)"
                        )
                        // Indices from the same 8.json — Room validates every
                        // entity's full structure (indices included) at the
                        // post-migration version, even for tables MIGRATION_8_9
                        // never touches.
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_profiles_address` ON `agent_profiles` (`address`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_profiles_is_active` ON `agent_profiles` (`is_active`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_agent_id` ON `chat_sessions` (`agent_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_updated_at` ON `chat_sessions` (`updated_at`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id` ON `chat_messages` (`session_id`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_messages_agent_address` ON `pending_messages` (`agent_address`)")

                        // One old row per touched table, written with only the
                        // v8 columns — exactly what an installed .dev build's
                        // real data looks like going into this migration.
                        db.execSQL(
                            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                                "VALUES ('agent-1', '0xabc', 'Old Agent', 'https://example.com', 1000, 1, 'RELAY')"
                        )
                        db.execSQL(
                            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count) " +
                                "VALUES ('session-1', 'agent-1', 'Old Session', 1000, 1000, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('msg-1', 'session-1', 'USER', 'hello from before the migration', 1000)"
                        )
                        db.execSQL(
                            "INSERT INTO pending_messages (agent_address, prompt, images_json, created_at) " +
                                "VALUES ('0xabc', 'queued before migration', NULL, 1000)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("seedV8Database always creates at version 8; onUpgrade should not run here")
                    }
                })
                .build()
        )
        // Forces file creation (and onCreate) without leaving a connection open.
        helper.writableDatabase.close()
    }

    @Test
    fun `an old chat_messages row is still readable after the 8-9 migration, with files null`() = runTest {
        seedV8Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val messages = db.messageDao().getMessagesListBySession("session-1")
        assertEquals(1, messages.size)
        assertEquals("hello from before the migration", messages.single().content)
        assertNull("pre-migration rows have no files column value", messages.single().files)

        db.close()
    }

    @Test
    fun `an old pending_messages row is still readable after the 8-9 migration, with files_json null`() = runTest {
        seedV8Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val pending = db.pendingMessageDao().getAllForAgent("0xabc")
        assertEquals(1, pending.size)
        assertEquals("queued before migration", pending.single().prompt)
        assertNull("pre-migration rows have no files_json column value", pending.single().filesJson)

        db.close()
    }

    @Test
    fun `a new row written after the migration round-trips its files column`() = runTest {
        seedV8Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        db.messageDao().insertMessage(
            ai.openonion.oochat.data.local.db.entity.ChatMessageEntity(
                id = "msg-2",
                sessionId = "session-1",
                role = "USER",
                content = "",
                timestamp = 2000,
                files = "[{\"name\":\"report.pdf\",\"path\":\"file:///a/report.pdf\"}]"
            )
        )

        val saved = db.messageDao().getMessagesListBySession("session-1").first { it.id == "msg-2" }
        assertEquals("[{\"name\":\"report.pdf\",\"path\":\"file:///a/report.pdf\"}]", saved.files)

        db.close()
    }
}
