#!/usr/bin/env bash
# Cold-start timing without Macrobenchmark.
#
# Macrobenchmark cannot complete a run on the EMUI device this project tests on
# — see docs/performance-baseline.md for the four modes tried and how each
# failed. `am start -W` works there, and its TotalTime is the same thing
# StartupTimingMetric reports as timeToInitialDisplay: intent dispatch to first
# frame drawn.
#
# What this gives up versus Macrobenchmark: no Perfetto trace, no shader-cache
# reset, no compilation control, and force-stop leaves the page cache warm, so
# these are not true first-ever-launch numbers. What it keeps is the part that
# matters for tracking work — a reproducible number on real hardware that moves
# when the app gets faster.
#
# Build and install first (the gates in docs/performance-baseline.md must be
# off, or every install will sit on a confirmation dialog):
#   ./gradlew :app:assembleBenchmarkRelease
#   adb push app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk /data/local/tmp/bm.apk
#   adb shell pm install -r -t /data/local/tmp/bm.apk
#
# Usage: ./measure-startup.sh [package] [iterations]

# No -e: adb's shell commands return non-zero for benign reasons (force-stop on
# an already-dead process, keyevent while the launcher is settling), and -e
# turned that into a silent exit right after the header printed.
set -uo pipefail

PKG="${1:-ai.openonion.oochat.benchmark}"
ITERATIONS="${2:-15}"
ACTIVITY="$PKG/ai.openonion.oochat.MainActivity"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
# Both an emulator and a phone are usually attached, and the emulator's numbers
# are meaningless here — insist on being told which.
SERIAL_ARG=()
[ -n "${ANDROID_SERIAL:-}" ] && SERIAL_ARG=(-s "$ANDROID_SERIAL")

echo "package:    $PKG"
echo "iterations: $ITERATIONS"
echo

times=()
for i in $(seq 1 "$ITERATIONS"); do
    "$ADB" "${SERIAL_ARG[@]}" shell am force-stop "$PKG"
    "$ADB" "${SERIAL_ARG[@]}" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    # Let the launcher settle; without this the first iterations read high.
    sleep 2
    t=$("$ADB" "${SERIAL_ARG[@]}" shell "am start -W -n $ACTIVITY" 2>/dev/null \
        | grep -E '^TotalTime:' | grep -oE '[0-9]+')
    [ -z "$t" ] && { echo "iteration $i: no TotalTime — is $PKG installed?" >&2; exit 1; }
    times+=("$t")
    printf '%3d  %5s ms\n' "$i" "$t"
done

printf '%s\n' "${times[@]}" | sort -n | awk '
    { v[NR] = $1; sum += $1 }
    END {
        median = (NR % 2) ? v[(NR + 1) / 2] : (v[NR/2] + v[NR/2 + 1]) / 2
        printf "\nmedian %d ms   mean %.1f ms   min %d ms   max %d ms   n=%d\n", \
               median, sum / NR, v[1], v[NR], NR
    }'
