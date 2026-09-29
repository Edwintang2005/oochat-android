#!/usr/bin/env bash
#
# ConnectOnion Android — boot a device, install the app, launch it (macOS/Linux).
#
# Uses a physical phone if one is attached, otherwise the virtual device that
# install.sh created. Run install.sh first.
#
set -euo pipefail

SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DIR="$SETUP_DIR/.local"
ENV_FILE="$LOCAL_DIR/env.sh"

ASSUME_YES=0
BUILD_FROM_SOURCE=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
    --build) BUILD_FROM_SOURCE=1 ;;
    -h|--help)
      cat <<EOF
Usage: ./run.sh [options]

  -y, --yes     Skip the confirmation prompt.
      --build   Build the APK from source with Gradle instead of using the
                pre-built one. Needs JDK 17.
  -h, --help    Show this message.
EOF
      exit 0 ;;
    *) echo "Unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() {
  printf '\n\033[31m✗ %s\033[0m\n\n' "$1"
  shift
  if [ "$#" -gt 0 ]; then
    for line in "$@"; do echo "    $line"; done
    echo
  fi
  exit 1
}

[ -f "$ENV_FILE" ] || fail "Setup has not been run yet." \
  "Run this first, in the same folder:" \
  "  ./install.sh"

# shellcheck source=/dev/null
. "$ENV_FILE"

REPO_ROOT="$CONNECTONION_REPO_ROOT"
AVD_NAME="$CONNECTONION_AVD_NAME"
APP_ID="$CONNECTONION_APP_ID"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"

[ -x "$ADB" ] || fail "adb is missing from the SDK." "Re-run ./install.sh to repair it."

# --- Which APK ----------------------------------------------------------------
APK=""
if [ "$BUILD_FROM_SOURCE" = "0" ]; then
  # Prefer the shipped build, genuinely newest first if there are several —
  # glob order is alphabetical, which is not the same thing and would happily
  # install a stale leftover.
  APK="$(ls -t "$REPO_ROOT"/apk/*.apk 2>/dev/null | head -1 || true)"
  if [ -z "$APK" ] && [ -f "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  fi
fi

# --- Say what is about to happen ---------------------------------------------
clear 2>/dev/null || true
bold "ConnectOnion Android — install and launch"
echo
bold "What this does"
if [ -n "$APK" ]; then
  info "1. Uses the pre-built APK:"
  info "     $APK"
else
  info "1. Builds the APK from source with Gradle (needs JDK 17)."
  info "     This takes about four minutes the first time."
fi
info "2. Uses an attached phone if there is one, otherwise boots the virtual"
info "     device '$AVD_NAME' created by install.sh."
info "3. Installs the app onto it and opens it."
echo
bold "What is NOT touched"
info "Only the app '$APP_ID' is installed on the device."
info "Nothing on this computer changes outside $LOCAL_DIR."
echo

if [ "$ASSUME_YES" != "1" ]; then
  printf 'Proceed? [y/N] '
  read -r reply
  case "$reply" in
    [yY]|[yY][eE][sS]) ;;
    *) echo "Cancelled."; exit 0 ;;
  esac
fi

# --- Build, if that is the route ---------------------------------------------
if [ -z "$APK" ]; then
  step "Building the APK from source"
  command -v java >/dev/null 2>&1 \
    || fail "No Java runtime found, and the build needs JDK 17." \
            "Install it from:  https://adoptium.net/temurin/releases/?version=17" \
            "Or drop a pre-built APK into $REPO_ROOT/apk/ and re-run."
  # `|| true`: under `set -e` a failing command substitution in a bare
  # assignment kills the script before the check below can report anything.
  JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' || true)"
  [ "${JAVA_MAJOR:-0}" = "17" ] \
    || fail "The build needs exactly JDK 17; this machine has Java ${JAVA_MAJOR:-unknown}." \
            "Get JDK 17 from:  https://adoptium.net/temurin/releases/?version=17" \
            "Then point the shell at it, e.g. on macOS:" \
            "  export JAVA_HOME=\$(/usr/libexec/java_home -v 17)"

  # GRADLE_USER_HOME too: without it Gradle puts its distribution and the whole
  # dependency graph — well over a gigabyte — in ~/.gradle, which uninstall.sh
  # does not touch and has no business deleting.
  ( cd "$REPO_ROOT" \
      && ANDROID_HOME="$ANDROID_SDK_ROOT" \
         GRADLE_USER_HOME="$LOCAL_DIR/gradle-home" \
         ./gradlew --console=plain :app:assembleDebug ) \
    || fail "The Gradle build failed." \
            "The output above says why. Common causes are covered in INSTALL.md § 6." \
            "You can skip the build entirely by using the pre-built APK in apk/."
  APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$APK" ] || fail "Gradle reported success but the APK is not where it should be:" "  $APK"
fi

# --- A device to install onto -------------------------------------------------
SERIAL=""
EMU_PID=""

# Every adb call after this point goes through here, so a phone and a leftover
# emulator being attached at once cannot make adb refuse with "more than one
# device" halfway through an install.
adbt() {
  if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi
}

step "Looking for a device"
"$ADB" start-server >/dev/null 2>&1 || true
# `|| true` on both: a failing adb has to reach the checks below and be
# reported, not kill the script through `set -e` with no message at all.
DEVICES="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2 == "device" { print $1 }' || true)"
DEVICE_COUNT="$(printf '%s\n' "$DEVICES" | grep -c '[^[:space:]]' || true)"

if [ "${DEVICE_COUNT:-0}" -gt 1 ]; then
  fail "More than one device is connected, so adb cannot tell which to use." \
       "Attached: $(printf '%s' "$DEVICES" | tr '\n' ' ')" \
       "Unplug all but one, or close any running emulator, and try again."
