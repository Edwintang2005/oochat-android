# Android Client WebSocket Protocol

This document describes the ConnectOnion protocol surface that the Android
client currently implements. It is a client-focused companion to the server
protocol, not a replacement for it: fields and event types not represented by
the Kotlin code below are intentionally out of scope.

For the complete server-side contract, see
`connectonion-self-deploy/docs/network/websocket-protocol.md`. That repository
is an internal reference implementation, not an Android project dependency.

## Connection target and lifecycle

The default relay is `Constants.DEFAULT_SERVER_URL`:

```text
https://oo.openonion.ai
```

`AgentConnection` derives a WebSocket URL from the configured relay:

- Relay connections use `wss://.../ws/input` for an HTTPS relay, or
  `ws://.../ws/input` for an HTTP relay.
- Direct agent connections use the supplied URL and connect to `/ws`.

`WebSocketFactory` is the seam used to create an OkHttp `WebSocket` from a
`Request` and `WebSocketListener`. Production uses `OkHttpWebSocketFactory`;
tests can inject a different implementation.

After a transient socket failure or close, `AgentConnection` retries up to five
times with exponential delays of 2, 4, 8, 16, and 32 seconds. It emits a
`ConnectionEvent.Reconnecting` while retrying. A pending onboarding gate
suppresses automatic reconnects until the user acts, and a rejected onboarding
submission opens a fresh connection so the server can send a new gate.

## Client to server frames

All outgoing frame models live in
`app/src/main/java/ai/openonion/oochat/data/protocol/OutgoingMessages.kt`.
They are `@Serializable` Kotlin data classes and are encoded with
`kotlinx.serialization`.

### `CONNECT` — `ConnectMessage`

`CONNECT` starts an authenticated session.

| Field | Meaning |
| --- | --- |
| `type` | Always `"CONNECT"`. |
| `payload` | `ConnectPayload(timestamp, to)`; this is the signed payload. |
| `from` | The local Ed25519 address. |
| `signature` | Hex Ed25519 signature over the canonical payload JSON. |
| `timestamp` | Unix time in seconds. The same value is used in `payload`. |
| `to` | Target agent address for relay mode; absent for direct mode. |
| `session_id` | Optional persisted server-session identifier. |
| `session` | Optional `SessionState` snapshot. |

`AgentConnection.sendConnectMessage()` builds the payload and uses
`KeyManager.canonicalJson()` followed by `KeyManager.sign()` to create the
signature. Keeping one timestamp for the signed payload and envelope avoids a
signature mismatch.

When reconnecting, `ConnectionRepositoryImpl` restores the saved
`SessionState` through `SessionStore`, calls `AgentConnection.setSession()`,
and the next `CONNECT` includes its `session_id`. This is how a server session
is resumed after an app restart or connection loss.

### `INPUT` — `InputMsg`

`INPUT` sends one user prompt.

| Field | Meaning |
| --- | --- |
| `type` | Always `"INPUT"`. |
| `input_id` | Per-message UUID used to associate `OUTPUT` frames. |
| `prompt` | Text sent to the agent. |
| `to` | Relay-mode target address; absent for direct mode. |
| `images` | Optional list of base64 data URLs, for example `data:image/jpeg;base64,...`. |
| `files` | Optional list of `FileAttachment(name, data)`; `data` uses the same data-URL encoding as `images` with an arbitrary MIME type. |

The UI obtains local image attachments through `ImageAttachmentStore` and
passes their data URLs through the connection layer. `AgentConnection` tracks
the latest `input_id`; an `OUTPUT` for a different input is ignored in relay
mode.

### Control and response frames

| Frame model | Wire `type` | When it is sent |
| --- | --- | --- |
| `PongMessage` | `PONG` | Automatically after a server `PING`. |
| `AskUserResponse` | `ASK_USER_RESPONSE` | The user answers an `ask_user` card. |
| `ApprovalResponse` | `APPROVAL_RESPONSE` | The user allows or denies an `approval_needed` request. `scope` defaults to `"once"`; `mode` (`"reject_soft"`, `"reject_hard"`, or `"reject_explain"`) is only meaningful when `approved` is false. |
| `InterruptMessage` | `INTERRUPT` | The user asks the agent to stop gracefully. No payload — the agent finishes the current step and returns a closing message rather than stopping mid-tool-call. |
| `PlanReviewResponse` | `PLAN_REVIEW_RESPONSE` | The user answers a `plan_review` gate. Carries a single free-text `message`; the server treats it as a follow-up message, not a structured accept/reject verdict. |
| `UlwResponse` | `ULW_RESPONSE` | The user answers a `ulw_turns_reached` checkpoint. `action` is `"continue"` or `"switch_mode"`; `turns` applies only when continuing, `mode` (`"safe"`, `"plan"`, `"accept_edits"`, or `"ulw"`) only when switching. |

