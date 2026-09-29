package ai.openonion.oochat.domain.model

/**
 * Chat UI item types displayed in the message list.
 */
/**
 * How far one of the user's own messages got.
 *
 * [SENT] is the default so anything reloaded from Room — where only delivered
 * messages are ever written — reads as delivered without a migration.
 */
enum class UserMessageState {
    /** On the wire. Nothing more is owed to the user about it. */
    SENT,

    /**
     * Accepted locally but not yet delivered: typed while a turn was running,
     * or written while the socket was down and waiting in the outbox.
     */
    QUEUED,

    /**
     * Delivery failed and will not be retried on its own. The bubble says so
     * and offers to send it again — the only state the user has to act on.
     */
    FAILED
}

sealed class ChatItem {
    abstract val id: String

    data class User(
        override val id: String,
        val content: String,
        val images: List<String>? = null,
        // Non-image SAF documents attached to this message — see
        // ChatFileAttachment's own doc for why this needs a name alongside
        // the path, unlike [images].
        val files: List<ChatFileAttachment>? = null,
        /** How far this message got. See [UserMessageState]. */
        val state: UserMessageState = UserMessageState.SENT
    ) : ChatItem() {
        /**
         * Waiting on a running turn, so on screen but not yet on the wire.
         * Also the tail the reducer inserts before — the running turn is still
         * emitting tool calls and its answer, and those belong above a message
         * that has not been sent yet.
         */
        val queued: Boolean get() = state == UserMessageState.QUEUED
    }

    data class Agent(
        override val id: String,
        val content: String,
        // Remote URLs (or occasionally base64 data URLs) sent back by the
        // agent via the "agent_image" server event — see ProtocolParser.
        val images: List<String>? = null
    ) : ChatItem() {
        /**
         * True for a standalone image-only Agent item; this shape also occurs
         * as the persisted form of a completed image-only turn, not just a
         * live marker.
         */
        val isImageOnlyMarker: Boolean get() = content.isBlank() && !images.isNullOrEmpty()
    }

    data class Thinking(
        override val id: String,
        val status: ThinkingStatus,
        val model: String? = null,
        val durationMs: Double? = null,
        val content: String? = null,
        // input_tokens + output_tokens from the paired llm_result frame's
        // `usage`. Null for a running/errored turn, or one from a build
        // before this was wired up.
        val tokensTotal: Int? = null,
        /** USD this turn's LLM call cost, when the server reports one. */
        val costUsd: Double? = null,
        // Context window occupancy at that same llm_result, as a 0.0-1.0
        // fraction (see ServerEvent.contextPercent's own doc for why this
        // isn't the 0-100 scale CompactItem's fields use).
        val contextPercent: Double? = null
    ) : ChatItem()

    /**
     * One complete assistant "turn": the model thought about it ([thinking])
     * and produced a reply ([agent]). Either side can be null while in flight;
     * stableAssistantId lets dedupeUI() collapse replays of the same turn.
     */
    data class Turn(
        override val id: String,
        val thinking: Thinking? = null,
        val agent: Agent? = null
    ) : ChatItem()

    data class ToolCall(
        override val id: String,
        val name: String,
        val args: Map<String, String>? = null,
        val status: ToolStatus = ToolStatus.RUNNING,
        val result: String? = null,
        val timingMs: Long? = null
    ) : ChatItem()

    data class AskUser(
        override val id: String,
        val text: String,
        val options: List<String>,
        val multiSelect: Boolean
    ) : ChatItem()

    data class ApprovalNeeded(
        override val id: String,
        val tool: String,
        val arguments: Map<String, String>,
        val description: String? = null,
        val batchRemaining: List<BatchToolPreview>? = null,
        /** Null while the gate is still open. Set once, by respondToApproval. */
        val decision: ApprovalDecision? = null
    ) : ChatItem()

    data class OnboardRequired(
        override val id: String,
        val methods: List<String>,
        val paymentAmount: Double? = null,
        val paymentAddress: String? = null
    ) : ChatItem()

