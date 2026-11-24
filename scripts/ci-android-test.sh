#!/usr/bin/env sh
# Runs instrumented tests on the device ANDROID_SERIAL names, and fails unless some ran.
# .github/workflows/android-test.yml calls it inside the emulator it boots; it works on any
# device adb sees, but read docs/engineering/development.md before pointing it at a watch
# you wear: the connected tasks uninstall the app when they finish.
#
#   scripts/ci-android-test.sh verifyAndroidTest            # the phone app's
#   scripts/ci-android-test.sh :wear:connectedAndroidTest   # the watch app's
#
# A connected task that finds nothing to run passes, and verifyAndroidTest does nothing at
# all without ANDROID_SERIAL; scripts/ci-android-test-results.py turns either into a failure.
set -eu

if [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "ANDROID_SERIAL must name the device to test on." >&2
    exit 1
fi
export ANDROID_SERIAL

# Animations off, so a test never waits on one or catches a frame halfway. The emulator
# action sets these; a device someone forgot to set up fails here instead of flaking later.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    value="$(adb -s "$ANDROID_SERIAL" shell settings get global "$scale" | tr -d '\r')"
    case "$value" in
        0 | 0.0) ;;
        *)
            echo "$scale is '$value' on $ANDROID_SERIAL; set it to 0 (adb shell settings put global $scale 0)." >&2
            exit 1
            ;;
    esac
done

mkdir -p build/android-test
# The whole run's log, from before the app is installed: a process that dies at start
# leaves no per-test logcat behind.
adb -s "$ANDROID_SERIAL" logcat -c || true
adb -s "$ANDROID_SERIAL" logcat -v threadtime > build/android-test/logcat.txt 2>&1 &
logcat_pid=$!

# Only result files this run writes count, so a local rerun never reads an old one.
started="build/android-test/started"
touch "$started"

status=0
scripts/ci-android-gradle.sh "$@" || status=$?

kill "$logcat_pid" 2>/dev/null || true
python3 scripts/ci-android-test-results.py --newer "$started" || status=$?
exit "$status"