### `ONBOARD_SUBMIT` — `OnboardSubmitMessage`

`ONBOARD_SUBMIT` answers an `ONBOARD_REQUIRED` gate. It contains the sender
address, a signature, and an `OnboardSubmitPayload` with a timestamp plus one
of:

- `invite_code` for the `invite_code` method, or
- `payment` for the `payment` method.

The frame is signed exactly like `CONNECT`: the server verifies the canonical
payload even when the agent trust mode is open. There is deliberately no
top-level timestamp; the timestamp belongs to the signed `payload`. The client
validates that the requested method matches the non-null payload value before
sending it.

## Server to client events

Incoming JSON is first decoded into `ServerEvent` in
`data/protocol/ServerEvent.kt`. `ProtocolParser.parse()` is the authoritative
list of event types currently handled by this Android client. Unknown event
types are returned as `ConnectionEvent.Unknown` and do not alter the chat.

| Server `type` | Fields used by this client | Client behaviour |
| --- | --- | --- |
| `PING` | — | Emits `Ping`; `AgentConnection` sends `PONG`. |
| `CONNECTED` | `session_id`, `session` | Marks the connection ready and persists the supplied session. |
| `llm_call` | `id`, `model` | Creates a running `ChatItem.Turn` / thinking item. |
| `llm_result` | `id`, `status`, `model`, `duration_ms` | Updates the matching thinking item to done or error. |
| `thinking` | `id`, `content`, `model` | Emits completed thinking metadata, or streams assistant text when `content` is present. |
| `tool_call` | `tool_id`, `id`, `name`, `args` | Creates a running `ChatItem.ToolCall`. |
| `tool_result` | `tool_id`, `id`, `name`, `status`, `result` | Updates a tool call to done or error. |
| `agent_image` | `image` | Creates an image-only agent item. The value is a URL or base64 data URL. |
| `assistant` | `content` | Creates an assistant turn using a stable content-derived ID. |
| `session_sync` | `session.messages` | Replays non-empty assistant messages from the server snapshot. |
| `user_input` | — | Ignored because the local `ChatItem.User` is already the source of truth. |
| `ask_user` | `id`, `text`, `options`, `multi_select` | Creates an answer card and enters a waiting state. |
| `approval_needed` | `id`, `tool`, `arguments`, `description`, `batch_remaining` | Creates an approval card and enters a waiting state. `batch_remaining` previews the next queued tool calls in the current approval batch. |
| `ONBOARD_REQUIRED` | `id`, `methods`, `payment_amount`, `payment_address` | Creates an onboarding card and enters a waiting state. |
| `ONBOARD_SUCCESS` | `id`, `level`, `message` | Creates a success card; the connection re-authenticates on the live socket. |
| `intent` | `id`, `status`, `ack`, `is_build` | Updates a `ChatItem.IntentItem`. Status is `UNDERSTOOD` only when `status` is exactly `"understood"`, otherwise `ANALYZING`. |
| `eval` | `id`, `status`, `passed`, `summary`, `expected`, `eval_path` | Updates a `ChatItem.EvalItem` holding the agent's self-grading result. Status is `DONE` only when `status` is exactly `"done"`, otherwise `EVALUATING`. |
| `compact` | `id`, `status`, `context_before`, `context_after`, `context_percent`, `message`, `error` | Updates a `ChatItem.CompactItem` tracking context-window compaction. Status is `DONE` for `"done"`, `ERROR` for `"error"`, otherwise `COMPACTING` — the one status event with three terminal states rather than two. |
| `tool_blocked` | `id`, `tool`, `reason`, `message`, `command` | Creates a `ChatItem.ToolBlockedItem` for a tool call that policy vetoed before it ran. Missing `tool`, `reason`, and `message` default to the empty string. |
| `plan_review` | `id`, `plan_content` | Creates a `ChatItem.PlanReviewItem` and enters a waiting state until the user answers with `PLAN_REVIEW_RESPONSE`. |
| `files_received` | `id`, `files[].name`, `files[].path` | Creates a `ChatItem.FilesReceivedItem` listing files the agent returned; an absent `files` array yields an empty list. |
| `ulw_turns_reached` | `id`, `turns_used`, `max_turns` | Creates a `ChatItem.UlwTurnsReachedItem` and enters a waiting state until the user answers with `ULW_RESPONSE`. Both counters default to `0`. |
| `OUTPUT` | `input_id`, `result`, `session` | Emits the final output when it belongs to the current input, or for any direct connection; updates the session snapshot. |
| `ERROR` | `message`, `error` | Emits `ConnectionError` using `message`, then `error`, then `"Unknown error"`. |