    data class OnboardSuccess(
        override val id: String,
        val level: String,
        val message: String
    ) : ChatItem()

    /**
     * Server rejected an invite-code/payment attempt with [reason]. Shares
     * its [id] with the [OnboardRequired] it replaces so the reducer can
     * swap the cards in place — the chat list shows one card throughout
     * the gate lifecycle (required → failed → required → success), not a
     * growing list of one-shot error cards.
     */
    data class OnboardingFailed(
        override val id: String,
        val reason: String
    ) : ChatItem()

    /** Intent analysis result displayed to the user. */
    data class IntentItem(
        override val id: String,
        val status: IntentStatus,
        val ack: String? = null,
        val isBuild: Boolean? = null
    ) : ChatItem()

    /** Evaluation / scoring result for an agent turn or output. */
    data class EvalItem(
        override val id: String,
        val status: EvalStatus,
        val passed: Boolean? = null,
        val summary: String? = null,
        val expected: String? = null,
        val evalPath: String? = null
    ) : ChatItem()

    /** Context compression (compaction) progress/summary. */
    data class CompactItem(
        override val id: String,
        val status: CompactStatus,
        // Double, not Int: server sends a float percentage (0-100, e.g.
        // 32.951), not a whole number. Int here throws a deserialization
        // error that silently drops the whole enclosing event.
        val contextBefore: Double? = null,
        val contextAfter: Double? = null,
        val contextPercent: Double? = null,
        val message: String? = null,
        val error: String? = null
    ) : ChatItem()

    /** A tool invocation that was blocked by policy. */
    data class ToolBlockedItem(
        override val id: String,
        val tool: String,
        val reason: String,
        val message: String,
        val command: String? = null
    ) : ChatItem()

    /** Plan review / approval request before execution. [planContent] is a
     * raw text/markdown blob, not a structured step list — matches
     * oo-chat-web's PlanReviewUI wire shape (`plan_content: string`). */
    data class PlanReviewItem(
        override val id: String,
        val planContent: String
    ) : ChatItem()

    /** Notification that files were received from the agent. */
    data class FilesReceivedItem(
        override val id: String,
        val files: List<ReceivedFile>
    ) : ChatItem()

    /** Autonomous work loop has reached its turn limit. */
    data class UlwTurnsReachedItem(
        override val id: String,
        val turnsUsed: Int,
        val maxTurns: Int
    ) : ChatItem()

    /**
     * Preview of a pending file write, sent by diff_writer.py's
     * `_send_preview` right before the paired ask_user/approval_needed
     * request for the same write (see DiffWriter.write()). Purely
     * informational — carries no response of its own, so a plain approval
     * (blind to what it's approving) is the only thing the user sees today
     * without this card.
     */
    data class DiffPreviewItem(
        override val id: String,
        val path: String,
        val preview: String,
        val truncated: Boolean = false,
        val fileExists: Boolean = true
    ) : ChatItem()

    /**
     * The agent (or a plugin acting on its behalf) switched session mode —
     * a `mode_changed` server event. [mode] is one of "safe" | "plan" |
     * "accept_edits" | "ulw" (raw, not an enum: unlike [ThinkingStatus] etc.
     * this is free-form content the server may extend, not a fixed UI state
     * machine). [triggeredBy] is one of "user" | "agent" | "ulw_checkpoint"
     * when the server sends it — plan_mode.py's exit path omits the field
     * entirely, so it stays null rather than defaulting to a guess.
     *
     * Arrives both unprompted (a plugin switched on the agent's own
     * initiative) and as the acknowledgement of a `mode_change` this client
     * sent — see [ai.openonion.oochat.data.protocol.ModeChangeMessage].
     * Either way it is authoritative for
     * [ai.openonion.oochat.data.repository.ConnectionRepository.approvalMode],
     * which the mode chip renders.
     */
    data class ModeChangedItem(
        override val id: String,
        val mode: String,
        val triggeredBy: String? = null
    ) : ChatItem()
}

