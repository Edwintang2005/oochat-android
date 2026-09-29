#!/usr/bin/env bash
#
# ConnectOnion Android — one-shot local environment setup (macOS/Linux).
#
# Everything this installs lands under tools/local-android-setup/.local/. No PATH is
# modified and no shell profile is touched. ANDROID_USER_HOME and (in run.sh)
# GRADLE_USER_HOME are redirected into .local/ as well, so the SDK tools and
# Gradle do not write to ~/.android or ~/.gradle either — without that,
# "uninstall removes everything" would not be true. ./uninstall.sh removes the lot.
#
set -euo pipefail

SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SETUP_DIR/../.." && pwd)"
LOCAL_DIR="$SETUP_DIR/.local"
SDK_DIR="$LOCAL_DIR/android-sdk"
AVD_DIR="$LOCAL_DIR/avd"
AVD_NAME="openonion-android"
ANDROID_API="${ANDROID_API:-36}"
CMDLINE_BUILD="13114758"

ASSUME_YES=0
USE_SYSTEM_SDK=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
    --use-system-sdk) USE_SYSTEM_SDK=1 ;;
    -h|--help)
      cat <<EOF
Usage: ./install.sh [options]

  -y, --yes           Skip the confirmation prompt.
      --use-system-sdk  Reuse an Android SDK already on this machine instead of
                      downloading one. Missing packages are added to it, and
                      uninstall.sh will NOT delete it.
  -h, --help          Show this message.

Environment overrides: ANDROID_API (default 36)
EOF
      exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# Print why a step failed, where to get the missing piece by hand, and stop.
fail() {
  printf '\n\033[31m✗ %s\033[0m\n\n' "$1"
  shift
  if [ "$#" -gt 0 ]; then
    echo "What to do:"
    for line in "$@"; do echo "    $line"; done
    echo
  fi
  if [ -d "$LOCAL_DIR" ]; then
    echo "Nothing outside $LOCAL_DIR was changed."
    echo "Re-running this script resumes where it stopped; ./uninstall.sh starts over."
  else
    echo "Nothing has been downloaded or changed."
  fi
  exit 1
}

case "$(uname -s)" in
  Darwin) HOST_OS="mac";   HOST_LABEL="macOS" ;;
  Linux)  HOST_OS="linux"; HOST_LABEL="Linux" ;;
  *) fail "Unsupported operating system: $(uname -s). This script covers macOS and Linux." \
          "On Windows, run install.bat in this same folder instead." ;;
esac

case "$(uname -m)" in
  arm64|aarch64) ABI="arm64-v8a" ;;
  x86_64|amd64)  ABI="x86_64" ;;
  *) fail "Unsupported CPU architecture: $(uname -m)." \
          "The Android emulator ships images for arm64-v8a and x86_64 only." ;;
esac

SYSTEM_IMAGE="system-images;android-${ANDROID_API};google_apis;${ABI}"
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-${HOST_OS}-${CMDLINE_BUILD}_latest.zip"

# --- Reuse an existing SDK, if asked and one is actually there ----------------
REUSED_SDK=0
if [ "$USE_SYSTEM_SDK" = "1" ]; then
  # Each candidate is tested on its own merits. Collapsing them with `:-` meant
  # an ANDROID_SDK_ROOT pointing somewhere that does not exist was silently
  # replaced by an unrelated default, reusing an SDK the user never named.
  FOUND=""
  for CANDIDATE in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
                   "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$CANDIDATE" ] || continue
    if [ -x "$CANDIDATE/platform-tools/adb" ]; then
      FOUND="$CANDIDATE"
      break
    fi
  done
  if [ -n "$FOUND" ]; then
    SDK_DIR="$FOUND"
    REUSED_SDK=1
  else
    fail "--use-system-sdk was given, but no Android SDK was found." \
         "Looked for platform-tools/adb under:" \
         "  ${ANDROID_SDK_ROOT:-<ANDROID_SDK_ROOT unset>}" \
         "  ${ANDROID_HOME:-<ANDROID_HOME unset>}" \
         "  $HOME/Library/Android/sdk   (macOS default)" \
         "  $HOME/Android/Sdk           (Linux default)" \
         "Re-run without --use-system-sdk to download a private copy instead."
  fi
fi

