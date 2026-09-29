# Installation Manual

**oochat for Android** — native ConnectOnion client.

This is a native Android application. It runs on a device or emulator and talks
to a ConnectOnion relay over WebSocket; there is no separate application backend
to run locally and no Docker image is required.

## Two ways to install — pick one

| | What you do | When to use it |
|---|---|---|
| **Approach A — pre-built APK** *(fastest — no toolchain)* | Download and `adb install`. See [§0](#0-install-the-pre-built-apk-no-build-required). | You want to test the app, not the build. |
| **Approach B — build from source** | JDK 17 + Android SDK, then `./gradlew`. See [§1](#1-environment-and-prerequisites) onward. On a machine with no SDK at all, `tools/local-android-setup/` fetches one — `./install.sh` then `./run.sh --build`. | You want to modify the app or verify the build and tests. |

Both produce the same application. Approach A takes about a minute; Approach B takes about four on a cold
Gradle cache.

**On a machine with no Android tooling at all**, `tools/local-android-setup/` automates
Approach A end to end — it installs a private SDK and emulator, creates a virtual
device, installs the app and launches it, then removes all of it on request:

```bash
cd tools/local-android-setup && ./install.sh && ./run.sh    # Windows: install.bat, run.bat
```

Each script states what it will download, how big it is and where it goes, then
waits for confirmation. Everything lands in `tools/local-android-setup/.local/` — no
`PATH` change, no shell profile edit, nothing outside that directory — and
`./uninstall.sh` deletes the lot. See
[`tools/local-android-setup/README.md`](tools/local-android-setup/README.md). The rest of this
document is the manual equivalent.

---

## 0. Install the pre-built APK (no build required)

Installable APKs are attached to GitHub Releases:

> [**the latest release**](https://github.com/openonion/oochat-android/releases/latest)

Download the `.apk` from the release assets. The `.sha256` file beside it lets
you verify that the download is intact.

Then, with a device connected over USB (debugging enabled) or an emulator
running:

```bash
adb install -r openonion-android-*.apk
```

On an emulator you can skip `adb` entirely and drag the APK file onto the
emulator window. On a physical phone, copying the file across and opening it
from the file manager works too, allowing "install from unknown sources" when
prompted.

**This is a debug-signed build, deliberately.** Signing is configured but no
keystore is committed — that would mean committing its password too — so on a
fresh clone `./gradlew assembleRelease` emits `app-release-unsigned.apk`, which
Android refuses to install. Supply a `keystore.properties` (see
[§4](#4-configuration-and-secrets)) and the same command produces a signed one.
The debug variant carries the standard Android debug signature, which is what
makes it installable on any device without shipping a private key. Its
behaviour is identical; it is not minified.

If `adb install` fails with no message (seen on some Huawei devices), see
[§6](#6-troubleshooting).

**How the APK is produced.** `.github/workflows/release-apk.yml` builds it,
runs the audit, formatting and unit-test gates against the same commit, then
attaches the APK and its SHA-256 to a Release when a matching `v*` tag is
pushed. A manual run is a dry run and retains the same files as Actions
artifacts without creating a tag or Release.

---

## 1. Environment and prerequisites

| | Requirement |
|---|---|
| **OS** | macOS or Linux, verified. Windows untested — nothing is platform-specific, but nobody has run it. |
| **JDK** | **17** — the version this project is built and verified on. The build pins `jvmTarget = 17`. A newer JDK may still build it (21 does), but no figure in this document was produced on one. |
| **Android SDK** | Platform **API 36** installed (`compileSdk = 36`). |
| **Android Studio** | Optional. The Gradle wrapper is self-contained. |
| **Gradle** | Do **not** install. `./gradlew` fetches and pins Gradle 8.13. |
| **Device** | Android 8.0 / API 26 or newer (`minSdk = 26`), plus internet. A physical device is preferred — camera, microphone and notifications are what an emulator models least well. |
| **Disk** | ~2 GB for the Gradle cache on a first build, plus the SDK. |

```bash
java -version    # expect 17.x
adb --version    # only needed to install onto a device
```

If Java is not 17, point this shell at one rather than editing the build:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS
```

---

## 2. Obtain and configure the project

**1.** Clone and enter the repository:

```bash
git clone https://github.com/openonion/oochat-android.git
cd oochat-android
```

**2.** Point the build at your Android SDK — one of these, not both:

```bash
# Either: create local.properties (Android Studio writes this on first open)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
echo "sdk.dir=$HOME/Android/Sdk"         > local.properties   # Linux

# Or: export it
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

There is no dependency-install step and no configuration file to fill in.
Relay and agent settings are chosen **in the app at runtime**, not at build
time — see §4.

---

## 3. Build and run

**3.** Build the debug APK:

```bash
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

**4.** Start an emulator, or connect a device with USB debugging on, and
confirm it is visible:

```bash
adb devices     # the device must be listed as "device", not "unauthorized"
```

**5.** Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` keeps existing app data. Omit it for a true first-run state.

**6.** Launch it from the launcher, or:

```bash
adb shell am start -n ai.openonion.oochat/.MainActivity
```

Steps 3 and 5 can be replaced by `./gradlew :app:installDebug` when the device
is attached to the build machine.

**Release builds** (`./gradlew :app:assembleRelease`) come out **unsigned** on a
fresh clone — the signing config is checked in, but it only activates when a
`keystore.properties` is present, and the keystore and its passwords are not in
git. Android refuses to install an unsigned APK, so use the debug one unless you
have your own key. See §4.

---

## 4. Configuration and secrets

**Nothing to configure before building.** No secret is compiled into the APK
and there is no file to fill in. What is configurable — the relay, the agent,
an optional per-agent API key — the user sets inside the running app, and it
is kept in encrypted storage on the device.

| Item | Status |
|---|---|
| Environment variables | **None** — nothing is read at build or run time |
| `.env` / config file | **None** — no template to copy, nothing to fill in |
| API keys or tokens | **None compiled in.** No `buildConfigField`, no `System.getenv` |
| Account / login | **None.** Identity is an Ed25519 key generated on the device at first run, stored in `EncryptedSharedPreferences` under an AES-256 master key held in the Android Keystore |
| Signing keystore | **Not in the repository**, by design. The wiring is: `app/build.gradle.kts` reads a git-ignored `keystore.properties` (see `keystore.properties.example`) and leaves the release unsigned when it is absent |

`local.properties` (§2) is the only machine-local file. It holds the SDK path
and nothing else; it is git-ignored because the path differs per machine, not
because it is sensitive. `.gitignore` also covers `.env*`, `secrets.properties`
and keystores so that no secret can be committed later.

Everything configurable is set **inside the app**:

| Setting | Where | Default |
|---|---|---|
| Relay / server URL | Onboarding, or the agent form | `https://oo.openonion.ai` |
| Direct connection | Mode toggle on the same panel | off (relay) |
| API key (optional) | Agent form | empty — stored in `EncryptedSharedPreferences`, excluded from cloud backup and device transfer, and never sent anywhere but the agent it belongs to |

---

## 5. Verification

**The install is working when all of the following hold.**

**7.** Onboarding appears on first launch and lists agents found on the default
relay.

**8.** Tapping an agent (or **Connect**) routes through Loading to Chat, and the
top bar shows the agent name over a **green "Connected"** dot.

**9.** A sent message gets a reply in the conversation.

### Two agents you can connect to

Both are live on the default relay, so you do not need to run an agent of your
own. Paste the address into Onboarding (or **Add agent** in the nav drawer);
if a card asks for an invite code, the code is `OpenOnion`.

| Agent | Address | What it is |
|---|---|---|
| `livecheck` | `0xb7062bd7d3938a320956fde55c59cb0436f132df8bd60f129d8ab10d159cc207` | Minimal — one `greet` tool. Best for checking that connect, send and reply work at all. |
| `my-agent` | `0xb73ccb0e7132d84971fcee6d797eaaddc8a029608ed880b074ef64fb8680d124` | 46 tools, mostly browser automation. Use it to see tool cards, approval prompts and the approval-mode chip do something real. |

Two things that are working as intended and can read as faults:

- **The `/` command palette stays empty on both.** It lists the skills an agent
  publishes to the relay's directory, fetched from `/api/agents/<address>` on
  connect. Both of these agents publish an empty `skills` list — verified
  against the live directory on 2026-08-09 — so there is nothing for the palette
  to show. An agent that publishes skills will populate it.
- **Dictation may pause for a couple of seconds the first time**, showing
  "Preparing…". On a device with no usable speech recogniser the app spends that
  window proving the recogniser is dead before falling back to server
  transcription, then remembers the verdict for the rest of the session.

To verify the build itself rather than the app:

```bash
./gradlew :app:testDebugUnitTest            # expect 1384 tests, 0 failures
./gradlew :app:ktlintCheck                  # CI gate; must be clean
./gradlew :app:verifyRoborazziDebug         # 94 screenshot baselines, 10 classes
./gradlew :app:jacocoTestReport             # coverage → app/build/reports/jacoco
```

⚠️ Run `verifyRoborazziDebug` in **its own** invocation. Combined with
`testDebugUnitTest` in one command, Roborazzi narrows the shared test task to the
screenshot classes only: the unit suite never runs, and the command still reports
`BUILD SUCCESSFUL`. Confirm the count from the report rather than the exit code —
a four-figure total means the suite ran, a couple of dozen means you got the
filter.

The HTML report lands in `app/build/reports/tests/testDebugUnitTest/index.html`.

### What is tested, and how

1384 tests across 133 classes run on the JVM — no device, no emulator. Android
framework types are supplied by **Robolectric**, so Room migrations, Compose
rendering and `ConnectivityManager` callbacks are all exercised in the same
suite rather than deferred to a manual pass.

| Layer | Classes |
|---|---|
| `ui/` | 51 |
| `data/` | 35 |
| `domain/` | 22 |
| `network/` | 14 |
| `util/`, `crypto/`, `di/`, `architecture/`, root | 11 |
| **Total** | **133** |

Three conventions are worth calling out, because they are what the coverage
number does not show:

- **External boundaries are faked, not mocked-out.** `AgentConnection` takes a
  `WebSocketFactory`, so the tests drive real frames through the real parser
  against a socket that never leaves the JVM. Sad paths — a dropped socket
  mid-turn, an `ERROR` frame on an established session, a server that returns
  a session id we no longer recognise — are ordinary test cases, not an
  afterthought.
- **New tests are reverse-verified.** For a bug fix, the production change is
  reverted and the new test is confirmed to *fail* before being restored. A
  test that passes both with and without the fix documents nothing, and two
  such tests were found and removed this way during development.
- **Screenshots are assertions.** The 94 Roborazzi baselines are committed, so
  `./gradlew :app:verifyRoborazziDebug` turns an unintended visual change into
  a failure instead of something someone notices in a demo. Run it locally
  before pushing: CI does not, because the task reconfigures
  `testDebugUnitTest` with a screenshot-only filter and the two cannot share a
  `gradlew` invocation (see the test-count guard in `.github/workflows/build.yml`).

The one thing not covered automatically is the round trip against a live
ConnectOnion relay: it is a third-party service this project neither owns nor
can stand up in CI. Steps 7–9 above are that check, done by hand.

`app/src/androidTest` holds 27 instrumented tests that need a device or
emulator (`./gradlew :app:connectedDebugAndroidTest`). **21 run; 6 are
`@Ignore`d** — three whole classes, of 3, 2 and 1 tests, disabled for the
reason given under *What is tested, and how* in README.md. They are not part of
the gate above and are not required to assess the build.

### Reproduction record

Run from a clean clone on 2026-08-03, macOS arm64, JDK 17.0.20, at commit
`748ad29`, with an isolated `GRADLE_USER_HOME` so nothing was reused:

| Step | Result |
|---|---|
| `git clone` | 2 s |
| `./gradlew :app:assembleDebug` — cold cache | **3 m 45 s**, `38 actionable tasks: 38 executed`, `BUILD SUCCESSFUL` |
| Gradle cache after the build | 1.6 GB |
| APK produced | `app-debug.apk`, about 27 MB |
| Same build, warm cache | 4 s (`21 from cache`) |

The first build is slow because it downloads Gradle 8.13, the Android Gradle
Plugin and the whole dependency graph. Budget four minutes and a working
network; later builds are seconds.

Verified again on 2026-08-10: `1384 tests, 0 failures`.

---

## 6. Troubleshooting

Only failures actually hit while building this project.

| Symptom | Cause | Fix |
|---|---|---|
| `Unsupported class file major version` | Not JDK 17 | `export JAVA_HOME=...` per §1 |
| `Failed to find target with hash string 'android-36'` | API 36 platform missing | `sdkmanager "platforms;android-36"` |
| `SDK location not found` | No `local.properties`, no `ANDROID_HOME` | §2 step 2 |
| `adb devices` shows `unauthorized` | USB-debugging prompt not accepted | Unlock the device and accept it |
| `adb: failed to install` with **no message** | Device rejects streamed install (seen on Huawei) | `adb push app-debug.apk /data/local/tmp/app.apk && adb shell pm install -r -t /data/local/tmp/app.apk` |
| Release APK will not install | It is unsigned | Expected — §4. Use the debug APK |
| First build appears to hang | Downloading Gradle + dependencies | Expected once; ~4 minutes |
| ktlint fails on import order | Formatting only | `./gradlew :app:ktlintMainSourceSetFormat` |
| App says **"Can't reach this server"** | Relay unreachable from this network | Check connectivity. Distinct from **"No agents on this server"**, which means the server answered and listed none |

---

## 7. Next step

Installing it is not the same as knowing what it does. Once §5 passes:

| Go to | For |
|---|---|
| [`USER_GUIDE.md`](USER_GUIDE.md) | **How to use the app.** Written for someone holding the phone: connecting, slash commands, dictation, attachments, the permission modes, conversations, and what each error message means. Start at [§1 Connecting to an agent](USER_GUIDE.md#1-connecting-to-an-agent). |
| [`docs/architecture.md`](docs/architecture.md) | **Where everything is.** Module boundaries, data flow, and the architectural rules enforced by tests. |
| [`README.md`](README.md) | **How it is built.** Tech stack, module layout, protocol notes, CI, and the testing approach in full. |

Two behaviours that read as faults on a first run are explained at the end of
[§5](#5-verification) — an empty `/` palette, and a pause on first dictation.
Neither is a bug, and both are worth reading before judging them.