enum class ThinkingStatus { RUNNING, DONE, ERROR }
enum class ToolStatus { RUNNING, DONE, ERROR }
enum class IntentStatus { ANALYZING, UNDERSTOOD }
enum class EvalStatus { EVALUATING, DONE }
enum class CompactStatus { COMPACTING, DONE, ERROR }

/** A single file the agent sent back, as listed in a [ChatItem.FilesReceivedItem]. */
data class ReceivedFile(val name: String, val path: String)

/**
 * A non-image file the user attached, once it's been copied into app-private
 * storage — the [ChatItem.User.files] counterpart to [ChatItem.User.images].
 * Needs [name] (unlike images, files render as a name+icon chip, not a
 * thumbnail) alongside [path], a local file:// URI to the durable copy —
 * see [ai.openonion.oochat.data.local.FileAttachmentStore].
 */
data class ChatFileAttachment(val name: String, val path: String)

/** A queued tool call previewed in an [ChatItem.ApprovalNeeded]'s batch. */
data class BatchToolPreview(val tool: String, val arguments: String)

/**
 * What the user answered a [ChatItem.ApprovalNeeded] with, in the wire protocol's
 * own terms (`APPROVAL_RESPONSE.scope`/`mode`).
 *
 * Lives on the item so it survives both recomposition and a reload. Keeping it
 * in a composable's `remember` re-asked a settled question; keeping it in a
 * ViewModel-side map fixed that but still vanished when the app restarted.
 */
data class ApprovalDecision(
    val approved: Boolean,
    val scope: String,
    val mode: String?,
    // Free-text reason given on a rejection — see APPROVAL_RESPONSE.feedback.
    val feedback: String? = null
)

/**
 * True for an item that represents work still in flight — backs Settings'
 * "Streaming responses" toggle, which hides these while off (final/terminal
 * cards like AskUser or ApprovalNeeded always show regardless).
 */
fun ChatItem.isInProgress(): Boolean = when (this) {
    is ChatItem.Thinking -> status == ThinkingStatus.RUNNING
    is ChatItem.ToolCall -> status == ToolStatus.RUNNING
    // A missing reply only means "still running" while the footer says so: a
    // turn whose thinking is DONE/ERROR has finished, it just produced no text
    // of its own (an intermediate llm_call that emitted a tool call, say).
    // Reading those as in flight left a reloaded row disabling the composer.
    is ChatItem.Turn -> thinking?.status == ThinkingStatus.RUNNING ||
        (thinking == null && agent == null)
    is ChatItem.IntentItem -> status == IntentStatus.ANALYZING
    is ChatItem.EvalItem -> status == EvalStatus.EVALUATING
    is ChatItem.CompactItem -> status == CompactStatus.COMPACTING
    else -> false
}

/**
 * Session-wide token/cost/context totals for the status bar shown between
 * the message list and the input bar. Android's counterpart to oo-chat-web's
 * status-bar.tsx accumulator, adapted to this client's shape: web keeps
 * `thinking` items as flat entries in its `ui` array and filters for them;
 * this client only ever produces one nested inside a [ChatItem.Turn], so
 * [sessionUsageTotals] walks turns instead.
 */
data class SessionUsageTotals(
    val totalTokens: Int,
    val totalCostUsd: Double,
    /** The most recent non-null contextPercent seen, regardless of which
     *  turn's status it came from — matches web, which takes the last
     *  `context_percent` unconditionally rather than only from a done item. */
    val latestContextPercent: Double?
)

/**
 * Accumulates every [ChatItem.Turn]'s [ChatItem.Thinking] into session
 * totals. Only a [ThinkingStatus.DONE] item's tokens/cost are summed — same
 * rule as web's `item.status === 'done'` — while contextPercent is taken
 * from the latest item that has one at all, done or not, again matching web.
 */
