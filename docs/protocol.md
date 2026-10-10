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
submission opens a fresh connection so the server can send a new gate. A Host
that refuses the client's OIP version ends the cycle with no reconnect at all;
see [Incompatible protocol version](#incompatible-protocol-version).

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
| `protocol` | The OIP protocol descriptor, `OipProtocol.DESCRIPTOR`. See below. |
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

#### Protocol descriptor

Every `CONNECT`, including the one re-sent after an onboarding gate, declares
the OIP version the client speaks:

```json
"protocol": {"name": "oip", "version": "0.1", "min_version": "0.1", "max_version": "0.1"}
```

The descriptor is defined once, in
`app/src/main/java/ai/openonion/oochat/data/protocol/OipProtocol.kt`, and
matches `OIP_PROTOCOL` in `connectonion-react`'s `src/connect/protocol.ts`.
It sits at the top level of the envelope and is not signed. The Host reads
`data.get("protocol")` from the envelope and verifies the signature over
`payload` alone, so adding the descriptor does not change the signed bytes.

The Host compares only `name` and `version`, in `supports_oip()` in
`connectonion/network/host/protocol.py`. It accepts a `CONNECT` with no
descriptor only during the rolling compatibility window. That window covers
1.7.x and may close no earlier than 1.8.0a1 (2026-09-15). If the Host does not
support the declared version, it answers with a non-retryable
`{"type":"ERROR","code":-32010,"message":"Unsupported OIP protocol","retryable":false,"protocol":{…}}`.
The client treats that as final; see
[Incompatible protocol version](#incompatible-protocol-version).

Covered by `OutgoingMessagesTest`, which checks the serialised `CONNECT`
frame, and by `AgentConnectionRobolectricTest`, which checks the frame sent
when the socket opens, the frame re-sent after onboarding, and that the
descriptor is not part of the signed payload.

#### Incompatible protocol version

When the Host refuses the descriptor, the client shows one error and does not
reconnect on its own. Retrying would send the same descriptor and be refused
the same way, so each attempt would cost battery and add another copy of the
error.

1. `ProtocolParser` turns an `ERROR` whose `code` is `-32010`
   (`OipProtocol.UNSUPPORTED_VERSION_CODE`) into
   `ConnectionEvent.ProtocolRejected`. The event carries the Host's version
   from the frame's `protocol` descriptor, for example `oip/0.2`, or null if
   the descriptor is missing or malformed. The name and version are
   peer-controlled and end up in `app.log` and in the error text, so anything
   other than a short token (letters, digits, `.`, `_`, `+`, `-`, at most 32
   characters) also reads as null. Every other `ERROR`, including ones with a
   string `code`, stays a `ConnectionError`.
2. `AgentConnection` marks the cycle as rejected, cancels any scheduled
   reconnect, and closes the socket itself. The Host leaves the socket open
   after its `ERROR`, and a later drop would otherwise start the reconnect
   ladder. While the cycle is rejected, `attemptReconnect()`, `retryNow()` and
   the socket reopen behind a conversation switch do nothing. That covers
   socket drops, the network coming back, the app returning to the
   foreground, and a switch that raced the refusal. Only a new `connect()`,
   meaning the user trying again, clears the flag.
3. `ConnectionRepositoryImpl` publishes
   `ConnectionState.Error(retryable = false)` with the message from
   `ServerErrorText.incompatibleProtocol()`, for example: "This agent uses a
   different version of the chat protocol (oip/0.2) than this app (oip/0.1).
   Update the app or the agent, then reconnect." It does this even if the
   attempt had already connected, because an auto-reconnect after a Host
   upgrade can be the `CONNECT` that is refused.
4. `ConnectToAgentUseCase` does not make its usual second attempt after a
   non-retryable error.
5. `ChatViewModel` adds the message to the chat once. Its stale-session
   suppression applies only to retryable errors, so a Host version that
   happens to read like a stale-session error cannot hide the message.
   `ConnectionBanner` titles the error "Incompatible version" rather than
   "Connection failed", and keeps its Reconnect action: nothing recovers on its
   own, and once the app or the agent is updated, that is the user's way back.

Covered by `ProtocolParserVersionRejectionTest`, by the W16AAPPLE-12 section of
`AgentConnectionRobolectricTest`, and by `ConnectionRepositoryImplTest`,
`ConnectToAgentUseCaseTest`, `ServerErrorTextTest`, `ChatViewModelTest`, and `ConnectionBannerTest`.

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
| `compact` | `id`, `status`, `context_before`, `context_after`, `context_percent`, `message`, `error` | Updates a `ChatItem.CompactItem` tracking context-window compaction. Status is `DONE` for `"done"`, `ERROR` for `"error"`, otherwise `COMPACTING` — the one status event with three terminal states rather than two. Rendered as a subtle divider in the chat timeline (see [Compaction in the timeline](#compaction-in-the-timeline)). |
| `tool_blocked` | `id`, `tool`, `reason`, `message`, `command` | Creates a `ChatItem.ToolBlockedItem` for a tool call that policy vetoed before it ran. Missing `tool`, `reason`, and `message` default to the empty string. |
| `plan_review` | `id`, `plan_content` | Creates a `ChatItem.PlanReviewItem` and enters a waiting state until the user answers with `PLAN_REVIEW_RESPONSE`. |
| `files_received` | `id`, `files[].name`, `files[].path` | Creates a `ChatItem.FilesReceivedItem` listing files the agent returned; an absent `files` array yields an empty list. |
| `ulw_turns_reached` | `id`, `turns_used`, `max_turns` | Creates a `ChatItem.UlwTurnsReachedItem` and enters a waiting state until the user answers with `ULW_RESPONSE`. Both counters default to `0`. |
| `RUNTIME_INPUT_ACK` | `session_id`, `id` | Emits `ConnectionEvent.RuntimeInputAcknowledged`: the host queued an `INPUT` that arrived mid-turn for the running turn's next iteration. `id` is the host's own UUID for the queued item, **not** the `input_id` we sent, so it cannot name the message it answers — see [Runtime input](#runtime-input). Not an `OUTPUT`: it does not end the turn. |
| `OUTPUT` | `input_id`, `result`, `session` | Emits the final output when it belongs to the current input, or for any direct connection; updates the session snapshot. |
| `ERROR` | `code`, `protocol`, `retryable`, `session_id`, `message`, `error` | With `code` `-32010`, emits `ProtocolRejected` (see [Incompatible protocol version](#incompatible-protocol-version)). With `code` `"RUNTIME_INPUT_REJECTED"`, emits `RuntimeInputRejected` instead of an error: the socket and the running turn are healthy, and `ConnectionError` could start the reconnect ladder (see [Runtime input](#runtime-input)). `retryable` is read as true only for a literal boolean `true`. Every other `ERROR` emits `ConnectionError` using `message`, then `error`, then `"Unknown error"`. |

For `assistant`, `thinking` text, `agent_image`, and `session_sync`, the parser
uses `stableAssistantId()` to make replayed messages idempotent. This prevents
the same assistant response from appearing multiple times after reconnecting.

The status and checkpoint events reuse the server's `id` instead. When a frame
omits it the parser generates a UUID, so that frame cannot be correlated with a
later update for the same item. `llm_result` and `tool_result` are stricter:
with no `id` (or `tool_id`) there is nothing to update, so they parse to
`ConnectionEvent.Unknown` rather than inventing one.

### Compaction in the timeline

When the agent's context window fills, the server compacts it: earlier turns
are replaced by a summary. The server announces this with two `compact`
frames that share one `id`, for example:

```json
{"type":"compact","id":"c1","status":"compacting","context_percent":91.2}
{"type":"compact","id":"c1","status":"done","context_before":90.0,"context_after":32.951}
```

The percentages are floats from 0 to 100, so the client parses them as
`Double`. Both frames parse to `ConnectionEvent.ChatItemUpdated`, so the
second replaces the first in place. `CompactDivider` (in
`ui/chat/components/StatusItemCards.kt`) draws the item as a thin, centred,
outline-coloured line rather than a card, because nothing about it needs an
answer:

| Status | Divider text |
| --- | --- |
| `COMPACTING` | "Compacting context…" |
| `DONE` | "Context compacted: 90% → 33%" (rounded), else `message`, else "Context compacted"; followed by a hint that earlier messages were summarised and the agent may not recall their details. |
| `ERROR` | `error`, else "Compaction failed", in the error colour. There is no memory-loss hint, because nothing was summarised. |

`CompactItem` is persisted with the transcript (`TranscriptItemPayload.Compact`),
so a restored chat still shows where the agent's memory was cut. If the stream
ends while an item is still `COMPACTING`, `resolveRunningItems()` marks it `DONE`.

## Runtime input

An `INPUT` that reaches a host while that session's agent is running is treated
as *runtime input* instead of starting a second turn. The host replies at once,
on the same socket, with one of two frames:

| Host reply | Meaning | Wire shape |
| --- | --- | --- |
| `RUNTIME_INPUT_ACK` | Queued for the running turn. | `{"type":"RUNTIME_INPUT_ACK","session_id":"…","id":"<host uuid>"}` |
| `ERROR` + `RUNTIME_INPUT_REJECTED` | Not taken (the turn is sealed, or the agent lacks the `runtime_input` plugin). Nothing was queued. | `{"type":"ERROR","code":"RUNTIME_INPUT_REJECTED","message":"…","session_id":"…","retryable":true}` |

Source of truth: `ws_router/session.py` in openonion/connectonion, which sends
the ACK only after `push_runtime_input` accepted the text.

**Linking a reply to its message.** Neither frame carries the `input_id` we
sent: the ACK's `id` is minted by the host. A reply can therefore only be
linked by there being exactly one candidate. `ChatViewModel` allows one message
awaiting an answer at a time (`pendingRuntimeInput`) and holds its send lock
until that answer arrives or `RUNTIME_INPUT_REPLY_TIMEOUT_MS` passes, so the next
message cannot go out while one is owed. A reply can never be handed to the wrong
message, even when the client was wrong about a turn running and the host never
answers.

The message is registered *before* the `INPUT` leaves: the host answers the
moment it reads it, and a reply that beat the registration would find nobody
waiting. Only mid-turn sends are registered; an `INPUT` to an idle agent starts a
turn and gets no such reply.

**Bubble state (`UserMessageState`).** A message sent into a running turn shows
`AWAITING_ACK` ("Sending…") until the host answers:

| Event | Result |
| --- | --- |
| `RUNTIME_INPUT_ACK` | `SENT` |
| `RUNTIME_INPUT_REJECTED`, `retryable: true` (first time) | `QUEUED`, then held until the turn's `OUTPUT` and sent again as an ordinary `INPUT`. The same bubble is reused and the same payload is sent, images and files included. |
| `RUNTIME_INPUT_REJECTED`, `retryable: true` (second time) | `FAILED` (tap to resend). A message is repeated once, never in a loop. |
| `RUNTIME_INPUT_REJECTED`, not retryable | `FAILED` (tap to resend) |
| `OUTPUT` with no reply | `SENT`. The host answers a runtime `INPUT` immediately, so silence until `OUTPUT` means the message reached an agent that had just gone idle and started its own turn. |
| No reply within `RUNTIME_INPUT_REPLY_TIMEOUT_MS` | `SENT`, for the same reason. |
| Any other `ERROR` frame | No change. It says nothing about whether the host took this message; the host's own refusal arrives as its own event. |
| Disconnect / reconnect | `FAILED`. No reply can arrive on a dead socket and whether the host queued it is unknown. |

**When this happens at all.** `ChatViewModel.sendWhenTheAgentIsIdle` still holds
ordinary messages until the running turn ends (see `architecture.md`), so a
message only goes out mid-turn when that hold times out
(`ChatViewModel.TURN_WAIT_TIMEOUT_MS`) or the client's view of the turn was
stale. The ACK handling is what makes that path honest rather than silent.
A message with images or files is never sent into a running turn, because the
host’s runtime-input queue keeps only the text. It is held until the turn ends.
If the turn is still running after 2 minutes, the message is marked failed
(tap to resend) rather than sent or held forever.

**Known gaps.**

- A reply that arrives with nothing awaiting it is logged and ignored. If a
  refusal crossed the turn's `OUTPUT` on the wire, the `OUTPUT` has already
  settled the message as `SENT` and the refusal cannot be attributed, so that
  message is not resent.
- Hosts older than the change that made the ACK truthful acknowledge input they
  never drain. Nothing on the wire identifies such a host.

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
