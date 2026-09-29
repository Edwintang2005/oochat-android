package ai.openonion.oochat.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outgoing WebSocket messages sent to the server.
 * Uses kotlinx.serialization for consistent JSON handling.
 */

@Serializable
data class ConnectMessage(
    val type: String = "CONNECT",
    val payload: ConnectPayload,
    val from: String,
    val signature: String,
    val timestamp: Long,
    val to: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val session: SessionState? = null
)

@Serializable
data class ConnectPayload(
    val timestamp: Long,
    val to: String? = null
)

@Serializable
data class InputMsg(
    val type: String = "INPUT",
    @SerialName("input_id") val inputId: String,
    val prompt: String,
    val to: String? = null,
    // Base64 data URLs, e.g. "data:image/jpeg;base64,...". See
    // ConnectOnion's websocket-protocol.md INPUT reference.
    val images: List<String>? = null,
    // Verified against the server's ws_router/agent_io.py (reads
    // data.get("files")) and network/connect.py:196
    // (files: Optional[List[Dict[str, Any]]]).
    val files: List<FileAttachment>? = null
)

/**
 * One file attached to an [InputMsg]. [data] is a base64 data URL, e.g.
 * "data:application/pdf;base64,..." — same encoding [InputMsg.images] uses,
 * just with an arbitrary mime type instead of always image/jpeg.
 */
@Serializable
data class FileAttachment(
    val name: String,
    val data: String
)

@Serializable
data class PongMessage(
    val type: String = "PONG"
)

/**
 * Ask the agent to gracefully stop its current run: it finishes the current
 * step and returns a closing message rather than stopping mid-tool-call.
 * Wire shape verified against oo-chat-web's components/chat/use-agent-sdk.ts
 * `interrupt()`, which sends exactly `{"type":"INTERRUPT"}` — no payload.
 */
@Serializable
data class InterruptMessage(
    val type: String = "INTERRUPT"
)

@Serializable
data class AskUserResponse(
    val type: String = "ASK_USER_RESPONSE",
    val answer: String
)

@Serializable
data class ApprovalResponse(
    val type: String = "APPROVAL_RESPONSE",
    val approved: Boolean,
    val scope: String = "once",
    // "reject_soft" | "reject_hard" | "reject_explain" — only meaningful
    // when approved == false. See oo-chat-web's use-agent-sdk.ts
    // respondToApproval for the reference wire shape.
    val mode: String? = null,
    // Free-text reason the LLM sees when a tool is rejected — see
    // oo-chat-web's use-agent-sdk.ts respondToApproval (only sent when
    // non-empty: `...(feedback && { feedback })`) and the server's
    // useful_plugins/tool_approval/approval.py doc, whose "rejected" wire
    // examples all carry this field. Only meaningful when approved == false;
    // the encoder (explicitNulls = false) drops it from the frame entirely
    // when null, matching the reference's conditional-spread behavior.
    val feedback: String? = null
)

/**
 * Reply to a server-emitted ONBOARD_REQUIRED gate.
 *
 * Sent after the user supplies either an invite code or confirms a payment
 * via the in-chat OnboardRequired card. Ed25519-signed like [ConnectMessage]
 * — the server requires a valid signature on every ONBOARD_SUBMIT even at
 * trust="open" (see connectonion's docs/features/trust.md and
 * network/trust/ws_admin.py's handle_onboard_submit, which calls the same
 * signed-request auth path CONNECT uses). The wire `type` must be exactly
 * "ONBOARD_SUBMIT" — the server's WS router only recognizes that literal;
 * anything else is silently dropped, not rejected with an ERROR frame.
 *
 * Note: no top-level `timestamp` here. The server's
 * `ws_admin.py::handle_onboard_submit` only reads `payload.invite_code` /
 * `payload.payment` and verifies the signature against `payload`
 * canonicalization — the envelope-level timestamp on [ConnectMessage] was
 * already redundant and the Python reference SDK
 * (`network/connect.py::_build_onboard_submit`) omits it here too. Carrying
 * both envelopes AND letting them drift was a real foot-gun; this matches
 * the wire shape the reference produces byte-for-byte.
 */
