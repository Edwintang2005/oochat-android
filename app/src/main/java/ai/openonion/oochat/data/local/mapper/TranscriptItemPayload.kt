package ai.openonion.oochat.data.local.mapper

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk form of the [ChatItem] kinds that carry transcript. PersistenceTransaction
 * previously stored only User/Agent/Turn and dropped the rest, so a reopened
 * conversation lost every tool call.
 *
 * A separate DTO rather than `@Serializable` on [ChatItem]: that would force all
 * 18 subclasses to be serializable, and would tie stored rows to domain field
 * names. The `@SerialName`s here are the wire contract and must not change.
 *
 * An approval gate is stored **only once answered**, and its answer travels
 * with it. Restoring an open gate would offer buttons for a question the
 * server has already closed; restoring an answered one is just the record of
 * what the agent was allowed to do, which is transcript like any other.
 *
 * The remaining gates (AskUser, Onboard*, PlanReview, UlwTurnsReached) are
 * still excluded — none of them carries its answer on the item yet, so there
 * is nothing to restore them into.
 */
@Serializable
sealed class TranscriptItemPayload {
    abstract val id: String

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(
        override val id: String,
        val name: String,
        val args: Map<String, String>? = null,
        val status: String,
        val result: String? = null,
        val timingMs: Long? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("thinking")
    data class Thinking(
        override val id: String,
        val status: String,
        val model: String? = null,
        val durationMs: Double? = null,
        val content: String? = null,
        val tokensTotal: Int? = null,
        val costUsd: Double? = null,
        val contextPercent: Double? = null
    ) : TranscriptItemPayload()

    /**
     * A [ChatItem.Turn] carrying only `thinking` — the footer row the live
     * llm_call/llm_result pair produces under the server's frame id, with the
     * reply text living on a separate Turn under a content-derived id. Stored
     * as its own transcript row because the two are never joined: the ids
     * disagree by construction, and live renders them as two rows.
     */
    @Serializable
    @SerialName("turn_thinking")
    data class TurnThinking(
        override val id: String,
        val status: String,
        val model: String? = null,
        val durationMs: Double? = null,
        val content: String? = null,
        val tokensTotal: Int? = null,
        val costUsd: Double? = null,
        val contextPercent: Double? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("intent")
    data class Intent(
        override val id: String,
        val status: String,
        val ack: String? = null,
        val isBuild: Boolean? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("eval")
    data class Eval(
        override val id: String,
        val status: String,
        val passed: Boolean? = null,
        val summary: String? = null,
        val expected: String? = null,
        val evalPath: String? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("compact")
    data class Compact(
        override val id: String,
        val status: String,
        val contextBefore: Double? = null,
        val contextAfter: Double? = null,
        val contextPercent: Double? = null,
        val message: String? = null,
        val error: String? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("tool_blocked")
    data class ToolBlocked(
        override val id: String,
        val tool: String,
        val reason: String,
        val message: String,
        val command: String? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("approval")
    data class Approval(
        override val id: String,
        val tool: String,
        val arguments: Map<String, String>,
        val description: String? = null,
        val approved: Boolean,
        val scope: String,
        val mode: String? = null,
        val feedback: String? = null
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("files_received")
    data class FilesReceived(
        override val id: String,
        val files: List<File>
    ) : TranscriptItemPayload() {
        @Serializable
        data class File(val name: String, val path: String)
    }

    @Serializable
    @SerialName("diff_preview")
    data class DiffPreview(
        override val id: String,
        val path: String,
        val preview: String,
        val truncated: Boolean = false,
        val fileExists: Boolean = true
    ) : TranscriptItemPayload()

    @Serializable
    @SerialName("mode_changed")
    data class ModeChanged(
        override val id: String,
        val mode: String,
        val triggeredBy: String? = null
    ) : TranscriptItemPayload()
}

/**
 * Reads and writes [TranscriptItemPayload] rows. Decode never throws: a row from
 * a newer build, or a corrupt one, degrades to "this item didn't come back"
 * rather than taking the whole reload down.
 */
object TranscriptItemCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    /**
     * `item_type` for an assistant row that also carries a [ChatItem.Turn]'s
     * thinking metadata in `payload`. Unlike every other item_type, the row
     * keeps its real `role`/`content` — the payload annotates the message
     * rather than replacing it, so it stays searchable and counted.
     */
    const val TURN_ITEM_TYPE = "Turn"

    /**
     * A turn's footer metadata, reusing the [TranscriptItemPayload.Thinking]
     * wire shape. Null when the item says nothing a footer could show, so a
     * reloaded turn renders no footer rather than a bare "Done".
     */
    fun encodeTurnThinking(thinking: ChatItem.Thinking): String? {
        if (!worthStoring(thinking)) return null
        return encode(toPayload(thinking) ?: return null)
    }

    /**
     * False for a finished turn that reports nothing — storing it would
     * restore a footer reading just "Done", which says less than no footer.
     * A non-DONE status is always worth keeping: "Failed" stands on its own.
     */
    private fun worthStoring(thinking: ChatItem.Thinking): Boolean =
        thinking.status != ThinkingStatus.DONE ||
            thinking.model != null || thinking.durationMs != null ||
            thinking.tokensTotal != null || thinking.costUsd != null ||
            thinking.contextPercent != null

    /** Null on any row that isn't a decodable turn payload — see [decode]. */
    fun decodeTurnThinking(raw: String): ChatItem.Thinking? = decode(raw) as? ChatItem.Thinking

    /** Null for anything not worth persisting — see the class doc's gate rationale. */
    fun toPayload(item: ChatItem): TranscriptItemPayload? = when (item) {
        is ChatItem.ToolCall -> TranscriptItemPayload.ToolCall(
            id = item.id,
            name = item.name,
            args = item.args,
            status = item.status.name,
            result = item.result,
            timingMs = item.timingMs
        )
        is ChatItem.Thinking -> TranscriptItemPayload.Thinking(
            id = item.id,
            status = item.status.name,
            model = item.model,
            durationMs = item.durationMs,
            content = item.content,
            tokensTotal = item.tokensTotal,
            costUsd = item.costUsd,
            contextPercent = item.contextPercent
        )
        // A turn that is only a footer (no reply text of its own) — the live
        // llm_call/llm_result row. RUNNING is skipped: llm_result replaces it
        // moments later under the same id, and a process death in between
        // would otherwise restore a spinner that can never finish.
        is ChatItem.Turn -> item.thinking
            ?.takeIf { item.agent == null && it.status != ThinkingStatus.RUNNING && worthStoring(it) }
            ?.let { t ->
                TranscriptItemPayload.TurnThinking(
                    id = item.id,
                    status = t.status.name,
                    model = t.model,
                    durationMs = t.durationMs,
                    content = t.content,
                    tokensTotal = t.tokensTotal,
                    costUsd = t.costUsd,
                    contextPercent = t.contextPercent
                )
            }
        is ChatItem.IntentItem -> TranscriptItemPayload.Intent(
            id = item.id,
            status = item.status.name,
            ack = item.ack,
            isBuild = item.isBuild
        )
        is ChatItem.EvalItem -> TranscriptItemPayload.Eval(
            id = item.id,
            status = item.status.name,
            passed = item.passed,
            summary = item.summary,
            expected = item.expected,
            evalPath = item.evalPath
        )
        is ChatItem.CompactItem -> TranscriptItemPayload.Compact(
            id = item.id,
            status = item.status.name,
            contextBefore = item.contextBefore,
            contextAfter = item.contextAfter,
            contextPercent = item.contextPercent,
            message = item.message,
            error = item.error
        )
        is ChatItem.ToolBlockedItem -> TranscriptItemPayload.ToolBlocked(
            id = item.id,
            tool = item.tool,
            reason = item.reason,
            message = item.message,
            command = item.command
        )
        // Answered gates only — see the class doc.
        is ChatItem.ApprovalNeeded -> item.decision?.let { d ->
            TranscriptItemPayload.Approval(
                id = item.id,
                tool = item.tool,
                arguments = item.arguments,
                description = item.description,
                approved = d.approved,
                scope = d.scope,
                mode = d.mode,
                feedback = d.feedback
            )
        }
        is ChatItem.FilesReceivedItem -> TranscriptItemPayload.FilesReceived(
            id = item.id,
            files = item.files.map { TranscriptItemPayload.FilesReceived.File(it.name, it.path) }
        )
        is ChatItem.DiffPreviewItem -> TranscriptItemPayload.DiffPreview(
            id = item.id,
            path = item.path,
            preview = item.preview,
            truncated = item.truncated,
            fileExists = item.fileExists
        )
        is ChatItem.ModeChangedItem -> TranscriptItemPayload.ModeChanged(
            id = item.id,
            mode = item.mode,
            triggeredBy = item.triggeredBy
        )
        else -> null
    }

    // Explicit serializer: the reified overload picks the concrete subclass and
    // omits the "kind" discriminator, making the row undecodable on the way back.
    fun encode(payload: TranscriptItemPayload): String =
        json.encodeToString(TranscriptItemPayload.serializer(), payload)

    /** Null on any malformed/unrecognised row, so a bad row is skipped, not fatal. */
    fun decode(raw: String): ChatItem? =
        runCatching { json.decodeFromString<TranscriptItemPayload>(raw) }
            .getOrNull()
            ?.let(::toChatItem)

    /** Unknown enum names fall back to the terminal state — showing the item with
     * a settled status beats dropping it from the transcript. */
    private fun toChatItem(payload: TranscriptItemPayload): ChatItem = when (payload) {
        is TranscriptItemPayload.ToolCall -> ChatItem.ToolCall(
            id = payload.id,
            name = payload.name,
            args = payload.args,
            status = enumOrDefault(payload.status, ToolStatus.DONE),
            result = payload.result,
            timingMs = payload.timingMs
        )
        is TranscriptItemPayload.Thinking -> ChatItem.Thinking(
            id = payload.id,
            status = enumOrDefault(payload.status, ThinkingStatus.DONE),
            model = payload.model,
            durationMs = payload.durationMs,
            content = payload.content,
            tokensTotal = payload.tokensTotal,
            costUsd = payload.costUsd,
            contextPercent = payload.contextPercent
        )
        is TranscriptItemPayload.TurnThinking -> ChatItem.Turn(
            id = payload.id,
            thinking = ChatItem.Thinking(
                id = payload.id,
                status = enumOrDefault(payload.status, ThinkingStatus.DONE),
                model = payload.model,
                durationMs = payload.durationMs,
                content = payload.content,
                tokensTotal = payload.tokensTotal,
                costUsd = payload.costUsd,
                contextPercent = payload.contextPercent
            )
        )
        is TranscriptItemPayload.Intent -> ChatItem.IntentItem(
            id = payload.id,
            status = enumOrDefault(payload.status, IntentStatus.UNDERSTOOD),
            ack = payload.ack,
            isBuild = payload.isBuild
        )
        is TranscriptItemPayload.Eval -> ChatItem.EvalItem(
            id = payload.id,
            status = enumOrDefault(payload.status, EvalStatus.DONE),
            passed = payload.passed,
            summary = payload.summary,
            expected = payload.expected,
            evalPath = payload.evalPath
        )
        is TranscriptItemPayload.Compact -> ChatItem.CompactItem(
            id = payload.id,
            status = enumOrDefault(payload.status, CompactStatus.DONE),
            contextBefore = payload.contextBefore,
            contextAfter = payload.contextAfter,
            contextPercent = payload.contextPercent,
            message = payload.message,
            error = payload.error
        )
        is TranscriptItemPayload.ToolBlocked -> ChatItem.ToolBlockedItem(
            id = payload.id,
            tool = payload.tool,
            reason = payload.reason,
            message = payload.message,
            command = payload.command
        )
        is TranscriptItemPayload.Approval -> ChatItem.ApprovalNeeded(
            id = payload.id,
            tool = payload.tool,
            arguments = payload.arguments,
            description = payload.description,
            decision = ApprovalDecision(payload.approved, payload.scope, payload.mode, payload.feedback)
        )
        is TranscriptItemPayload.FilesReceived -> ChatItem.FilesReceivedItem(
            id = payload.id,
            files = payload.files.map { ReceivedFile(it.name, it.path) }
        )
        is TranscriptItemPayload.DiffPreview -> ChatItem.DiffPreviewItem(
            id = payload.id,
            path = payload.path,
            preview = payload.preview,
            truncated = payload.truncated,
            fileExists = payload.fileExists
        )
        is TranscriptItemPayload.ModeChanged -> ChatItem.ModeChangedItem(
            id = payload.id,
            mode = payload.mode,
            triggeredBy = payload.triggeredBy
        )
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String, fallback: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(fallback)
}
