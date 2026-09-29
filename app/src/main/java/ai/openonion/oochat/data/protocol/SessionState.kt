package ai.openonion.oochat.data.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session state synced from server.
 *
 * The wire `trace` array is deliberately absent. Nothing ever read it — it
 * was deserialized into a JsonElement tree (3-5x its source text in heap)
 * only to be serialized straight back out by SessionStoreImpl, once per
 * session_sync, for the whole life of a conversation. Every Json instance
 * that decodes this is ignoreUnknownKeys, so leaving it out means the tree
 * is never built at all. Add it back only with a reader.
 */
@Serializable
data class SessionState(
    @SerialName("session_id") val sessionId: String? = null,
    val messages: List<SessionMessage>? = null,
    val turn: Int? = null
)

@Serializable
data class SessionMessage(
    // Lenient and defaulted for the same reason `content` is nullable below:
    // a missing or wrong-typed `role` in one message aborted the whole
    // enclosing session_sync/OUTPUT frame. A blank role matches neither
    // "user" nor "assistant", so ProtocolParser skips just that entry.
    @Serializable(with = LenientStringSerializer::class)
    val role: String = "",
    // Nullable: an assistant message that only made tool calls (no text
    // reply) is sent by the server with `content` omitted entirely, not an
    // empty string — a non-null String here threw "Field 'content' is
    // required" and silently dropped the *entire* enclosing session_sync/
    // OUTPUT frame (every other message in it too), not just this one
    // entry. Observed on a real session (`$.session.messages[1]` missing
    // content) via the app's own FileLogger.
    @Serializable(with = FlexibleContentSerializer::class)
    val content: String? = null
)

/**
 * Reads `content` whether the server sends a plain string or, once the
 * message carries an image, an array of typed content blocks.
 *
 * Same blast radius as the nullability note above — a shape mismatch here
 * aborts the whole enclosing frame, so one image in the history silently
 * killed every session_sync and OUTPUT for that conversation.
 *
 * Only text blocks are kept: image blocks are base64 payloads already held
 * locally, and inlining them would bloat the transcript for no gain.
 */
object FlexibleContentSerializer : KSerializer<String?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SessionMessageContent", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element
                .mapNotNull { block ->
                    (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
