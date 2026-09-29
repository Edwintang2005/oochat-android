# Automated local Android setup

Three scripts that take a machine with no Android tooling on it and end with the
app running, then remove every trace on request. Nothing here needs an
administrator password, and nothing is installed system-wide.

If you would rather do it by hand, or want to know why any of this is the way it
is, [`INSTALL.md`](../../INSTALL.md) covers the same ground manually.

## Quick start

**macOS / Linux**

```bash
cd tools/local-android-setup
./install.sh
./run.sh
```

**Windows**

```
cd tools\local-android-setup
install.bat
run.bat
```

Each script prints exactly what it is about to do — what gets downloaded, how
big it is, where it goes, and what is left untouched — and waits for you to
confirm before doing any of it. Answering anything other than `y` exits without
having changed anything. Pass `-y` to skip the prompt.

## What each script does

| Script | What it does |
|---|---|
| `install.sh` / `install.bat` | Downloads a private Android SDK, the emulator, an API 36 system image, and creates one virtual device. 10–25 minutes, about 1.8 GB of download. |
| `run.sh` / `run.bat` | Boots that virtual device — or uses a physical phone if one is attached — installs the app and launches it. Prints a live agent address to test against. |
| `uninstall.sh` / `uninstall.bat` | Removes the app from the device and deletes everything `install` downloaded. |

## Where things go, and what is left alone

Everything lands in one directory:

```
tools/local-android-setup/.local/
├── android-sdk/      the SDK, platform-tools, emulator, system image
├── avd/              the virtual device
├── android-home/     the SDK tools' own per-user state, incl. adb's keypair
├── gradle-home/      Gradle's cache — only if you build with --build
├── env.sh|env.ps1    paths recorded for the other two scripts
└── emulator.log      emulator output, if it was started
```

Deleting that directory undoes the whole thing, which is all `uninstall` does.

Nothing is written outside it. No `PATH` change, no shell profile edit, and no
system or user environment variable — every variable the scripts need is set
for their own process only.

Two directories that normally get written by this kind of tooling are
deliberately redirected into `.local/` so that they are removable: the scripts
set `ANDROID_USER_HOME`, so `adb` and `sdkmanager` keep their per-user state
(including the keypair `adb` generates on first use) out of `~/.android`; and
`run` sets `GRADLE_USER_HOME` before invoking Gradle, so a `--build` run does
not leave a multi-gigabyte cache in `~/.gradle`. Without those two, "uninstall
removes everything" would not be true.

## If a step fails

Every failure prints the official download page for whatever is missing and the
exact command to do that step by hand, then stops without leaving anything
half-configured. Re-running the script after fixing it picks up where it left
off; already-installed packages are skipped.

The one thing the scripts deliberately do not install is **JDK 17**. It is only
needed to build from source or run the test suite — installing and running the
app does not need it — and installing a JDK properly is platform-specific enough
that doing it silently would be worse than pointing at
[Adoptium](https://adoptium.net/temurin/releases/?version=17).

## Options

The two platforms take the same options in their own idiom — the `.bat` files
are launchers that pass arguments through to PowerShell.

| Effect | macOS / Linux | Windows |
|---|---|---|
| Skip the confirmation prompt | `-y` or `--yes` | `-y` |
| Build from source instead of using the pre-built APK (needs JDK 17) | `./run.sh --build` | `run.bat -Build` |
| Reuse an SDK already on the machine rather than downloading one | `./install.sh --use-system-sdk` | `install.bat -UseSystemSdk` |
| Use a different platform level, if API 36 has no image for your CPU | `ANDROID_API=35 ./install.sh` | `install.bat -AndroidApi 35` |

With `--use-system-sdk` / `-UseSystemSdk`, missing packages are added to the
existing SDK and `uninstall` will not delete it.

## Why Windows gets a `.ps1` alongside each `.bat`

The `.bat` files are three-line launchers; the logic lives in the `.ps1` beside
each one. Batch cannot express this reliably — in `if cond cmd1 & cmd2` the
second command runs unconditionally, and a label inside a parenthesised block
breaks the block — both of which are silent failures rather than errors.
PowerShell ships with every supported version of Windows, and the launchers pass
`-ExecutionPolicy Bypass` for that one process only, so no machine-wide
execution policy is changed.

## Using a physical phone instead

Enable USB debugging, plug it in, accept the prompt on the phone, then run
`run.sh` / `run.bat`. It notices the attached device and skips the emulator
entirely — `install` is then only needed for `adb`.

An emulator has no camera and no microphone, so photo capture and dictation can
only be exercised on a real phone. Everything else works on both.
