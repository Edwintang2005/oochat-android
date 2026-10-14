package ai.openonion.oochat.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [key]'s value when it is a JSON string; null when it is missing, null, a number or a boolean. */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