elif [ "${DEVICE_COUNT:-0}" -eq 1 ]; then
  SERIAL="$DEVICES"
  info "Found one already connected — using it ($SERIAL)."
else
  info "None attached. Booting the virtual device '$AVD_NAME'."
  [ -x "$EMULATOR" ] || fail "The emulator is missing from the SDK." "Re-run ./install.sh to repair it."

  "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-save -no-boot-anim \
      >"$LOCAL_DIR/emulator.log" 2>&1 &
  EMU_PID=$!
  info "Started (pid $EMU_PID). Log: $LOCAL_DIR/emulator.log"
fi

# Runs whichever branch we came from. adb reports a device as `device` well
# before the OS has finished starting, so an emulator left half-booted by an
# interrupted earlier run looks ready here and is not.
step "Waiting for the device to be ready"
WAITED=0
until [ "$(adbt shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  if [ -n "$EMU_PID" ] && ! kill -0 "$EMU_PID" 2>/dev/null; then
    fail "The emulator exited before finishing its boot." \
         "The reason is at the end of:  $LOCAL_DIR/emulator.log" \
         "On Linux this is usually missing KVM permission. Check with:" \
         "  ls -l /dev/kvm     (your user needs access)" \
         "Emulator troubleshooting: https://developer.android.com/studio/run/emulator-troubleshooting"
  fi
  if [ "$WAITED" -ge 420 ]; then
    fail "The device has not finished booting after seven minutes." \
         "Check $LOCAL_DIR/emulator.log, or start it by hand with:" \
         "  ANDROID_AVD_HOME=$ANDROID_AVD_HOME $EMULATOR -avd $AVD_NAME"
  fi
  sleep 3
  WAITED=$((WAITED + 3))
  printf '.'
done
[ "$WAITED" -gt 0 ] && printf '\n'
# The emulator branch had no serial to pin until adb could see it.
[ -n "$SERIAL" ] || SERIAL="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2 == "device" { print $1; exit }' || true)"
info "Ready${WAITED:+ after ${WAITED}s}${SERIAL:+ ($SERIAL)}."

# --- Install ------------------------------------------------------------------
step "Installing the app"
# adb's own message is captured rather than only echoed, because the useful
# failures here are distinguishable and each needs different advice. Telling
# someone to "accept the USB-debugging prompt" when adb has already talked to
# the device sends them to look at the one thing that is working.
INSTALL_LOG="$LOCAL_DIR/install-apk.log"
: > "$INSTALL_LOG"

install_apk() {
  if adbt install -r "$APK" 2>&1 | tee -a "$INSTALL_LOG" | grep -q '^Success'; then
    return 0
  fi
  info "Streamed install failed — retrying by pushing the file first."
  adbt push "$APK" /data/local/tmp/connectonion.apk >/dev/null 2>&1 || return 1
  adbt shell pm install -r -t /data/local/tmp/connectonion.apk 2>&1 \
    | tee -a "$INSTALL_LOG" | grep -q '^Success'
}

if ! install_apk; then
  if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match' "$INSTALL_LOG"; then
    # Hit by anyone who installs the signed APK from the Release and then builds
    # from source, or the reverse: Android will not replace a package with one
    # signed by a different key, and the release and debug keys differ. This is
    # not a broken build and not a broken device.
    fail "A copy of $APP_ID is already installed, signed with a different key." \
         "Android will not replace it, so this is expected rather than a fault:" \
         "the APK published on the Release is signed with the release key, and a" \
         "build from source is signed with the local debug key." \
         "" \
         "Removing the installed copy also deletes its conversations and its" \
         "on-device identity, so this script will not do it for you:" \
         "" \
         "  $ADB uninstall $APP_ID" \
         "" \
         "Then re-run ./run.sh. To keep the existing data instead, use the same" \
         "APK it was installed from."
  fi
  if grep -q 'INSTALL_FAILED_INSUFFICIENT_STORAGE' "$INSTALL_LOG"; then
    fail "The device does not have enough free space for the app (~30 MB)." \
         "Free some space on the device and re-run ./run.sh."
  fi
  echo
  echo "adb said:"
  grep -v '^$' "$INSTALL_LOG" | tail -8
  fail "Could not install the APK onto the device." \
       "The full log is at $INSTALL_LOG" \
       "If the device is a physical phone, unlock it and accept the USB-debugging prompt." \
       "Check it is visible with:  $ADB devices" \
       "adb reference: https://developer.android.com/tools/adb"
fi
info "Installed."

step "Launching"
adbt shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
  || fail "Installed, but the app would not start." \
          "Open 'ConnectOnion' from the device's app list by hand."

# --- What to do next ----------------------------------------------------------
step "Running"
echo
bold "To confirm it works end to end"
info "1. On the Onboarding screen, paste this agent address:"
info ""
info "   0xb7062bd7d3938a320956fde55c59cb0436f132df8bd60f129d8ab10d159cc207"
info ""
info "2. If it asks for an invite code, enter:  OpenOnion"
info "3. Send any message. A reply confirms install, identity, relay"
info "   connection and round trip are all working."
echo
info "A second agent with many more tools, for seeing tool cards and approval"
info "prompts do something real:"
info "   0xb73ccb0e7132d84971fcee6d797eaaddc8a029608ed880b074ef64fb8680d124"
echo
bold "Notes"
info "The '/' command palette is empty against both — they publish no skills."
info "An emulator has no camera or microphone, so photo capture and dictation"
info "cannot be exercised on one. Everything else works."
echo
bold "When you are finished"
info "./uninstall.sh   — removes the app and everything install.sh downloaded."
echo
