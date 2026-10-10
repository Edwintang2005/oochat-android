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
`ConnectionEvent.Reconnecting` while retrying. Each retry sends a new `CONNECT`
carrying `session_id` and, once agent events have been handled on that session,
`last_msg_id`, so the Host can replay what was missed; see
[Reconnect and replay](#reconnect-and-replay).
A pending onboarding gate
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
| `payload` | `ConnectPayload(timestamp, to, nonce, signed_commands)`; this is the signed payload. `nonce` is a fresh random UUID; `signed_commands` is always `1`. See [Signed commands](#signed-commands). |
| `from` | The local Ed25519 address. |
| `signature` | Hex Ed25519 signature over the canonical payload JSON. |
| `timestamp` | Unix time in seconds. The same value is used in `payload`. |
| `to` | Target agent address for relay mode; absent for direct mode. |
| `session_id` | Optional persisted server-session identifier. |
| `last_msg_id` | Optional. The Host's id for the last agent-log frame this connection handled on `session_id`; sent on a `CONNECT` for that same session (a reconnect, a switch back, the re-`CONNECT` after an invite code) and ignored by the Host unless the session is still running. Unsigned, like `session_id`. See [Reconnect and replay](#reconnect-and-replay). |
| `session` | Optional `SessionState` snapshot. |

`AgentConnection.sendConnectMessage()` builds the payload and uses
`KeyManager.canonicalJson()` followed by `KeyManager.sign()` to create the
signature. Keeping one timestamp for the signed payload and envelope avoids a
signature mismatch.

The Host refuses a CONNECT signature it has seen before. Ed25519 is
deterministic and the timestamp is in whole seconds, so the `nonce` is what
keeps two CONNECTs in the same second distinct. `AgentConnection.nextConnectTimestamp()`
also never repeats a second, as a second guard.

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
A reconnect on the same
`AgentConnection` also names the last event it handled; see
[Reconnect and replay](#reconnect-and-replay).


### Signed commands

Every `CONNECT` signs `payload.signed_commands: 1`. This turns on the Host's
v2 command gate for that socket. After that, every application command must
carry its own Ed25519 signature, or the Host answers with an `ERROR` and does
not act on it. Without the gate, anyone able to write to the socket, such as a
relay, could inject commands or replay captured ones under the identity
`CONNECT` authenticated. The capability is inside the signature, so it cannot
be stripped to downgrade the socket. A pre-v2 Host ignores it.

The envelope is built by `SignedCommand.envelope()` in
`app/src/main/java/ai/openonion/oochat/data/protocol/SignedCommand.kt`, and
`AgentConnection.signedCommand()` signs it with the connection's identity:

```json
{
  "type": "APPROVAL_RESPONSE", "approved": true, "scope": "once",
  "payload": {
    "type": "APPROVAL_RESPONSE", "approved": true, "scope": "once",
    "to": "0x3d40…", "timestamp": 1760000000, "nonce": "550e8400-…"
  },
  "from": "0xClientPublicKey",
  "signature": "…"
}
```

| Field | Meaning |
| --- | --- |
| top-level fields | The command exactly as it was sent before v2, for a pre-v2 Host, which never reads `payload`. |
| `payload` | The whole command plus `to`, `timestamp` and `nonce`. This is what is signed, and the only part a v2 Host executes. |
| `payload.to` | The agent address `CONNECT` named. Signed on direct connections too, even though a direct `INPUT` carries no top-level `to`, so a command captured for one agent cannot be delivered to another. |
| `payload.timestamp` | Unix time in seconds. The Host refuses one more than 300 seconds from its own clock. |
| `payload.nonce` | A fresh random UUID per frame. Two identical commands in the same second would otherwise sign identical bytes, and the second would be refused as a replay. |
| `from` | The local Ed25519 address. |
| `signature` | Hex Ed25519 signature over `KeyManager.canonicalJson(payload)`: keys sorted at every depth, no whitespace, non-ASCII as UTF-8. |

The Host's checks, in `authenticated_command_payload` in
`connectonion/network/host/auth.py`, and the `ERROR` message each one sends:

| Check | Refusal |
| --- | --- |
| Timestamp within 300 seconds | `unauthorized: signature expired` |
| `payload.to` is the agent `CONNECT` named (relay connections) | `unauthorized: wrong recipient` |
| Signature verifies against `from` | `unauthorized: invalid signature` |
| `from` is the identity that opened the socket | `unauthorized: command signer does not own this connection` |
| `payload.type` equals the frame's `type` | `unauthorized: signed command type mismatch` |
| `payload.nonce` present | `unauthorized: signed command nonce required` |
| Signature never used before (a shared, persistent ledger) | `unauthorized: signed command already used` |

Signed: `INPUT`, `EXEC`, `ASK_USER_RESPONSE`, `APPROVAL_RESPONSE`, `INTERRUPT`,
`PLAN_REVIEW_RESPONSE`, `ULW_RESPONSE` and `mode_change`. These are the frames
the Host leaves unsigned on purpose (`SignedCommand.EXEMPT_TYPES`, matching
`exempt` in `ws_router/session.py`):

- `CONNECT` and `ONBOARD_SUBMIT` carry their own signatures.
- `PONG` is a transport frame.
- `SESSION_STATUS` on a live socket uses the identity `CONNECT` verified.

If no identity is loaded, a command is dropped rather than sent unsigned.

**Costs and gaps.**

- An `INPUT`'s images and files are carried twice: once at the top level and
  once in the signed payload. A message with attachments is therefore about
  twice its old size on the wire.
- Every signed timestamp must be within 300 seconds of the Host's clock, and
  a phone's clock can be off by more. So the client signs with the server's
  time (W16AAPPLE-90). `network/HostClock` reads the HTTP `Date` header of each
  WebSocket upgrade and applies the difference to `CONNECT`, every signed
  command and `ONBOARD_SUBMIT` on that socket:
  - No `Date` header: the device clock, as before.
  - Each new socket takes its own reading.
  - The correction is bounded to 30 minutes (`HostClock.MAX_OFFSET_MS`). On
    the relay path the header comes from the relay, and the Host records a
    signature as used only once it has passed the expiry check. A relay that
    moved the clock forward could therefore hold a command and deliver it up
    to that much later. Past the bound the device clock is used.
  - `nextConnectTimestamp()` follows a clock that has gone back by more than
    5 seconds (the user fixed the date, or the correction arrived) instead of
    holding every later `CONNECT` past the old, wrong second.

  When the correction cannot help, the refusal (`unauthorized: signature
  expired`) is shown as `ServerErrorText.CLOCK_WRONG`, advice to check the
  device's date and time. That covers a connection error, a refused reconnect
  and a refused invite code.
- Every refusal comes back as an ordinary `ERROR`. On a live session it
  surfaces as a `ConnectionError` like any other; in reply to a reconnect's
  `CONNECT` it ends the reconnect (see [When a reconnect fails](#when-a-reconnect-fails)).
  A correct client never triggers one except through the clock.
- A command dropped for want of an identity is only logged; the UI is
  not told. CONNECT needs the same keys, so this cannot happen on a live
  connection in practice.

Covered by `SignedCommandTest`, which signs every command type with real Ed25519
and checks it against a model of the Host's gate. That covers a byte-for-byte
canonical form taken from Python's own `json.dumps` (including tabs, control
characters, DEL, U+2028 and emoji, where JSON encoders differ), a rejected replay, a fresh
nonce for each repeated command, and refusal of an edited, relabelled,
foreign-signed, misaddressed or stale command. Also covered by the W16AAPPLE-16
section of `AgentConnectionRobolectricTest`, which checks the frames
`AgentConnection` actually sends, the `CONNECT` capability and nonce, and that
`PONG` and `SESSION_STATUS` stay unsigned.

### `INPUT` — `InputMsg`

`INPUT` sends one user prompt. It is a [signed command](#signed-commands); the
table below lists the command fields, which appear both at the top level and in
the signed `payload`.

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

### `EXEC` — `ExecMessage`

`EXEC` asks the agent to run one named tool immediately with a JSON object of
arguments. It is a client-requested tool command, separate from a conversational
`INPUT` that may cause the agent to choose and run several tools itself.

It is a [signed command](#signed-commands), built like every other one:
`AgentConnection.signedCommand()` wraps `ExecMessage` in `SignedCommand.envelope()`.
The table lists the command fields, which appear both at the top level and in
the signed `payload`:

| Field | Meaning |
| --- | --- |
| `type` | Always `"EXEC"`. |
| `exec_id` | UUID identifying this tool command. |
| `tool` | Name of the tool to run. |
| `args` | JSON object containing the tool arguments. |
| `to` | Target agent address. Kept at the top level too, for a pre-v2 relay that routes on it. |

The envelope then adds `timestamp` and `nonce` to the signed `payload`, and
`from` and `signature` beside it, exactly as for `INPUT`:

```json
{
  "type": "EXEC", "exec_id": "request-id", "tool": "read_file",
  "args": { "path": "README.md" }, "to": "agent-address",
  "payload": {
    "type": "EXEC", "exec_id": "request-id", "tool": "read_file",
    "args": { "path": "README.md" }, "to": "agent-address",
    "timestamp": 1730000000, "nonce": "fresh-request-value"
  },
  "from": "0xClientPublicKey",
  "signature": "…"
}
```

The Host's `ws_router/exec.py` reads `exec_id`, `tool` and `args`.

The Android client waits for the server's `EXEC_RESULT` with the same `exec_id`.
See [EXEC_RESULT](#exec_result) in the server-to-client section. It does
not retry the command automatically after a timeout.

Two refusals arrive as a plain `ERROR` with no `exec_id`, and both fail every
waiting EXEC card at once instead of leaving it to time out:

- `code: "provider_station_only"`: a Work Room Host takes no EXEC. It concerns
  EXEC alone, so it is shown on the cards only, not again as a chat error.
- `"authenticate first"`: refuses every command on the socket, so it is still
  reported in the chat as well, in case a message was refused alongside.

Any other refusal, such as a signed-command `unauthorized: …`, could belong to
an `INPUT` instead, so a waiting EXEC is left to its timeout.

### Control and response frames

Every control frame below except `PONG` is a signed command.
`mode_change` (`ModeChangeMessage`, sent by `AgentConnection.setMode()`) is
signed the same way.

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
| `CONNECTED` | `session_id`, `status`, `server_newer`, `session` | Marks the connection ready and persists the supplied session. `status` and `server_newer` reach the chat screen, which acts on them once: see [CONNECTED status](#connected-status). |
| `llm_call` | `id`, `model` | Creates a running `ChatItem.Turn` / thinking item. |
| `llm_result` | `id`, `status`, `model`, `duration_ms` | Updates the matching thinking item to done or error. |
| `thinking` | `id`, `content`, `kind`, `model` | With `content`, the agent's reasoning: a `ChatItem.Thinking` under the frame's `id` with `kind` set (`intent`, `reflect`, or `reasoning` when absent), rendered as a collapsible "Thought" rather than as a reply. Without `content`, completed thinking metadata on a `ChatItem.Turn`. See [Streaming frames](#streaming-frames). |
| `tool_call` | `tool_id`, `id`, `name`, `args`, `status` | Creates a `ChatItem.ToolCall` keyed on `tool_id`. `pending`, `running`, `in_progress` or no status is running; anything else is an error. |
| `tool_result` | `tool_id`, `id`, `name`, `status`, `result`, `timing_ms` | Updates the `ChatItem.ToolCall` with the same `tool_id`. Only `completed`, `success` or `done` is done; every other status, or none, is an error. |
| `plan` | `entries[].content`, `entries[].priority`, `entries[].status` | Replaces the agent's Todo List (`ConnectionEvent.PlanUpdated`); an empty list clears it. Not a chat item: shown as `PlanPanel` above the transcript. A plan with any invalid entry is ignored whole. |
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
| `EXEC_RESULT` | `exec_id`, `status`, `result`, `error`, `tool`, `duration_ms` | Completes the pending direct `EXEC` request with a success or error outcome. |
| `ERROR` | `code`, `protocol`, `retryable`, `session_id`, `message`, `error` | With `code` `-32010`, emits `ProtocolRejected` (see [Incompatible protocol version](#incompatible-protocol-version)). With `code` `"RUNTIME_INPUT_REJECTED"`, emits `RuntimeInputRejected` instead of an error: the socket and the running turn are healthy, and `ConnectionError` could start the reconnect ladder (see [Runtime input](#runtime-input)). `retryable` is read as true only for a literal boolean `true`. Every other `ERROR` emits `ConnectionError` using `message`, then `error`, then `"Unknown error"`. |

### `EXEC_RESULT`

`EXEC_RESULT` reports the outcome of one client-requested tool command. The
`exec_id` connects the result to the pending `EXEC` request.

A successful result has this form:

```json
{
  "type": "EXEC_RESULT",
  "exec_id": "request-id",
  "status": "success",
  "tool": "read_file",
  "result": "file contents",
  "duration_ms": 42.0
}
```

An error result has this form:

```json
{
  "type": "EXEC_RESULT",
  "exec_id": "request-id",
  "status": "error",
  "tool": "delete_file",
  "error": "Permission denied",
  "duration_ms": 18.0
}
```

The parser accepts a success only when `result` is present and accepts an
error only when `error` is present. Frames with a missing `exec_id`, missing
required content, or an unsupported status are ignored as unknown frames.

For `assistant`, `thinking` text, `agent_image`, and `session_sync`, the parser
uses `stableAssistantId()` to make replayed messages idempotent. This prevents
the same assistant response from appearing multiple times after reconnecting.
That is the second line of defence: `AgentConnection` first drops a repeat of a
frame it has already handled (see [Reconnect and replay](#reconnect-and-replay)).

The status and checkpoint events reuse the server's `id` instead. When a frame
omits it the parser generates a UUID, so that frame cannot be correlated with a
later update for the same item. `llm_result` and `tool_result` are stricter:
with no `id` (or `tool_id`) there is nothing to update, so they parse to
`ConnectionEvent.Unknown` rather than inventing one.

### Streaming frames

Checked against connectonion's own emitters (`core/tool_executor.py`,
`core/wire_events.py`, `useful_plugins/re_act.py`,
`useful_events_handlers/reflect.py`, `core/agent.py`) and the reference web
client's mapper (`connectonion-react`'s `chat-item-mapper.ts`). Covered by
`StreamingFramesSpecTest`, one section per frame type.

**Stable ids.** Every logged frame carries its own trace `id`, which moves the
replay cursor. The chat item a frame becomes is keyed on the id that names the
*thing*, so a replay lands on the item already on screen:

| Frame | Chat item id |
| --- | --- |
| `thinking` with text | the frame's `id` (a `thinking-` prefixed content hash if it has none, so it can never share an id with a reply of the same text) |
| `tool_call`, `tool_result` | `tool_id`, the LLM's call id that tool_executor.py puts on both. Each frame's own `id` differs between the two and is only a fallback. |
| `llm_call`, `llm_result` | the shared `id` |

**Tool status.** On the live wire `wire_events.py` rewrites tool statuses to
OIP's lifecycle: a start is `pending` or `in_progress`, a result is `completed`
or `failed`. A replayed or older trace can still say `success`, `error`,
`not_found` or `interrupted`. A result is terminal, so only a known success is
shown as done; a missing or unknown status is shown as failed, as the Host's own
replay (`session/ui.py`) and the web client do. `timing_ms` (float
milliseconds) is kept on the item.

**One bubble per turn.** A turn (everything between two of the user's
messages) shows only its newest thought; a "Show all N thoughts" toggle above
it lists the earlier ones, oldest first (`groupReasoningByTurn`,
`ReasoningBubble`). The toggle's state is held by `MessageList` per turn, so it
stays open as new thoughts arrive.

**Reasoning versus reply.** `re_act` and `reflect` also append their reasoning
to the session's `messages` as an assistant entry, so every `session_sync`
after a `thinking` frame lists that text again as if it were a reply. The
reducer drops a snapshot reply whose text is reasoning already on screen, and
`mergeServerTranscript` counts stored reasoning rows as local copies, so the
transcript replayed on CONNECT does not add it either.

**Reconnect.** A `tool_result` updates the `ToolCall` with its `tool_id` in
place, wherever it sits in the list. A replayed `tool_call` for a call already
on screen never reopens it: the card keeps its status and result and only takes
the `args` or name it was missing (a result can reach the list before its start
when a replay begins mid-run). Replaying a whole execution over the list it
produced leaves the list unchanged.

**Plan.** Session state, not transcript, as in the web client's
`CurrentTodoListPanel`. `ConnectionRepository.plan` holds what the panel shows,
and `AgentConnection`'s session carries the same plan as `SessionState.plan`:

- A `plan` frame replaces both.
- A session snapshot (CONNECTED, `session_sync`, OUTPUT) with a valid `plan`
  replaces it. One with no `plan`, or a malformed one, keeps the plan already
  held for the same session (`remote-agent.ts`'s `_applySessionSnapshot` rule);
  a different session's plan is never carried over.
- The session, plan included, is saved with the rest of it
  (`session_states.plan_json`, added in database v13) and echoed on CONNECT,
  which the Host adopts as the session, so the agent's next turn keeps its
  plan.
- `connect()` and a conversation switch show the stored plan of the
  conversation being opened. Nothing on the wire would bring it back otherwise:
  the Host sends a session on CONNECTED only when its copy is newer
  (`server_newer`) and replays frames only into a running turn.

A stored plan that no longer decodes is dropped on its own; the rest of the
session still restores.

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
                   -> (a reconnect on the same AgentConnection also carries last_msg_id)
                   -> CONNECTED / OUTPUT / session_sync refresh local state
```
### Reconnect and replay

A dropped socket does not stop the agent. The Host keeps the running turn and a
log of everything the agent has sent on that session, so a client that comes
back can ask for what it missed instead of losing it.

**What the client sends.** Every `CONNECT` carries `session_id`. A `CONNECT` for
the session the connection last handled agent frames on (a retry from the ladder
or `retryNow()`, a switch back to that session, the re-`CONNECT` after an invite
code) also carries `last_msg_id`: the Host's own id for the last agent-log frame
handled there. The Host ignores it unless the session is still `running`. The id
is unsigned and sits next to `session_id` on the envelope; the signed `payload`
is still just `timestamp`, plus `to` in relay mode. A `CONNECT` for any other
session (a different conversation, or a stale-session rejection, which drops the
session) sends no `last_msg_id`.

**What moves the cursor.** Any frame that carries an `id`, except the frames the
Host writes straight to the socket instead of appending to its log: `CONNECTED`,
`OUTPUT`, `ERROR`, `PING`, `AGENT_PROFILE`, `DASHBOARD_SNAPSHOT`,
`SESSION_STATUS`, `RUNTIME_INPUT_ACK` (whose `id` is a UUID the Host mints for the
queued input, not a log entry), `ONBOARD_REQUIRED` and `ONBOARD_SUCCESS`
(`HOST_FRAME_TYPES` in `ProtocolParser`). The Host stamps an `id` on every entry in its log, so this
follows new event types (`turn_result`, `provider_message`, and so on)
without a list to maintain, whether or not the client decodes them;
`ProtocolParser` puts it in `ParseResult.msgId`. An id on one of the excluded
frames would name nothing the Host could rewind to. `AgentConnection` stores the
latest id together with its session and sends it only while that session is
still the current one; `reset()` clears it.

**What the Host does.**

| Session on the Host | `CONNECTED` | Replay |
| --- | --- | --- |
| `running`, `last_msg_id` known | `status: "running"` | Every logged frame after that id |
| `running`, no id or an unknown one | `status: "running"` | The whole current execution |
| `connected`: the turn finished while the client was away | `status: "connected"` | None. If the Host's copy is newer, `CONNECTED` carries it and the app merges it (`ServerTranscriptReceived`). |
| Not found | `status: "new"` | None |

The Host finds the id by searching its log from the start and resumes after the
*first* frame that carries it. An `llm_call` and its `llm_result` share one id, as
do the statuses of a `compact` or an `eval`, so a replay can start earlier than
the client expects and re-send frames it already has. The Host leaves
de-duplication to the client.

**De-duplication.** `ProtocolParser` gives each frame that becomes a chat item
(or updates one) a `dedupeKey`: a SHA-256 of the frame as the client decodes it,
without `session_id`. `AgentConnection.handleMessage` keeps the last
`MAX_SEEN_FRAMES` (4096) keys and drops a frame whose key it has seen straight
after parsing, before the session is updated and before `Waiting` or any chat
event is emitted, so a repeat changes nothing. The key is not the raw text:
through the relay every frame carries a `conn_id` that is new for each client
socket, so the same logged event reads differently after a reconnect. Nor is it
the `id` alone: the Host reuses one id for `llm_call` and `llm_result` and for
every status of a `compact` or an `eval`, so comparing ids would drop real
updates. (`tool_call` and `tool_result` share a `tool_id`, but each frame has its
own `id`.) Three kinds of frame have no key and
are never dropped: the snapshots the Host re-sends on every `CONNECT`
(`AGENT_PROFILE`, `DASHBOARD_SNAPSHOT`); `session_sync`, whose replay carries the
session as it is now and so never equals the original, and which is already safe
to receive twice (a reply's id comes from its content); and frames the client
does not decode. A dropped repeat also leaves the cursor where it was. The
item-level idempotence described above (`stableAssistantId()`, `dedupeUI()`)
stays as the second line of defence.

**Order.** `AgentConnection` handles frames one at a time, in the order they
arrive on the socket's reader thread; the Host replays in log order and finishes
the replay before any live frame; and `socketGeneration` discards late frames
from a socket the client has already replaced. Nothing sorts events, so items
are added to the chat in log order. The one deliberate exception is that cards
waiting for the user's answer (approval, `ask_user`, and similar) stay pinned to
the bottom of the list.

**Buffers.** Events reach the chat through two `SharedFlow`s:
`AgentConnection.events` and the chat bus in `ConnectionRepositoryImpl`. Both
hold `AgentConnection.EVENT_BUFFER_CAPACITY` (1024) events and drop the oldest
when full, so a stuck collector can never stall the socket. That is a bound, not
a guarantee: a replay of more than about a thousand events arriving while the
chat is busy can still lose the oldest.

**Known gaps.** The cursor and the keys of the frames already seen live in the `AgentConnection`
instance, in memory. A new instance (the Reconnect button, an app restart)
starts without them, sends no `last_msg_id`, and the Host replays the whole
current execution; only the item-level de-duplication then protects against
repeats. Persisting the cursor is not implemented.

Covered by `OutgoingMessagesTest` (the `CONNECT` wire shape), `ProtocolParserTest`
(`msgId` on every log frame and no host frame; a `dedupeKey` that ignores `conn_id`
and `session_id` and separates frames that differ), `AgentConnectionRobolectricTest`
(the cursor is sent, scoped to its session and kept out of the signed payload;
repeats are dropped once and in order, also when the relay names the new socket
differently; snapshots still arrive; `reset()` and a switch away and back; a
200-frame burst arrives whole), and `ConnectionRepositoryImplTest` (the same
burst through the chat bus).

### When a reconnect fails

A dropped socket climbs `AgentConnection`'s reconnect ladder: five tries, 2, 4,
8, 16 and 32 seconds apart (`MAX_RECONNECT_ATTEMPTS`, `reconnectBackoffMs`). Only
`CONNECTED` resets it. Three things end a try early:

| What happens on the reconnect socket | What the client does |
| --- | --- |
| `ERROR` from the relay saying the agent is offline (`Agent not connected: …`) | A failed rung, like a dropped socket. Closes the socket and climbs. No chat error per rung. |
| Any other `ERROR` in reply to its `CONNECT` (trust, signature, the clock) | Stops the ladder, closes the socket, emits `ConnectionEvent.ReconnectRefused`. The repository shows it as `ConnectionState.Error`, humanised, with Reconnect. |
| No `CONNECTED` within `CONNECTED_TIMEOUT_MS` (15 s) | A failed rung. Closes the socket and climbs. |

This applies only to a socket opened after the cycle had reached the agent: a
ladder retry, `retryNow()`, or a conversation switch. The Host keeps a socket
open after an `ERROR`, so before this (W16AAPPLE-82) no close arrived to climb
the ladder, and the repository read the `ERROR` as a business error on a live
session: the screen sat on "Reconnecting…" for good. An `ERROR` after
`CONNECTED` is still a non-fatal `ConnectionErrorOccurred`. So is the reply to
the re-`CONNECT` after an invite code on the first socket (W16AAPPLE-83), which
is not a reconnect.

**An offline agent (W16AAPPLE-79).** When the ladder runs out and the last try
ended with the relay's offline verdict, the client says so once (the relay's
reason, or `ReconnectRefused` with it if the cycle had connected before), then
tries again every `OFFLINE_POLL_INTERVAL_MS` (60 s). These tries are quiet: no
`Reconnecting` per try, so the screen keeps showing why it is not connected.
They run only while the app is on screen (`ConnectOnionApplication.isInForeground`,
passed down as `isAppVisible`). A try that comes due in the background is
skipped, and `retryNow()`, which the chat screen calls when it comes back,
goes at once with a fresh ladder. A plain network failure still stops at the
cap with `Disconnected`, as before.

Covered by `AgentConnectionRobolectricTest` (the W16AAPPLE-82 and -79
sections, on a virtual clock) and `ConnectionRepositoryImplTest` (a refused
reconnect ends in `Error` although the attempt had connected).

### CONNECTED status

`CONNECTED` answers a `CONNECT` and says what the Host holds for the session.
`ProtocolParser` reads `status` into `ServerSessionStatus` (`new`, `connected` or
`running`; anything else, or none, is `UNKNOWN`) and `server_newer` into a
boolean, and `ConnectionState.Connected` carries both to the chat screen.
`ChatViewModel.actOnHostAnswer` acts on them once the screen knows which
conversation it is on, whether it opened the socket or joined one that was
already live.

| `status` | The Host | The app |
| --- | --- | --- |
| `running` | Is mid-turn on this session and replays what the client missed (see above). | Holds the composer behind the turn exactly as if this process had sent its `INPUT`: a message typed now waits for the turn, or rides it as runtime input (see [Runtime input](#runtime-input)). A turn that a dropped connection marked Failed is un-failed, and `SESSION_STATUS` is not asked. |
| `connected` | Is idle: alive, or restored from disk. | Whatever the screen still shows in progress belongs to a turn that ended unheard, and no frame will ever finish it. It is settled the way an `OUTPUT` would settle it and saved, so the spinner does not come back with the next launch. A turn a drop already failed stays failed until the `SESSION_STATUS` reply and the replay say whether its answer arrived. |
| `new` | Has no record: no id was sent, the id is unknown (same id), or it is another caller's (a new id, which the app adopts). | The conversation stays on screen. A turn still in flight can never finish, so it is marked Failed (Retry), other spinning cards stop, and `SESSION_STATUS` is not asked. A conversation with nothing in flight is left alone, which is every first connect of a new conversation. |
| none, or `index` | An older Host, or a session-index socket. | Nothing. The `SESSION_STATUS` question after a drop is the only recovery. |

**The answer has a lifetime.** `ConnectionState.Connected` stays in place for as long
as the socket does, and every chat screen created on it starts from it, so a screen
opened an hour later would act on a turn that ended long ago. The screen therefore
acts once and calls `releaseSessionStatus()`, which sets `status` back to `UNKNOWN`
and `server_newer` to false. The repository also releases it by itself when the
connection moves to another conversation (the answer was about the old one) and when
an `OUTPUT` arrives, before the event is emitted (the turn `running` described has
ended). A release shows up as one more `Connected` state with no status, so after a
drop that failed a turn the Host may be asked `SESSION_STATUS` twice instead of
once. The second reply finds nothing left to do.

**`server_newer`.** `CONNECT` carries no `session`, so to the Host the client's copy is
empty and `server_newer` is true whenever the Host has history. `CONNECTED` then
carries the Host's `session` and `chat_items`; the app does not read `chat_items`.
It merges the assistant replies of `session.messages` into the conversation
(`ConversationHistoryUseCase.mergeServerTranscript`), matched by their
content-derived id. The merge only adds what is missing and never removes or
rewrites a local row. A message the user has not sent yet, or one that failed, is
untouched, and applying the same transcript twice, or after the live stream already
delivered the reply, adds nothing. The text in the composer is state of the input
bar alone, so no connection event touches it.

**The outbox.** A message typed while the socket is down is shown as queued ("Waiting
to send…") and parked in the connection's outbox, which flushes the moment
`CONNECTED` lands. When the screen next sees `Connected` it marks those messages
sent, which also unpins them from the bottom of the list so the reply lands below
the question. A message held behind a running turn stays queued: it is waiting for
the turn, not for the socket.

**Known gaps.**

- Tool calls, cards and footers of a turn that finished while the app was away are
  not rebuilt. The app merges the transcript's assistant replies only.
- The merge matches replies by content, so two identical replies in one
  conversation count as one.
- After `new` the Host's agent no longer has the earlier conversation while the app
  still shows it: `CONNECT` carries no history for the Host to adopt, as the web
  client's does.

Covered by `ProtocolParserTest` (`status` and `server_newer` off the frame),
`AgentConnectionRobolectricTest` (they reach the connection's event),
`ConnectionRepositoryImplTest` (they reach the state, and are released),
`ChatViewModelTest` (each status, the failed turn that stays failed, the outbox and
the merge) and `ProcessDeathResumeTest` (a relaunch against the fake Host's three
answers).

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
