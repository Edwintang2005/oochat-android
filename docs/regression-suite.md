# Regression suite

The tests that hold the features users rely on, and the behaviour that looks
unintended. Everything here is a JVM unit test, so it runs wherever
`testDebugUnitTest` runs, with no device.

```bash
./gradlew :app:testDebugUnitTest -Pregression
```

Without `-Pregression` the task runs the whole unit suite as before. The list of
classes lives in `regressionSuite` in `app/build.gradle.kts`. A class listed
there is in the suite as a whole, so a new test for one of these features
belongs in one of those classes, or its class belongs in that list.

## What it covers

| Feature | Classes | What is pinned |
|---|---|---|
| Add agent by 0x address | `AgentViewModelTest`, `AgentFormScreenComposeTest`, `AgentFormNamingTest`, `EmbeddedDiscoveryPanelComposeTest`, `AgentDiscoveryServiceTest`, `AgentRepositoryImplTest`, `AgentDiscoveryRepositoryImplTest`, `AgentMapperTest` | 0x + 64 hex and every near miss, in Setup too; a URL pasted as an address; what Save hands over (trimmed, lower-cased address, default name, relay or direct); one agent per address in any letter case, on add, edit and discovery; the first agent added becomes the default (W16AAPPLE-57 to -61, -81) |
| Direct (/ws) and relay (/ws/input) | `AgentConnectionRobolectricTest`, `AgentConnectionTest`, `ProtocolParserTest`, `ConnectionConfigTest`, `ConstantsTest`, `EncryptedPreferencesConnectionConfigRepositoryTest`, `LoadingViewModelTest` | The URL actually dialled in each mode; `to` present on relay frames and absent on direct ones, signed and unsigned; which saved configs count as relay |
| Ed25519 identity | `IdentityVectorsTest`, `KeyManagerMnemonicTest`, `KeyManagerPersistenceTest`, `KeyManagerTest`, `SettingsViewModelTest` | Phrase -> seed -> address and key -> signature, against outside test data; mint, restore, import, reset and backup; every BIP39 phrase length, spacing, and hex keys whose halves do not match (W16AAPPLE-62, -63, -64) |
| Onboarding gate | `OnboardCardsComposeTest`, `ChatEventReducerTest`, `OutgoingMessagesTest`, `ConnectionRepositoryImplTest`, `ConnectToAgentUseCaseTest`, `OnboardingViewModelTest` | No reconnect behind the user while the gate is open (failure and close); the exact `ONBOARD_SUBMIT` frame; submitting while disconnected |
| Slash-command palette | `SlashCommandMatcherTest`, `SlashCommandPaletteComposeTest` (plus `ConnectionRepositoryImplTest`) | Skills filled from the relay directory, looked up by the agent's address; a picked command is sent as plain text; hidden when disconnected |
| Dictation | `DictationMergeTest`, `InputBarDictationComposeTest`, `ChatViewModelTest`, `SpeechRecognizerIntentTest`, `SpeechRecognitionLevelTest`, `VoiceTranscriptionInstructionTest`, `VoiceTranscriptionServiceTest`, `VoiceRecorderStoreTest` | Nothing is ever sent on its own, including when the fallback path fails; one token refresh after a 401 |
| Low-balance bar | `LowBalanceBarComposeTest` (plus `ConnectionRepositoryImplTest`) | Threshold, wording at zero and below, the tap opening the purchase page, the balance reaching the live profile |
| Session resume | `ProcessDeathResumeTest` | Killed mid-stream: the relaunch CONNECTs on the same `session_id` with no `last_msg_id` and the replayed turn lands once in Room and on screen; a dropped socket names the last event it handled; a turn that finished while the app was dead arrives once, and a tool call it left running stops spinning |
| CONNECTED status | `ProtocolParserTest`, `AgentConnectionRobolectricTest`, `ConnectionRepositoryImplTest`, `ChatViewModelTest`, `ProcessDeathResumeTest` | `status` (new, connected, running) and `server_newer` read off the frame and carried to the screen, which acts on each once: running holds the composer and un-fails a dropped turn, connected settles cards left spinning, new fails a lost turn and leaves a brand-new conversation alone, a failed turn stays failed; a message typed offline is marked sent when the connection returns; the Host's newer transcript merges once, without touching a message not yet sent (W16AAPPLE-22) |

## Where the test data comes from

`IdentityVectorsTest` asserts values that did not come from this app:

- RFC 8032's own Ed25519 test vectors.
- Addresses and signatures made with python-mnemonic and PyNaCl (libsodium),
  the libraries the connectonion SDK uses.
- Signatures checked with the server's own
  `connectonion.network.host.auth.verify_signature`.

libsodium's Android `.so` cannot load on a JVM, so these tests run
`KeyManager` on an RFC 8032 stand-in (`BouncyCastleEd25519`), injected through
the `Ed25519` seam. The same file checks the stand-in against those vectors. The
device test `KeyManagerSodiumVectorsTest` checks the real binary against the same
values.

## Known issues

These tests assert what the app *should* do, wrapped in `knownIssue { }`. While
the problem is still there, the test is reported as **skipped**, and its reason
is printed to the test's stderr. Once someone fixes it, the test **fails** on
purpose. At that point, remove the wrapper, and the test guards the fix.

| Area | Issue | Test |
|---|---|---|
| Direct/relay | A direct `wss://host` URL becomes `ws://wss://host/ws`, so the app dials a host called `wss` | `AgentConnectionRobolectricTest` |
| Direct/relay | A direct URL ending in `/` is dialled at `//ws` (the Add Agent form does not trim it) | `AgentConnectionRobolectricTest` |
| Direct/relay | The saved `connectionMode` is never read. A relay-mode agent on a self-hosted relay is dialled at `<relay>/ws` | `ChatViewModelTest` |
| Slash palette | Skills from the directory are not de-duplicated, and "No description" is not dropped | `ConnectionRepositoryImplTest` |
| Slash palette | Two skills with the same name crash the composer when `/` is typed | `SlashCommandPaletteComposeTest` |
| Low balance | The amount follows the phone's locale: `$0,42` on a German phone | `LowBalanceBarComposeTest` |

## Not covered, and why

- **Which screen opens first** (Setup or Loading). This is decided inside
  `ConnectOnionApp` from the real app container. Testing it on the JVM needs a
  fake container there; `FakeAppContainer` exists only under `androidTest`.
- **Whether the low-balance bar can appear against a live agent.** The balance
  only comes from `AGENT_PROFILE`, which `AgentDiscoveryService` notes no agent
  sends, and the directory profile has no balance field. Worth confirming with
  the client before writing a test either way.
- **The recovery phrase across clients.** Android derives the key from the first
  32 bytes of the BIP39 seed. `connectonion` 1.8.8's `address.recover()` uses
  SLIP-0010 instead, so the same phrase gives a different address in the CLI.
  Which one the web client uses needs confirming.
- **Anything needing a device.** CI runs no instrumented tests, and the
  screenshot baselines are not verified (see the README). Process death is
  therefore modelled in `ProcessDeathResumeTest`: the first launch's objects
  are abandoned without a flush and the relaunch is rebuilt on the same Room
  file, rather than the app being killed on an emulator.
