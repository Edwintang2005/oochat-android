package ai.openonion.oochat.data.local.db

import ai.openonion.oochat.data.local.db.dao.AgentDao
import ai.openonion.oochat.data.local.db.dao.MessageDao
import ai.openonion.oochat.data.local.db.dao.PendingMessageDao
import ai.openonion.oochat.data.local.db.dao.SessionDao
import ai.openonion.oochat.data.local.db.dao.SessionStateDao
import ai.openonion.oochat.data.local.db.entity.AgentEntity
import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.data.local.db.entity.ChatSessionEntity
import ai.openonion.oochat.data.local.db.entity.PendingMessageEntity
import ai.openonion.oochat.data.local.db.entity.SessionStateEntity
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versions 1-4 predate schema export, so an upgrade from one of those still
 * rebuilds destructively — scoped via [fallbackToDestructiveMigrationFrom]
 * rather than a blanket fallback that silently wipes on every future bump.
 * From v5 on every change is a real [Migration]. Add one here for any new
 * schema change instead of growing the destructive list.
 *
 * Numbering matches the team repo (the previous project repository)
 * as of this commit, so the two stay mergeable; see the commit message for
 * what that realignment cost.
 */
@Database(
    entities = [
        AgentEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SessionStateEntity::class,
        PendingMessageEntity::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun agentDao(): AgentDao

    abstract fun sessionDao(): SessionDao

    abstract fun messageDao(): MessageDao

    abstract fun sessionStateDao(): SessionStateDao

    abstract fun pendingMessageDao(): PendingMessageDao

    companion object {
        private const val DATABASE_NAME = "connectonion_db"

        // internal (not private): Migration8To9Test opens a hand-built v8
        // database and drives this migration directly, so it needs access
        // from outside this class body.
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN voice_transcript_status TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN voice_path TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN voice_duration_seconds REAL")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `agent_address` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `images_json` TEXT,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_messages_agent_address` " +
                        "ON `pending_messages` (`agent_address`)"
                )
            }
        }

        /**
         * Transcript columns, so tool calls survive a session reload. Additive
         * and nullable — existing rows keep rendering off `role`/`content`.
         *
         * ⚠️ v7 is the last version shared with the team repo. If that repo also
         * mints a v8, reconcile by hand before merging: one version number would
         * otherwise describe two schemas.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN item_type TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN payload TEXT")
            }
        }

        /**
         * File attachments, mirroring how MIGRATION_5_6 added voice columns:
         * `chat_messages.files` holds the User-side JSON (name+path pairs,
         * see [ai.openonion.oochat.data.local.mapper.MessageMapper]);
         * `pending_messages.files_json` carries the same shape the outbox
         * already uses for `images_json` (base64 [ai.openonion.oochat.data.protocol.FileAttachment]
         * list) so a message queued while disconnected doesn't drop its
         * files. Both additive/nullable — existing rows just read back with
         * files == null.
         *
         * ⚠️ The previous project repository is at v8 with
         * an identical schema (identityHash 6a59fb2c501429d79140f8b50ca3015b
         * as of this commit) — this exact SQL is meant to be copied over
         * verbatim when that repo mints its own v9, so the two stay
         * mergeable.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN files TEXT")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN files_json TEXT")
            }
        }

        /**
         * Where a conversation started in the server's per-agent message
         * stream, for the positional attribution of replayed history.
         * Superseded by [MIGRATION_10_11], which drops the column again —
         * kept here because an install still on v9 has to pass through it.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_sessions ADD COLUMN server_message_offset INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * One server session per conversation instead of per agent — see
         * [SessionStateEntity]. `session_states` is rebuilt keyed by
         * conversation id and deliberately NOT backfilled: an agent-keyed
         * row covers every conversation at once, so there is no conversation
         * it can honestly be assigned to. Every pre-existing conversation
         * therefore starts a fresh server session on its next CONNECT.
         * Local transcripts are untouched — `chat_messages` is not read
         * here and `chat_sessions` is copied row-for-row, minus the
         * now-unused `server_message_offset`.
         *
         * No foreign key on `conversation_id` — see [SessionStateEntity] for
         * why the parent row cannot be relied on to exist yet.
         */
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `session_states`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_states` (
                        `conversation_id` TEXT NOT NULL,
                        `session_id` TEXT,
                        `turn` INTEGER,
                        `messages_json` TEXT,
                        `trace_json` TEXT,
                        `last_updated` INTEGER NOT NULL,
                        PRIMARY KEY(`conversation_id`)
                    )
                    """.trimIndent()
                )

                // SQLite can't drop a column here (the minSdk floor predates
                // ALTER TABLE DROP COLUMN), so chat_sessions is rebuilt.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_sessions_new` (
                        `id` TEXT NOT NULL,
                        `agent_id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `message_count` INTEGER NOT NULL,
                        `last_message_preview` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`agent_id`) REFERENCES `agent_profiles`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "INSERT INTO `chat_sessions_new` " +
                        "(`id`, `agent_id`, `title`, `created_at`, `updated_at`, `message_count`, `last_message_preview`) " +
                        "SELECT `id`, `agent_id`, `title`, `created_at`, `updated_at`, `message_count`, `last_message_preview` " +
                        "FROM `chat_sessions`"
                )
                db.execSQL("DROP TABLE `chat_sessions`")
                db.execSQL("ALTER TABLE `chat_sessions_new` RENAME TO `chat_sessions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_agent_id` ON `chat_sessions` (`agent_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_sessions_updated_at` ON `chat_sessions` (`updated_at`)")
            }
        }

        /**
         * Which conversation a queued message was written in, so a reconnect
         * cannot flush it into whichever one the socket lands on — see
         * PendingMessageEntity.sessionId. Additive and nullable; rows written
         * before this read back as "no session named", which is how they were
         * already treated.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN session_id TEXT")
            }
        }

        /**
         * The one migration chain, shared with the migration tests so a
         * migration can never be written, tested, and then left out of the
         * builder below — which would crash a real upgrade.
         */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // dropAllTables = false: rebuild only what Room declares.
                    // The two are equivalent here — nothing outside Room owns a
                    // table in this database — so this takes the narrower of the
                    // two, which stays correct if that ever stops being true.
                    .fallbackToDestructiveMigrationFrom(dropAllTables = false, 1, 2, 3, 4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
