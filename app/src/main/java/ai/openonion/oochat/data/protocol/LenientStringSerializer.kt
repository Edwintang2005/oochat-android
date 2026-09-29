package ai.openonion.oochat.data.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A wire string that must never take its frame down with it: a primitive
 * decodes to its unquoted text, an object or array to its JSON source, null
 * to blank. Always paired with a `= ""` default so a missing key survives too.
 *
 * Nested DTOs decode as part of the enclosing [ServerEvent], so without this
 * one wrong-typed field in one list entry drops the whole event — the failure
 * that leaves the agent blocked in `io.receive()`. ProtocolParser drops the
 * entries that come out blank.
 */
object LenientStringSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ai.openonion.oochat.data.protocol.LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = input.decodeJsonElement()) {
            // JsonNull is a JsonPrimitive whose contentOrNull is null → blank.
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}