# --- Tell them what is about to happen, then wait ----------------------------
clear 2>/dev/null || true
bold "ConnectOnion Android — environment setup"
echo
echo "This prepares a machine to install and run the app locally."
echo "Read this before answering; nothing has been downloaded or changed yet."
echo
bold "What gets installed"
if [ "$REUSED_SDK" = "1" ]; then
  info "Reusing the Android SDK already at:"
  info "  $SDK_DIR"
  info "Only missing packages below are added to it. It is NOT deleted on uninstall."
else
  info "Android command-line tools    (~150 MB download)"
fi
info "Platform API ${ANDROID_API}               (~80 MB)"
info "Platform-tools / adb          (~15 MB)"
info "Android emulator              (~350 MB)"
info "System image ${SYSTEM_IMAGE}"
info "                              (~1.2 GB — the big one)"
info "One virtual device named '${AVD_NAME}'"
echo
bold "Where it goes"
if [ "$REUSED_SDK" = "1" ]; then
  info "SDK packages : $SDK_DIR  (existing, shared)"
else
  info "SDK          : $SDK_DIR"
fi
info "Virtual device: $AVD_DIR"
echo
bold "What is NOT touched"
info "No changes to PATH, ~/.bashrc, ~/.zshrc or any shell profile."
info "No changes to ~/.android or ~/.gradle — the SDK tools are pointed at"
info "  a copy inside .local/, so nothing of yours is written to or reused."
info "No administrator password required, nothing installed system-wide."
echo
bold "Removing it later"
info "./uninstall.sh   — deletes everything above and the app from the device."
echo
bold "Rough totals"
# Measured, not estimated: 5.7 GB in .local/ after a clean run on macOS arm64,
# API 36. The ~4 GB this used to claim was low by 40%, which is the wrong
# direction to be wrong in when someone is deciding whether to let it start.
info "Download : ~1.8 GB     Disk after install : ~5.7 GB"
info "Building from source instead of using a pre-built APK adds a further"
info "  ~1.5 GB of Gradle cache under .local/, also removed by ./uninstall.sh."
info "Time     : 10–25 minutes, mostly the system image download."
echo
echo "Host detected: $HOST_LABEL / $(uname -m)  →  emulator ABI $ABI"
echo

if [ "$ASSUME_YES" != "1" ]; then
  printf 'Proceed? [y/N] '
  read -r reply
  case "$reply" in
    [yY]|[yY][eE][sS]) ;;
    *) echo "Cancelled. Nothing was downloaded or changed."; exit 0 ;;
  esac
fi

mkdir -p "$LOCAL_DIR" "$AVD_DIR"

# --- Java (only needed to build from source, not to install the APK) ---------
step "Checking for a Java runtime"
JAVA_OK=0
if command -v java >/dev/null 2>&1; then
  # `|| true` is load-bearing. macOS ships a /usr/bin/java stub that exists on
  # PATH but exits non-zero when no JDK is installed, and under `set -e` a bare
  # assignment from a failing command substitution kills the script outright —
  # silently, right after we have told the user this check is not fatal.
  JAVA_RAW="$(java -version 2>&1 | head -1 || true)"
  JAVA_MAJOR="$(printf '%s' "$JAVA_RAW" | sed -n 's/.*version "\([0-9]*\).*/\1/p' || true)"
  if [ "${JAVA_MAJOR:-0}" = "17" ]; then
    info "Found: $JAVA_RAW"
    JAVA_OK=1
  else
    info "Found $JAVA_RAW — the Gradle build needs exactly JDK 17."
  fi
else
  info "No java on PATH."
fi
if [ "$JAVA_OK" = "0" ]; then
  info ""
  info "This is not fatal. JDK 17 is needed only to build from source or to run"
  info "the test suite. Installing and running the pre-built APK does not need it."
  info "If you do want to build:  https://adoptium.net/temurin/releases/?version=17"
  info ""
fi

# --- Command-line tools -------------------------------------------------------
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_DIR/cmdline-tools/latest/bin/avdmanager"

