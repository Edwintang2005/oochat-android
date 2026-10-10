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

/**
 * The form an agent address is saved in, wherever it is saved. Hex is
 * case-insensitive, so `0xAB…` and `0xab…` are one key and must be one agent;
 * lower case is what the connectonion SDK writes (`"0x" + bytes.hex()`).
 * Lookups ignore case as well (AgentDao), for rows saved before this.
 * W16AAPPLE-57.
 */
fun canonicalAgentAddress(address: String): String = address.lowercase()
