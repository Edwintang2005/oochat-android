package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.util.FileLogger
import ai.openonion.oochat.util.LogTags
import ai.openonion.oochat.util.stringField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom

/** The app's MessagePort, by way of the parent page. */
fun interface ControlCenterPort {
    fun post(message: JsonObject)

    fun close() {}
}

/** Which chat an action runs in. Only [CURRENT] is supported for now. */
enum class ControlCenterConversation(val wire: String) {
    CURRENT("current"),
    NEW("new")
}

/**
 * What the app may ask of the chat. Each runs one visible user turn and returns the session
 * it ran in, or throws [ControlCenterActionRejected].
 */
interface ControlCenterActions {
    suspend fun sendMessage(message: String, conversation: ControlCenterConversation): String
    suspend fun runSkill(skill: String, args: String?, conversation: ControlCenterConversation): String
}

/**
 * The user message a `run_skill` becomes. Arguments are kept on one line, as in
 * DashboardSkillIntent, so the app cannot add text below the command.
 */
fun controlCenterSkillMessage(skill: String, args: String?): String {
    val line = args.orEmpty().trim().replace(Regex("\\s+"), " ")
    return if (line.isEmpty()) "/$skill" else "/$skill $line"
}

/**
 * How an action is cancelled when the app sends `cancel`, so the chat can tell it from the
 * page simply closing: the first stops the turn, the second leaves it running in the chat.
 */
class ControlCenterCancelled : CancellationException("Cancelled by the Control Center app")

/** Sent to the app as the message of an `action_rejected` response. */
class ControlCenterActionRejected(message: String) : Exception(message)

/**
 * The chat's side of the Control Center bridge, one per app load. A port of
 * `createControlCenterHost` in connectonion-react's `src/control-center.ts`.
 *
 * Messages that do not carry this bridge version, [revision] and [epoch] are ignored, so a
 * port from an earlier load cannot act. Requests come from the agent's code and are checked
 * here before reaching [actions].
 *
 * Not thread-safe: use it from one thread, as the WebView's callbacks are.
 */