if [ ! -x "$SDKMANAGER" ]; then
  step "Downloading the Android command-line tools"
  ZIP="$LOCAL_DIR/cmdline-tools.zip"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --progress-bar -o "$ZIP" "$CMDLINE_URL" \
      || fail "Download failed: $CMDLINE_URL" \
              "Download that file by hand, then unzip it so that this path exists:" \
              "  $SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
              "All command-line tool builds: https://developer.android.com/studio#command-line-tools-only"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress -O "$ZIP" "$CMDLINE_URL" \
      || fail "Download failed: $CMDLINE_URL" \
              "All command-line tool builds: https://developer.android.com/studio#command-line-tools-only"
  else
    fail "Neither curl nor wget is available, so nothing can be downloaded." \
         "Install one of them, or fetch this by hand:" \
         "  $CMDLINE_URL" \
         "and unzip it to $SDK_DIR/cmdline-tools/latest/"
  fi

  step "Unpacking the command-line tools"
  command -v unzip >/dev/null 2>&1 \
    || fail "unzip is not installed, so the archive cannot be unpacked." \
            "Install unzip (Debian/Ubuntu: sudo apt install unzip), then re-run."
  rm -rf "$LOCAL_DIR/unpack"
  mkdir -p "$LOCAL_DIR/unpack" "$SDK_DIR/cmdline-tools"
  unzip -q "$ZIP" -d "$LOCAL_DIR/unpack" \
    || fail "The downloaded archive is corrupt." "Delete $ZIP and re-run this script."
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$LOCAL_DIR/unpack/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$LOCAL_DIR/unpack" "$ZIP"
  info "Installed to $SDK_DIR/cmdline-tools/latest"
else
  step "Command-line tools already present — skipping download"
fi

[ -x "$SDKMANAGER" ] || fail "sdkmanager is missing after unpacking, which should not happen." \
  "Delete $LOCAL_DIR and run this script again."

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_AVD_HOME="$AVD_DIR"
# Keeps the tools' own per-user state — repositories.cfg, adb's keypair, caches —
# inside .local/ rather than ~/.android, which is what makes the "nothing outside
# this directory" promise true.
#
# ANDROID_USER_HOME only. Setting ANDROID_SDK_HOME as well — on the reasoning
# that it is the deprecated predecessor and older tooling still reads it — broke
# AVD creation outright:
#
#   Error: An error occurred while creating AVD: Can't locate Android SDK
#   installation directory for the AVD .ini file.
#
# avdmanager in these command-line tools resolves the SDK through
# ANDROID_SDK_HOME when it is set, and the value here is .local/, whose SDK is a
# level down in .local/android-sdk. Pointing it at the SDK directory instead does
# not help — any value fails, because the variable no longer means what its name
# suggests. Unsetting it is the fix.
#
# It failed at the very last step, after the ~1.8 GB of downloads, and the error
# was swallowed by a >/dev/null on the avdmanager call, so the script reported
# only "Could not create the virtual device."
export ANDROID_USER_HOME="$LOCAL_DIR/android-home"
unset ANDROID_SDK_HOME
mkdir -p "$ANDROID_USER_HOME"

# --- Licences and packages ----------------------------------------------------
step "Accepting SDK licences"
# Confirmations come from a bounded file, not a pipe from `yes`. Piping has two
# problems: `yes` is killed by SIGPIPE when sdkmanager stops reading, so the
# pipeline reports 141 under pipefail whether or not anything went wrong and
# sdkmanager's real status is buried in PIPESTATUS; and an unbounded producer
# deadlocks outright against anything that reads stdin to EOF.
LICENCE_FEED="$LOCAL_DIR/.licence-feed"
yes 2>/dev/null | head -n 100 > "$LICENCE_FEED" || true
LICENCE_RC=0
"$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses < "$LICENCE_FEED" >/dev/null 2>&1 \
  || LICENCE_RC=$?
rm -f "$LICENCE_FEED"
if [ "$LICENCE_RC" -ne 0 ]; then
  fail "Could not accept the SDK licences — sdkmanager exited ${LICENCE_RC}." \
       "Nothing can be installed until they are accepted, so this stops here" \
       "rather than failing on the first package and blaming that instead." \
       "Accept them by hand, answering y to each prompt:" \
       "  \"$SDKMANAGER\" --sdk_root=\"$SDK_DIR\" --licenses" \
       "Reference: https://developer.android.com/tools/sdkmanager"
fi
info "Done."

