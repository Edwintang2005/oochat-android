package ai.openonion.oochat.data.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One entry of an `AGENT_PROFILE` frame's `skills` list — `{"name": …,
 * "description": …}`, the shape the relay's own directory publishes for every
 * agent that has skills at all (`GET /api/agents`, and connectonion's
 * `tests/unit/test_agent_profile.py` asserts on `s["name"]`).
 *
 * A missing [name] decodes to blank rather than failing; ProtocolParser drops
 * those entries.
 */
@Serializable(with = SkillWireSerializer::class)
data class SkillWire(
    val name: String,
    val description: String? = null
)

/**
 * Accepts either `{"name": …, "description": …}` or a bare `"name"` string.
 * Lenient on purpose: this list is decoded as part of the whole AGENT_PROFILE
 * frame, so one unexpected element shape would otherwise take the model and
 * tools down with it — which is exactly the bug a `List<String>` here caused.
 * A shape we did not anticipate degrades to name-only, never to a dropped frame.
 */
object SkillWireSerializer : KSerializer<SkillWire> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ai.openonion.oochat.data.protocol.SkillWire") {
            element<String>("name")
            element<String?>("description", isOptional = true)
        }

    override fun deserialize(decoder: Decoder): SkillWire {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("SkillWire can only be read from JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> SkillWire(name = element.contentOrNull.orEmpty())
            is JsonObject -> SkillWire(
                name = (element["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                description = (element["description"] as? JsonPrimitive)?.contentOrNull
            )
            // An array or anything else: no name to salvage, so it becomes a
            // blank entry the parser drops.
            else -> SkillWire(name = "")
        }
    }

    override fun serialize(encoder: Encoder, value: SkillWire) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("SkillWire can only be written as JSON")
        output.encodeJsonElement(
            buildJsonObject {
                put("name", value.name)
                value.description?.let { put("description", it) }
            }
        )
    }
}
