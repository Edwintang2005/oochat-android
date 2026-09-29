package ai.openonion.oochat.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_profiles",
    indices = [
        Index(value = ["address"], unique = true),
        Index(value = ["is_active"])
    ]
)
data class AgentEntity(
    @PrimaryKey
    val id: String,

    val address: String,

    val name: String,

    val description: String? = null,

    @ColumnInfo(name = "server_url")
    val serverUrl: String,

    @Deprecated(
        message = "API key is stored in EncryptedSharedPreferences via AgentSecureConfigRepository. " +
                  "This field is retained only for Room schema compatibility.",
        level = DeprecationLevel.WARNING
    )
    @ColumnInfo(name = "api_key")
    val apiKey: String? = null,

    @ColumnInfo(name = "avatar_url")
    val avatarUrl: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_connected_at")
    val lastConnectedAt: Long? = null,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "connection_mode")
    val connectionMode: String = "relay",

    /** Drag-to-reorder position (ascending) — same order surfaced by both AgentListScreen and NavDrawer. */
    @ColumnInfo(name = "position", defaultValue = "0")
    val position: Int = 0
)