fun List<ChatItem>.sessionUsageTotals(): SessionUsageTotals {
    var totalTokens = 0
    var totalCostUsd = 0.0
    var latestContextPercent: Double? = null

    for (item in this) {
        val thinking = (item as? ChatItem.Turn)?.thinking ?: continue
        if (thinking.contextPercent != null) {
            latestContextPercent = thinking.contextPercent
        }
        if (thinking.status == ThinkingStatus.DONE) {
            totalTokens += thinking.tokensTotal ?: 0
            totalCostUsd += thinking.costUsd ?: 0.0
        }
    }

    return SessionUsageTotals(totalTokens, totalCostUsd, latestContextPercent)
}

/**
 * Settles everything still dangling; used on user-interrupt and on every
 * OutputReceived. The live llm_call/llm_result/OUTPUT flow can leave a Turn's
 * placeholder permanently "running" due to an id mismatch (llm_result carries
 * a different id than the final OUTPUT).
 *
 * Deliberately settles a superset of [isInProgress]: a reply-less Turn is
 * closed out here even once its footer reads DONE, because OUTPUT *is* the
 * closing signal, and leaving it a merge target would let a later turn's
 * reply adopt this turn's footer.
 *
 * [closeTurns] false for the one caller where the turn is not over: an
 * interrupt is a request, and the agent still owes a closing message. Giving
 * the Turn an empty agent there hides it from [ChatEventHelpers
 * .mergeIntoPendingTurn], so that message arrives as a bubble of its own and
 * the footer it belongs to is stranded above it, holding the timing and token
 * count for a reply it no longer contains.
 */
fun List<ChatItem>.resolveRunningItems(closeTurns: Boolean = true): List<ChatItem> = map { item ->
    when {
        item is ChatItem.Thinking && item.status == ThinkingStatus.RUNNING ->
            item.copy(status = ThinkingStatus.DONE)
        item is ChatItem.Turn && (item.thinking?.status == ThinkingStatus.RUNNING || item.agent == null) ->
            item.copy(
                thinking = item.thinking?.copy(status = ThinkingStatus.DONE),
                agent = if (closeTurns) item.agent ?: ChatItem.Agent(id = item.id, content = "") else item.agent
            )
        item is ChatItem.ToolCall && item.status == ToolStatus.RUNNING ->
            item.copy(status = ToolStatus.DONE)
        item is ChatItem.IntentItem && item.status == IntentStatus.ANALYZING ->
            item.copy(status = IntentStatus.UNDERSTOOD)
        item is ChatItem.EvalItem && item.status == EvalStatus.EVALUATING ->
            item.copy(status = EvalStatus.DONE)
        item is ChatItem.CompactItem && item.status == CompactStatus.COMPACTING ->
            item.copy(status = CompactStatus.DONE)
        else -> item
    }
}

/**
 * Clears a "Failed" mark that the conversation itself disproves.
 *
 * A turn interrupted by a dropped socket is force-failed on the spot, and the
 * reply the server had already produced arrives afterwards in its replay — as
 * its own row, not merged onto the turn. Live, SESSION_STATUS resolves that.
 * A row persisted before it could is reloaded still saying Failed, directly
 * above the answer it claims never arrived, for the life of the conversation.
 *
 * Only an ERROR immediately followed by an assistant reply with content is
 * cleared: a turn that genuinely produced nothing must keep saying so, since
 * there Retry is the right move.
 */
fun List<ChatItem>.resolveFalseFailures(): List<ChatItem> = mapIndexed { index, item ->
    val answered = getOrNull(index + 1).let { next ->
        when (next) {
            is ChatItem.Agent -> next.content.isNotBlank()
            is ChatItem.Turn -> next.agent?.content?.isNotBlank() == true
            else -> false
        }
    }
    when {
        !answered -> item
        item is ChatItem.Thinking && item.status == ThinkingStatus.ERROR ->
            item.copy(status = ThinkingStatus.DONE)
        item is ChatItem.Turn && item.agent == null && item.thinking?.status == ThinkingStatus.ERROR ->
            item.copy(thinking = item.thinking.copy(status = ThinkingStatus.DONE))
        else -> item
    }
}
