#!/usr/bin/env bash
#
# ConnectOnion Android — remove everything install.sh created (macOS/Linux).
#
set -euo pipefail

SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DIR="$SETUP_DIR/.local"
ENV_FILE="$LOCAL_DIR/env.sh"

ASSUME_YES=0
for arg in "$@"; do
  case "$arg" in
    -y|--yes) ASSUME_YES=1 ;;
    -h|--help) echo "Usage: ./uninstall.sh [-y|--yes]"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

if [ ! -d "$LOCAL_DIR" ]; then
  echo "Nothing to remove — $LOCAL_DIR does not exist."
  echo "install.sh has either not been run, or this has already been undone."
  exit 0
fi

SDK_REUSED=0
APP_ID="ai.openonion.oochat"
AVD_NAME="openonion-android"
if [ -f "$ENV_FILE" ]; then
  # shellcheck source=/dev/null
  . "$ENV_FILE"
  SDK_REUSED="${CONNECTONION_SDK_REUSED:-0}"
  APP_ID="${CONNECTONION_APP_ID:-$APP_ID}"
  AVD_NAME="${CONNECTONION_AVD_NAME:-$AVD_NAME}"
fi

SIZE="$(du -sh "$LOCAL_DIR" 2>/dev/null | cut -f1 || echo '?')"

clear 2>/dev/null || true
bold "ConnectOnion Android — remove the local setup"
echo
bold "What will be deleted"
info "$LOCAL_DIR  ($SIZE)"
if [ "$SDK_REUSED" = "1" ]; then
  info ""
  info "This includes the virtual device only. The Android SDK at"
  info "  ${ANDROID_SDK_ROOT:-unknown}"
  info "was already on this machine and will be left alone — but the packages"
  info "install.sh added to it (platform, system image, emulator) stay behind."
  info "Remove those with the SDK Manager in Android Studio if you want them gone."
else
  info "  → the private Android SDK, the emulator, the system image,"
  info "    and the '$AVD_NAME' virtual device"
fi
info ""
info "The app '$APP_ID' will be uninstalled from any connected device."
echo
bold "What was never touched, and so needs no undoing"
info "PATH, ~/.bashrc, ~/.zshrc and every other shell profile."
info "~/.android, ~/.gradle, and any Android Studio installation of your own —"
info "  the scripts pointed the SDK tools and Gradle at .local/ instead, so"
info "  there is nothing of yours to clean up."
info "Anything outside this folder."
echo

if [ "$ASSUME_YES" != "1" ]; then
  printf 'Delete it all? [y/N] '
  read -r reply
  case "$reply" in
    [yY]|[yY][eE][sS]) ;;
    *) echo "Cancelled. Nothing was removed."; exit 0 ;;
  esac
fi

ADB="${ANDROID_SDK_ROOT:-}/platform-tools/adb"

step "Removing the app from any connected device"
if [ -x "$ADB" ] && [ "$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')" -gt 0 ]; then
  if "$ADB" uninstall "$APP_ID" >/dev/null 2>&1; then
    info "Uninstalled $APP_ID."
  else
    info "Not installed on the connected device — nothing to do."
  fi
else
  info "No device connected — skipping."
fi

step "Shutting down the emulator, if it is running"
if [ -x "$ADB" ]; then
  "$ADB" emu kill >/dev/null 2>&1 || true
  "$ADB" kill-server >/dev/null 2>&1 || true
  info "Done."
fi

step "Deleting $LOCAL_DIR"
if [ "$SDK_REUSED" = "1" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ]; then
  case "$ANDROID_SDK_ROOT" in
    "$LOCAL_DIR"*) ;;  # private copy after all — safe to take with the rest
    *) info "Leaving the pre-existing SDK at $ANDROID_SDK_ROOT untouched." ;;
  esac
fi
rm -rf "$LOCAL_DIR"
info "Removed."

echo
bold "Done"
info "This folder now holds only the scripts themselves."
info "Delete tools/local-android-setup/ to remove those too."
echo