For `assistant`, `thinking` text, `agent_image`, and `session_sync`, the parser
uses `stableAssistantId()` to make replayed messages idempotent. This prevents
the same assistant response from appearing multiple times after reconnecting.

The status and checkpoint events reuse the server's `id` instead. When a frame
omits it the parser generates a UUID, so that frame cannot be correlated with a
later update for the same item. `llm_result` and `tool_result` are stricter:
with no `id` (or `tool_id`) there is nothing to update, so they parse to
`ConnectionEvent.Unknown` rather than inventing one.

## Session state and recovery

`SessionState` is the wire DTO:

| Field | Meaning |
| --- | --- |
| `session_id` | Server-owned session identifier. |
| `messages` | Ordered `SessionMessage(role, content)` transcript entries. |
| `trace` | Optional protocol trace as JSON elements. |
| `turn` | Optional server turn counter. |

`SessionState.toDomain()` maps the DTO to `SessionSnapshot`, exposing only the
session ID, turn count, and message count to domain consumers. The full DTO is
stored by `SessionStoreImpl`: it serializes messages and trace JSON into one
Room `SessionStateEntity` row keyed by agent address. `MemoryGatedSessionStore`
can suppress reads when the memory setting is off while still allowing writes
and deletes.

The recovery path is therefore:

```text
saved SessionState -> SessionStore.getSession(agentAddress)
                   -> AgentConnection.setSession(...)
                   -> next CONNECT carries session_id
                   -> CONNECTED / OUTPUT / session_sync refresh local state
```

## HTTP exception

Every frame above travels over the WebSocket. Voice transcription is the one
client-to-relay interaction that does not: `VoiceTranscriptionServiceImpl` in
`network/VoiceTranscriptionService.kt` uploads a recording over HTTPS with its
own OkHttp client (30-second connect and read timeouts), separate from
`AgentConnection`.

The endpoint is the configured base URL — `Constants.DEFAULT_SERVER_URL` unless
overridden — with a fixed path appended:

```text
https://oo.openonion.ai/v1/chat/completions
```

The body is an OpenAI-style chat completion that carries the audio inline:

| Field | Meaning |
| --- | --- |
| `model` | Always `"gemini-2.5-flash"`. |
| `messages[0].role` | Always `"user"`. |
| `messages[0].content[0]` | A `text` part holding the fixed prompt `"Transcribe this audio accurately."`. |
| `messages[0].content[1]` | An `input_audio` part whose `input_audio.data` is the WAV file base64-encoded with `Base64.NO_WRAP`, and whose `input_audio.format` is `"wav"`. |

Authentication is a bearer token in the `Authorization` header, taken from the
`apiKey` of `AccountRepository.loadAccount()`. If the relay answers `401`, the
client closes that response, re-reads the token with
`loadAccount(forceReauth = true)`, and replays the same body once. Only that
one case is retried: every other failure is returned to the caller as a failed
`Result` so the UI can move its voice bubble to the failed state rather than
silently re-uploading.

The transcript is the first non-blank `choices[].message.content` in the
response. A non-2xx status, an absent body, and an empty transcript all fail
the `Result`.

## Source of truth

Complete protocol definitions live in
`connectonion-self-deploy/docs/network/websocket-protocol.md` (the
server-side authoritative document, not part of this repository).
