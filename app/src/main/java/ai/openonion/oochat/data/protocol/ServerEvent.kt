package ai.openonion.oochat.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Server events received via WebSocket.
 */
@Serializable
data class ServerEvent(
    val type: String,
    val result: String? = null,
    @SerialName("input_id") val inputId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val session: SessionState? = null,
    val message: String? = null,
    val error: String? = null,
    val id: String? = null,
    @SerialName("tool_id") val toolId: String? = null,
    val name: String? = null,
    // JsonElement, not String: the server sends tool args with their native
    // JSON types (int/bool/etc — e.g. bash's `timeout: int`), and this Json
    // instance is strict (no isLenient). A String-typed map here throws a
    // hard deserialization failure on any frame carrying a non-string arg,
    // silently dropping the whole event — observed on approval_needed
    // frames, which then never render, deadlocking the agent in
    // io.receive(). ProtocolParser converts to a display-friendly
    // Map<String, String> downstream (ChatItem.ToolCall.args stays String).
    val args: Map<String, JsonElement>? = null,
    val status: String? = null,
    val model: String? = null,
    @SerialName("duration_ms") val durationMs: Double? = null,
    val content: String? = null,
    val kind: String? = null,
    val text: String? = null,
    // "ask_user" event's actual field — see ask_user.py's event payload.
    // `text` above is unused by the server for this event but kept as a
    // fallback in ProtocolParser.
    val question: String? = null,
    val options: List<String>? = null,
    @SerialName("multi_select") val multiSelect: Boolean? = null,
    val tool: String? = null,
    // See [args] above — same strict-decode hazard, same fix.
    val arguments: Map<String, JsonElement>? = null,
    val description: String? = null,
    val methods: List<String>? = null,
    @SerialName("payment_amount") val paymentAmount: Double? = null,
    @SerialName("payment_address") val paymentAddress: String? = null,
    val level: String? = null,
    // "agent_image" event's payload — see agent.io.send_image() in the
    // ConnectOnion protocol: a URL (typically) or base64 data URL.
    val image: String? = null,
    // Preview of the next queued tool calls in the current approval batch,
    // sent alongside "approval_needed" — see oo-chat-web's
    // ApprovalNeededUI.batch_remaining (components/chat/types.ts) and
    // [BatchToolPreview] for the entry shape.
    @SerialName("batch_remaining") val batchRemaining: List<BatchToolPreview>? = null,
    // "intent" event fields — see oo-chat-web's IntentUI (components/chat/types.ts).
    val ack: String? = null,
    @SerialName("is_build") val isBuild: Boolean? = null,
    // "eval" event fields — see oo-chat-web's EvalUI.
    val passed: Boolean? = null,
    val summary: String? = null,
    val expected: String? = null,
    @SerialName("eval_path") val evalPath: String? = null,
    // "compact" event fields — see oo-chat-web's CompactUI.
    // Double, not Int: auto_compact.py sends agent.context_percent, a float
    // percentage (0-100, e.g. 32.951), not a whole number. Int here throws a
    // hard deserialization failure on any real compact frame, dropping the
    // whole event and leaving the UI stuck on "compacting".
    @SerialName("context_before") val contextBefore: Double? = null,
    @SerialName("context_after") val contextAfter: Double? = null,
    // Same 0-100 float scale as context_before/after above — core/agent.py
    // computes all three as input_tokens / limit * 100. Int here throws a
    // hard deserialization failure, silently dropping the whole event.
    @SerialName("context_percent") val contextPercent: Double? = null,
    // "tool_blocked" event fields — see oo-chat-web's ToolBlockedUI.
    val reason: String? = null,
    val command: String? = null,
    // "ulw_turns_reached" event fields — see oo-chat-web's UlwTurnsReachedUI.
    @SerialName("turns_used") val turnsUsed: Int? = null,
    @SerialName("max_turns") val maxTurns: Int? = null,
    // "plan_review" event field — see oo-chat-web's PlanReviewUI.
    @SerialName("plan_content") val planContent: String? = null,
    // "files_received" event field — see oo-chat-web's FilesReceivedUI.
    val files: List<ReceivedFileWire>? = null,
    // "llm_result" event's token-usage payload — see core/usage.py's
    // TokenUsage pydantic model (agent.py's _get_llm_decision sends
    // response.usage.model_dump()). Only the two counts this client
    // displays are declared; ignoreUnknownKeys drops the rest
    // (cached_tokens, cache_write_tokens, cost) rather than failing on them.
    val usage: TokenUsageWire? = null,
    // "diff_preview" event fields — see diff_writer.py's _send_preview
    // (useful_tools/diff_writer.py, DiffWriter.write()). Purely
    // informational, sent right before the paired ask_user/approval_needed
    // request for the same write — does not block or expect a response.
    val path: String? = null,
    val preview: String? = null,
    val truncated: Boolean? = null,
    @SerialName("file_exists") val fileExists: Boolean? = null,
    // "AGENT_PROFILE" event fields — see ws_router/connect.py (~line 147):
    // sent once, right after CONNECT clears the trust gate. A superset of
    // the public /info endpoint — `skills` only exists on the operator's
    // own machine and is never in /info. `name`/`model` above are reused
    // (same field names, same meaning) rather than duplicated here.
    val address: String? = null,
    val tools: List<String>? = null,
    // Objects, not strings: each entry is {name, description} — see
    // [SkillWire], whose serializer also accepts a bare string. A String-typed
    // list here failed to decode any skills-bearing frame and dropped the
    // whole profile (model and tools included) as Unknown.
    val skills: List<SkillWire>? = null,
    // Only present when the server's balance is non-None — see connect.py's
    // comment on the same field. Not the account-balance card's source
    // (that's a different, account-scoped balance) — ProtocolParser/UI
    // deliberately ignore this field for now, see AgentLiveProfile's doc.
    @SerialName("balance_usd") val balanceUsd: Double? = null,
    // "DASHBOARD_SNAPSHOT" event field — the agent's own `dashboard.html`,
    // read off disk by the host and pushed whole (see ws_router/dashboard.py's
    // read_dashboard_snapshot). Agent-authored and therefore untrusted; it is
    // never parsed or rewritten here, only wrapped — see
    // ai.openonion.oochat.ui.dashboard.DashboardDocument.
    val html: String? = null,
    // "mode_changed" event fields — sent whenever a plugin flips
    // agent.current_session['mode'] (ulw.py's ULW enter/exit, tool_approval's
    // _set_mode, plan_mode.py's enter/exit). `mode` is one of "safe" |
    // "plan" | "accept_edits" | "ulw"; `triggered_by` is one of "user" |
    // "agent" | "ulw_checkpoint", or absent entirely — plan_mode.py's
    // exit_plan_and_implement() sends `{'type':'mode_changed','mode':mode}`
    // with no triggered_by key at all.
    val mode: String? = null,
    @SerialName("triggered_by") val triggeredBy: String? = null
)