class ControlCenterHost(
    private val revision: String,
    private val epoch: String,
    snapshot: ControlCenterSnapshot,
    private val port: ControlCenterPort,
    private val actions: ControlCenterActions,
    private val scope: CoroutineScope
) {
    private var state: JsonObject
    private var sequence = 0L
    private var closed = false
    private val seen = HashSet<String>()
    private val pending = HashMap<String, Job>()

    init {
        require(REVISION.matches(revision) && ID.matches(epoch)) { "Invalid Control Center bridge scope" }
        state = sendable(snapshot)
        sendSnapshot()
    }

    /** [raw] is the app's message as JSON text, checked for size and nesting before parsing. */
    fun onMessage(raw: String) {
        if (closed || utf8Length(raw) > MAX_FRAME_BYTES || !nestsAtMost(raw, MAX_DEPTH)) return
        val value = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return
        if (!isScoped(value)) return
        when (value.stringField("type")) {
            PREFIX + "resnapshot" -> sendSnapshot()
            PREFIX + "cancel" -> value.stringField("id")?.let { pending.remove(it)?.cancel(ControlCenterCancelled()) }
            PREFIX + "request" -> onRequest(value)
        }
    }

    fun publish(next: ControlCenterSnapshot) {
        if (closed) return
        state = sendable(next)
        send(PREFIX + "event") { put("sequence", ++sequence); put("snapshot", state) }
    }

    /** Cancels pending actions, closes the port and sends nothing more. */
    fun dispose() {
        closed = true
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
        port.close()
    }

    private fun onRequest(value: JsonObject) {
        val id = value.stringField("id")?.takeIf { ID.matches(it) } ?: return
        if (id in seen) return respond(id, error = "Duplicate Control Center request")
        if (seen.size >= MAX_SEEN || pending.size >= MAX_PENDING) {
            return respond(id, error = "Bridge request capacity reached; reload the app")
        }
        seen += id
        if (state.stringField("connectionState") != ControlCenterConnection.CONNECTED.wire) {
            return respond(id, error = "Agent connection is not ready")
        }
        val payload = value["payload"] as? JsonObject ?: return respond(id, error = "Invalid action payload")
        val conversation = when (val target = payload["conversation"]) {
            null, JsonNull -> ControlCenterConversation.CURRENT
            else -> ControlCenterConversation.entries.firstOrNull {
                target is JsonPrimitive && target.isString && target.content == it.wire
            } ?: return respond(id, error = "Invalid conversation target")
        }
        val action: suspend () -> String = when (value.stringField("action")) {
            "send_message" -> {
                val message = payload.stringField("message")?.trim().orEmpty()
                if (message.isEmpty() || message.length > MAX_MESSAGE_CHARS) {
                    return respond(id, error = "Message must contain 1-$MAX_MESSAGE_CHARS characters")
                }
                { actions.sendMessage(message, conversation) }
            }
            "run_skill" -> {
                val skill = payload.stringField("skill")
                if (skill == null || !SKILL_NAME.matches(skill) || !isPublished(skill)) {
                    return respond(id, error = "Agent does not publish that skill")
                }
                // As in the SDK, an explicit null is invalid; only a missing field means none.
                val args = payload["args"]
                if (args != null && !(args is JsonPrimitive && args.isString && args.content.length <= MAX_ARGS_CHARS)) {
                    return respond(id, error = "Invalid skill arguments")
                }
                { actions.runSkill(skill, (args as? JsonPrimitive)?.content, conversation) }
            }
            else -> return respond(id, error = "Unsupported Control Center action")
        }
        // Registered before it starts: on Main.immediate an action that refuses without
        // suspending would otherwise finish first, and its refusal would never be sent.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = try {
                Result.success(action())
            } catch (e: CancellationException) {
                // Only this job's own cancellation goes unanswered; one thrown by the action is a failure.
                if (!isActive) throw e
                FileLogger.e(LogTags.CONTROL_CENTER, "Action cancelled itself: ${e.message}")
                Result.failure(ControlCenterActionRejected("Control Center action failed"))
            } catch (e: ControlCenterActionRejected) {
                Result.failure(e)
            } catch (e: Exception) {
                FileLogger.e(LogTags.CONTROL_CENTER, "Action failed: ${e.message}")
                Result.failure(ControlCenterActionRejected("Control Center action failed"))
            }
            if (pending.remove(id) != null && !closed) {
                outcome.fold(
                    onSuccess = { respond(id, sessionId = it) },
                    onFailure = { respond(id, error = it.message ?: "Control Center action failed") }
                )
            }
        }
        pending[id] = job
        // Also covers a job that never runs because the scope is already cancelled.
        job.invokeOnCompletion { pending.remove(id) }
        job.start()
    }

    private fun isPublished(skill: String): Boolean =
        (state["skills"] as? JsonArray).orEmpty()
            .any { (it as? JsonObject)?.stringField("name") == skill }

    private fun respond(id: String, sessionId: String? = null, error: String? = null) =
        send(PREFIX + "response") {
            put("id", id)
            put("ok", error == null)
            if (error == null) {
                put("result", buildJsonObject { put("sessionId", sessionId) })
            } else {
                put(
                    "error",
                    buildJsonObject {
                        put("code", "action_rejected")
                        put("message", error)
                    }
                )
            }
        }

    private fun sendSnapshot() =
        send(PREFIX + "snapshot") { put("sequence", ++sequence); put("snapshot", state) }

    private fun send(type: String, fields: JsonObjectBuilder.() -> Unit) {
        if (closed) return
        port.post(
            buildJsonObject {
                put("version", BRIDGE_VERSION)
                put("revision", revision)
                put("epoch", epoch)
                put("type", type)
                fields()
            }
        )
    }

    private fun isScoped(value: JsonObject): Boolean {
        val version = value["version"] as? JsonPrimitive
        return version != null && !version.isString && version.intOrNull == BRIDGE_VERSION &&
            value.stringField("revision") == revision &&
            value.stringField("epoch") == epoch
    }

    /** The snapshot as sent. One over the size limit is sent without its skills and chat items. */
    private fun sendable(snapshot: ControlCenterSnapshot): JsonObject {
        val json = snapshot.toJson()
        if (fits(json)) return json
        FileLogger.w(LogTags.CONTROL_CENTER, "Snapshot over ${MAX_FRAME_BYTES}B sent without skills and chat items")
        val dropped = snapshot.truncated || snapshot.chatItems.isNotEmpty()
        return snapshot.copy(skills = emptyList(), chatItems = emptyList(), truncated = dropped).toJson()
    }

    companion object {
        const val BRIDGE_VERSION = 1
        const val PREFIX = "connectonion.control-center/"

        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val MAX_PENDING = 16
        const val MAX_SEEN = 512
        const val MAX_MESSAGE_CHARS = 10_000
        const val MAX_ARGS_CHARS = 2_000

        /** Bridge messages are a few levels deep; deeper ones are refused before parsing. */
        const val MAX_DEPTH = 32

        private val REVISION = Regex("^sha256:[a-f0-9]{64}$")
        private val ID = Regex("^[A-Za-z0-9._:-]{1,128}$")
        private val SKILL_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private val random = SecureRandom()

        fun newEpoch(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        private fun fits(value: JsonObject): Boolean = utf8Length(value.toString()) <= MAX_FRAME_BYTES

        /** UTF-8 size of [text] without encoding a copy of it, which for a snapshot can be megabytes. */
        internal fun utf8Length(text: String): Int {
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    // A valid pair is one 4-byte character; a lone surrogate encodes as "?".
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { i++; 4 }
                    c.isSurrogate() -> 1
                    else -> 3
                }
                i++
            }
            return bytes
        }

        /** True when no array or object in [raw] is nested deeper than [max]. Parsing deeper JSON can overflow the stack. */
        private fun nestsAtMost(raw: String, max: Int): Boolean {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in raw) {
                when {
                    escaped -> escaped = false
                    inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' || c == '[' -> if (++depth > max) return false
                    c == '}' || c == ']' -> depth--
                }
            }
            return true
        }
    }
}
