package ai.openonion.oochat.domain.model

data class AgentProfile(
    val id: String,
    val address: String,
    val name: String,
    val description: String? = null,
    val serverUrl: String,
    val apiKey: String? = null,
    val avatarUrl: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null,
    val isActive: Boolean = true,
    /** Connection mode: "relay" or "direct" */
    val connectionMode: String = "relay",
    /** Drag-to-reorder position (ascending) — same order surfaced by both AgentListScreen and NavDrawer. */
    val position: Int = 0
)