step "Installing SDK packages (this is the long part)"
PACKAGES=("platform-tools" "platforms;android-${ANDROID_API}" "emulator" "$SYSTEM_IMAGE")
for pkg in "${PACKAGES[@]}"; do
  info "→ $pkg"
  if ! "$SDKMANAGER" --sdk_root="$SDK_DIR" "$pkg" >/dev/null; then
    case "$pkg" in
      "$SYSTEM_IMAGE")
        fail "Could not install the system image: $pkg" \
             "API ${ANDROID_API} may not have an image for $ABI yet." \
             "Try a different level, e.g.:  ANDROID_API=35 ./install.sh" \
             "Or list what is available:" \
             "  $SDKMANAGER --sdk_root=$SDK_DIR --list | grep system-images" ;;
      *)
        fail "Could not install: $pkg" \
             "Retry, or install it by hand with:" \
             "  $SDKMANAGER --sdk_root=$SDK_DIR \"$pkg\"" \
             "SDK manager reference: https://developer.android.com/tools/sdkmanager" ;;
    esac
  fi
done
info "All packages installed."

# --- Virtual device -----------------------------------------------------------
step "Creating the virtual device '$AVD_NAME'"
if [ -f "$AVD_DIR/$AVD_NAME.ini" ]; then
  info "Already exists — leaving it alone."
else
  # avdmanager's own words are kept, not discarded. Both attempts used to end in
  # >/dev/null 2>&1, so a failure here produced "Could not create the virtual
  # device." and nothing else — after twenty minutes and 1.8 GB, at the one step
  # where the reason is the only useful thing to have. The log is shown only when
  # both attempts have failed, so a working run stays quiet.
  AVD_LOG="$LOCAL_DIR/avd-create.log"
  : > "$AVD_LOG"
  # A half-created AVD directory from a previous failure makes the retry fail for
  # a second, unrelated reason.
  rm -rf "$AVD_DIR/$AVD_NAME.avd"
  if ! echo "no" | "$AVDMANAGER" create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device "pixel_6" \
        --path "$AVD_DIR/$AVD_NAME.avd" >>"$AVD_LOG" 2>&1; then
    rm -rf "$AVD_DIR/$AVD_NAME.avd"
    echo "--- retry without --device pixel_6 ---" >>"$AVD_LOG"
    if ! echo "no" | "$AVDMANAGER" create avd \
          --name "$AVD_NAME" \
          --package "$SYSTEM_IMAGE" \
          --path "$AVD_DIR/$AVD_NAME.avd" >>"$AVD_LOG" 2>&1; then
      echo
      echo "avdmanager said:"
      sed 's/\r/\n/g' "$AVD_LOG" | grep -v '^\[' | grep -v '^[[:space:]]*$' | tail -15
      fail "Could not create the virtual device." \
           "The full log is at $AVD_LOG" \
           "Create one by hand with:" \
           "  ANDROID_AVD_HOME=$AVD_DIR ANDROID_SDK_ROOT=$SDK_DIR \\" \
           "  $AVDMANAGER create avd -n $AVD_NAME -k \"$SYSTEM_IMAGE\"" \
           "Reference: https://developer.android.com/tools/avdmanager"
    fi
  fi
  # avdmanager can exit 0 having written nothing usable, and run.sh needs the
  # .ini, not the directory beside it.
  [ -f "$AVD_DIR/$AVD_NAME.ini" ] \
    || fail "avdmanager reported success but wrote no $AVD_NAME.ini." \
            "The full log is at $AVD_LOG" \
            "Without that file the emulator cannot find the device."
  info "Created at $AVD_DIR/$AVD_NAME.avd"
fi

# --- Record the environment for run.sh / uninstall.sh -------------------------
cat > "$LOCAL_DIR/env.sh" <<EOF
# Written by install.sh — read by run.sh and uninstall.sh. Safe to delete.
export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_AVD_HOME="$AVD_DIR"
export ANDROID_USER_HOME="$LOCAL_DIR/android-home"
# Deliberately not ANDROID_SDK_HOME — see the note above where it is unset.
export PATH="$SDK_DIR/platform-tools:$SDK_DIR/emulator:\$PATH"
CONNECTONION_AVD_NAME="$AVD_NAME"
CONNECTONION_REPO_ROOT="$REPO_ROOT"
CONNECTONION_SDK_REUSED="$REUSED_SDK"
CONNECTONION_APP_ID="ai.openonion.oochat"
EOF

step "Setup complete"
echo
bold "Next step"
info "./run.sh    — boots the virtual device, installs the app, and launches it."
info "            Attach a physical phone first and it uses that instead."
echo
bold "When you are finished"
info "./uninstall.sh   — removes everything this script created."
echo
