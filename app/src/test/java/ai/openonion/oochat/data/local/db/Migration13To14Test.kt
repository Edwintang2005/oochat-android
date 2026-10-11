package ai.openonion.oochat.data.local.db

import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
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
 * Manual migration test for [AppDatabase.MIGRATION_13_14] — user messages get
 * a column saying whether they got through (`chat_messages.delivery_state`).
 *
 * Built the same way as [Migration12To13Test]: a real v13 database on disk
 * (every CREATE statement taken from app/schemas/.../13.json), opened through
 * [AppDatabase] so Room's real migration chain and schema validation run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration13To14Test {

    private val dbName = "migration-13-14-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    private fun seedV13Database() {
        context.getDatabasePath(dbName).delete()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
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
                            "CREATE TABLE IF NOT EXISTS `session_states` (`conversation_id` TEXT NOT NULL, `session_id` TEXT, `turn` INTEGER, `messages_json` TEXT, `trace_json` TEXT, `plan_json` TEXT, `last_updated` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `pending_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `agent_address` TEXT NOT NULL, `prompt` TEXT NOT NULL, `images_json` TEXT, `files_json` TEXT, `session_id` TEXT, `created_at` INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_pending_messages_agent_address` ON `pending_messages` (`agent_address`)"
                        )

                        // A user message as an installed v13 build wrote it.
                        db.execSQL(
                            "INSERT INTO agent_profiles (id, address, name, server_url, created_at, is_active, connection_mode) " +
                                "VALUES ('agent-1', '0xabc', 'Agent', 'wss://relay', 1000, 1, 'RELAY')"
                        )
                        db.execSQL(
                            "INSERT INTO chat_sessions (id, agent_id, title, created_at, updated_at, message_count) " +
                                "VALUES ('conv-1', 'agent-1', 'Chat', 1000, 1000, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO chat_messages (id, session_id, role, content, timestamp) " +
                                "VALUES ('m1', 'conv-1', 'USER', 'hello', 1000)"
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
    fun `an existing message survives and reads back as delivered`() = runTest {
        seedV13Database()
        val db = openMigrated()

        val row = db.messageDao().getMessagesListBySession("conv-1").single()

        assertEquals("hello", row.content)
        // Before this column, every message reloaded as sent; it still does.
        assertNull(row.deliveryState)
        db.close()
    }

    @Test
    fun `the migrated table records a failed message`() = runTest {
        seedV13Database()
        val db = openMigrated()

        db.messageDao().updateDeliveryState("m1", ChatMessageEntity.DELIVERY_FAILED)

        assertEquals(ChatMessageEntity.DELIVERY_FAILED, db.messageDao().getDeliveryStateById("m1"))
        db.close()
    }
}
