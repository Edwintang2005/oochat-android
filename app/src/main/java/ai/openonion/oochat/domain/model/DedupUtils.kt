package ai.openonion.oochat.domain.model

/**
 * Deduplicates chat items by their [ChatItem.id], keeping the FIRST occurrence of each id.
 *
 * Keeping the first occurrence preserves the original render order — the position
 * a ChatItem was first placed at is the position the user already saw it in. The
 * previous "keep last" implementation caused visible displacement whenever the same
 * id was re-appended: a server `session_sync` that replays the assistant history
 * on every turn would push the existing agent bubbles to the bottom of the list.
 *
 * New (in-place) updates already replace the existing item before dedupeUI runs,
 * so this function only needs to drop later duplicates — not overwrite originals.
 */
fun List<ChatItem>.dedupeUI(): List<ChatItem> {
    val seen = linkedSetOf<String>()
    val result = mutableListOf<ChatItem>()
    for (item in this) {
        if (seen.add(item.id)) {
            result.add(item)
        }
    }
    return result
}
