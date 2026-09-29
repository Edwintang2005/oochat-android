package ai.openonion.oochat.domain.usecase

import ai.openonion.oochat.data.local.db.AppDatabase
import ai.openonion.oochat.data.local.db.dao.MessageDao
import ai.openonion.oochat.data.local.db.dao.SessionDao
import ai.openonion.oochat.data.local.db.entity.ChatMessageEntity
import ai.openonion.oochat.data.local.mapper.MessageMapper
import ai.openonion.oochat.data.local.mapper.TranscriptItemCodec
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.Role
import androidx.room.withTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

open class PersistenceTransaction(
    private val database: AppDatabase
) {
    private val messageDao: MessageDao = database.messageDao()
    private val sessionDao: SessionDao = database.sessionDao()
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun persistMessageAtomically(
        sessionId: String,
        item: ChatItem,
        onTitleUpdate: suspend (String, String) -> Unit,
        onPreviewUpdate: suspend (String, Int, String?) -> Unit
    ) {
        database.withTransaction {
            // session_sync replays the whole thread after every turn, and
            // assistant messages carry a content-derived id, so a replay
            // re-inserts rows that already exist. insertMessage is REPLACE and
            // the list is read back ORDER BY timestamp, so stamping `now` on a
            // replay silently moved every earlier reply to the end of the
            // conversation. Keep the original instant; only new rows get now.
            val originalTimestamp = messageDao.getTimestampById(item.id)
            val now = System.currentTimeMillis()
            // The message keeps the instant it first arrived; the session's own
            // "last activity" is still now, so a replay doesn't drag the drawer
            // row backwards.
            val messageTimestamp = originalTimestamp ?: now

            // A content-derived id can collide with a row already filed under
            // a *different* session (a session_sync replay landing in a fresh
            // conversation). insertMessage is REPLACE, which would silently
            // relocate that row here and drop it from the session that owns
            // it. Leave it where it is instead.
            val existingSessionId = messageDao.getSessionIdById(item.id)
            if (existingSessionId != null && existingSessionId != sessionId) return@withTransaction
            val files = (item as? ChatItem.User)?.files
            // Transcript items take the item_type/payload columns and skip the
            // text path — no content, and not part of the preview or title.
            // Null here means plain chat text (below) or a gate we don't replay.
            val transcript = TranscriptItemCodec.toPayload(item)
            if (transcript != null) {
                messageDao.insertMessage(
                    ChatMessageEntity(
                        id = item.id,
                        sessionId = sessionId,
                        // Keeps the row parseable by anything reading `role`
                        // blind; the real kind is in item_type.
                        role = Role.SYSTEM.name,
                        content = "",
                        timestamp = messageTimestamp,
                        itemType = transcript::class.simpleName,
                        payload = TranscriptItemCodec.encode(transcript)
                    )
                )
                // No preview/count/title update: a tool call isn't what the
                // drawer should show as "last message".
                return@withTransaction
            }

            val (role, content, images) = when (item) {
                is ChatItem.User -> Triple(Role.USER, item.content, item.images)
                is ChatItem.Agent -> Triple(Role.ASSISTANT, item.content, item.images)
                is ChatItem.Turn -> {
                    val agent = item.agent ?: return@withTransaction
                    Triple(Role.ASSISTANT, agent.content, agent.images)
                }
                else -> return@withTransaction
            }
            if (content.isBlank() && images.isNullOrEmpty() && files.isNullOrEmpty()) return@withTransaction

            // A Turn's footer metadata rides along on its own assistant row
            // rather than a transcript row of its own: the row keeps its
            // role/content, so the message stays searchable, counted and
            // previewable, and no schema change is needed.
            //
            // A session_sync replay re-sends the same turn with thinking =
            // null, and insertMessage is REPLACE — so a replay must fall back
            // to whatever was already recorded instead of erasing the footer.
            val turnThinking = (item as? ChatItem.Turn)?.thinking
                ?.let { TranscriptItemCodec.encodeTurnThinking(it) }
                ?: messageDao.getPayloadById(item.id)

            // Before its reply arrived this turn was a footer-only row filed
            // under the server's frame id, which the thinking still carries.
            // The merged item supersedes it, so retire it rather than leave a
            // second row that double-counts in the session totals.
            (item as? ChatItem.Turn)?.thinking?.id
                ?.takeIf { it != item.id }
                ?.let { messageDao.deleteMessageById(it) }

            val encodedImages = images?.let { json.encodeToString(it) }
            val entity = ChatMessageEntity(
                id = item.id,
                sessionId = sessionId,
                role = role.name,
                content = content,
                timestamp = messageTimestamp,
                images = encodedImages,
                files = MessageMapper.encodeFiles(files),
                itemType = turnThinking?.let { TranscriptItemCodec.TURN_ITEM_TYPE },
                payload = turnThinking
            )
            messageDao.insertMessage(entity)

            val session = sessionDao.getSessionById(sessionId)
            if (session != null && role == Role.USER && session.title == "New conversation") {
                val newTitle = content.trim().take(40).ifBlank { "New conversation" }
                sessionDao.updateTitle(sessionId, newTitle, now)
                onTitleUpdate(sessionId, newTitle)
            }

            val count = messageDao.getMessageCount(sessionId)
            val preview = content.take(80)
            sessionDao.updateMessageInfo(sessionId, count, preview, now)
            onPreviewUpdate(sessionId, count, preview)
        }
    }
}