@Serializable
data class TokenUsageWire(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    // Fallback spellings. This server's core/usage.py TokenUsage sends only
    // the two above, but the reference web client reads total_tokens first
    // and prompt/completion_tokens second (status-bar.tsx) — an
    // OpenAI-shaped provider passing its usage through unchanged would
    // otherwise read as zero here.
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    /** USD for this one call — core/usage.py computes it from the model's rates. */
    val cost: Double? = null
)

/**
 * One queued follow-up call in an approval batch. `arguments` really is a
 * JSON-encoded string, not an object — approval.py's _get_batch_remaining
 * forwards the assistant message's `function.arguments`, which tool_executor
 * wrote with json.dumps. Unlike the top-level [ServerEvent.arguments], which
 * is a dict and had to be widened.
 *
 * Both fields are lenient and defaulted anyway: this list decodes inside the
 * `approval_needed` frame, and dropping that frame blocks the agent forever.
 */
@Serializable
data class BatchToolPreview(
    @Serializable(with = LenientStringSerializer::class) val tool: String = "",
    @Serializable(with = LenientStringSerializer::class) val arguments: String = ""
)

/**
 * One entry of a `files_received` frame — `{name, path}`, built by agent.py
 * from `Path(p).name` and `p`. Lenient and defaulted for the same reason as
 * [BatchToolPreview]: one malformed entry must not cost the whole frame.
 */
@Serializable
data class ReceivedFileWire(
    @Serializable(with = LenientStringSerializer::class) val name: String = "",
    @Serializable(with = LenientStringSerializer::class) val path: String = ""
)
