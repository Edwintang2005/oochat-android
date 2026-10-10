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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Manual migration test for [AppDatabase.MIGRATION_12_13] — the session's
 * Todo List gets a column (`session_states.plan_json`).
 *
 * Built the same way as [Migration10To11Test]: a real v12 database on disk
 * (every CREATE statement taken from app/schemas/.../12.json), opened through
 * [AppDatabase] so Room's real migration chain and schema validation run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration12To13Test {

    private val dbName = "migration-12-13-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun seedV12Database() {
        context.getDatabasePath(dbName).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `agent_profiles` (`id` TEXT NOT NULL, `address` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `server_url` TEXT NOT NULL, `api_key` TEXT, `avatar_url` TEXT, `created_at` INTEGER NOT NULL, `last_connected_at` INTEGER, `is_active` INTEGER NOT NULL, `connection_mode` TEXT NOT NULL, `position` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_profiles_address` ON `agent_profiles` (`address`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_agent_profiles_is_active` ON `agent_profiles` (`is_active`)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `last_message_preview` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`agent_id`) REFERENCES `agent_profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_chat_sessions_agent_id` ON `chat_sessions` (`agent_id`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_chat_sessions_updated_at` ON `chat_sessions` (`updated_at`)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `model` TEXT, `duration_ms` INTEGER, `images` TEXT, `files` TEXT, `voice_path` TEXT, `voice_duration_seconds` REAL, `voice_transcript_status` TEXT, `item_type` TEXT, `payload` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_chat_messages_session_id` ON `chat_messages` (`session_id`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `session_states` (`conversation_id` TEXT NOT NULL, `session_id` TEXT, `turn` INTEGER, `messages_json` TEXT, `trace_json` TEXT, `last_updated` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `agent_address` TEXT NOT NULL, `prompt` TEXT NOT NULL, `images_json` TEXT, `files_json` TEXT, `session_id` TEXT, `created_at` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_pending_messages_agent_address` ON `pending_messages` (`agent_address`)"
                        )

                        // A stored session as an installed v12 build wrote it.
                        db.execSQL(
                            "INSERT INTO session_states (conversation_id, session_id, turn, messages_json, last_updated) " +
                                "VALUES ('conv-1', 'sid-1', 5, '[]', 1000)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun `an existing session survives and reads back with no plan`() = runTest {
        seedV12Database()
        val db = openMigrated()

        val row = db.sessionStateDao().getSession("conv-1")

        assertEquals("sid-1", row?.sessionId)
        assertEquals(5, row?.turn)
        assertEquals("[]", row?.messagesJson)
        assertNull(row?.planJson)
        db.close()
    }

    @Test
    fun `the migrated table stores a plan`() = runTest {
        seedV12Database()
        val db = openMigrated()

        db.sessionStateDao().saveSession(
            SessionStateEntity(conversationId = "conv-1", sessionId = "sid-1", planJson = "[]")
        )

        assertEquals("[]", db.sessionStateDao().getSession("conv-1")?.planJson)
        db.close()
    }
}