@Serializable
data class OnboardSubmitMessage(
    val type: String = "ONBOARD_SUBMIT",
    val payload: OnboardSubmitPayload,
    val from: String,
    val signature: String
)

@Serializable
data class OnboardSubmitPayload(
    val timestamp: Long,
    @SerialName("invite_code") val inviteCode: String? = null,
    val payment: Double? = null
)

/**
 * Reply to a server-emitted "plan_review" gate. Wire shape verified against
 * oo-chat-web's components/chat/use-agent-sdk.ts respondToPlanReview,
 * which sends `{"type":"PLAN_REVIEW_RESPONSE","message":<text>}` — the
 * server treats this as a free-text follow-up message, not a structured
 * accept/reject payload.
 */
@Serializable
data class PlanReviewResponse(
    val type: String = "PLAN_REVIEW_RESPONSE",
    val message: String
)

/**
 * Reply to a server-emitted "ulw_turns_reached" checkpoint. Wire shape
 * verified against oo-chat-web's components/chat/use-agent-sdk.ts
 * respondToUlwTurnsReached: `action` is "continue" or "switch_mode";
 * `turns` is only meaningful when continuing, `mode` only when switching
 * (one of "safe" | "plan" | "accept_edits" | "ulw").
 */
@Serializable
data class UlwResponse(
    val type: String = "ULW_RESPONSE",
    val action: String,
    val turns: Int? = null,
    val mode: String? = null
)

/**
 * Ask the agent to switch its tool-approval mode.
 *
 * Wire shape verified against the server, not against the web client: the
 * frame is forwarded verbatim from the socket into the agent's mailbox
 * (network/host/ws_router/session.py:131-134, the `elif active_io` fallthrough
 * that names `mode_change` in its own comment), then drained by
 * useful_plugins/tool_approval/approval.py's `poll_mode_changes`:
 * `agent.io.receive_all('mode_change')` (:895) reads `msg.get('mode')` (:896)
 * and, for the ULW branch, `msg.get('turns')` (:901). Lower-case `mode_change`
 * — unlike the SCREAMING_CASE router-level types (INPUT/CONNECT/EXEC), this one
 * is a plugin-level message and the literal is matched exactly.
 *
 * [mode] is one of "safe" | "plan" | "accept_edits" (the plugin's own
 * `VALID_MODES`, tool_approval/constants.py:44) or "ulw", which
 * `poll_mode_changes` routes to ulw.py's `handle_ulw_mode_change` instead.
 *
 * [turns] is only read on the "ulw" branch — the budget of autonomous turns
 * before the agent pauses at a `ulw_turns_reached` checkpoint. The encoder
 * (explicitNulls = false) drops the key entirely when null, which is also
 * what the server wants: `turns = turns or ULW_DEFAULT_TURNS` (ulw.py:68)
 * only falls back to 100 when the field is absent.
 *
 * The server acknowledges by sending a `mode_changed` notification back
 * (approval.py:341, ulw.py:86) — see [ChatItem.ModeChangedItem][ai.openonion.oochat.domain.model.ChatItem.ModeChangedItem].
 */
@Serializable
data class ModeChangeMessage(
    val type: String = "mode_change",
    val mode: String,
    val turns: Int? = null
)

/**
 * Query whether [session]'s id is still running on the server — used after
 * a reconnect to tell a genuinely-failed turn from one the server is still
 * working on despite the socket having dropped (WebSocket disconnect does
 * NOT kill the agent thread server-side — see
 * network/host/session/active.py's own doc). The server replies with a
 * top-level `{"type":"SESSION_STATUS","session_id":...,"status":...}` (see
 * [ai.openonion.oochat.network.ConnectionEvent.SessionStatusReceived])
 * — request and reply are NOT symmetric.
 *
 * Wire shape verified against the server's
 * network/host/ws_router/session.py: `sid = (data.get("session") or
 * {}).get("session_id")` reads a *nested* `session.session_id`, unlike
 * [ConnectMessage], which carries `session_id` at the envelope's top level.
 */
@Serializable
data class SessionStatusQuery(
    val type: String = "SESSION_STATUS",
    val session: SessionStatusQuerySession
)

@Serializable
data class SessionStatusQuerySession(
    @SerialName("session_id") val sessionId: String
)
