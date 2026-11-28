#!/usr/bin/env bash
# Install the OpenWatch watch app on a Wear OS watch over Wi-Fi adb, grant
# its runtime permissions, launch it, and report what is running.
#
#   scripts/wear-install.sh [--serial <ip:port>] [options]
#
#   --apk <path>     APK to install (default: the debug build output, built when missing)
#   --release        use the release build output instead of the debug one
#   --package <id>   the installed package id, when the APK name does not say
#   --no-build       never run Gradle; fail when the APK is missing
#   --no-grant       do not pre-grant runtime permissions; the app asks on the wrist
#   --status         only report; install nothing
#
# After the install it grants Bluetooth, notifications, activity recognition
# (steps), and the heart rate, background, skin temperature and SpO2 reads
# (BODY_SENSORS before Android 16), then compiles a non-debuggable build.
#
# Pair the computer first: scripts/wear-pair.sh. Guide: docs/how-to/wear-os-sideload.md
set -euo pipefail
# shellcheck source=lib/wear-adb.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/wear-adb.sh"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEBUG_APK="$REPO_ROOT/wear/build/outputs/apk/debug/wear-debug.apk"
RELEASE_APK="$REPO_ROOT/wear/build/outputs/apk/release/wear-release.apk"
RELEASE_PACKAGE="tech.mmarca.openwatch"
DEBUG_PACKAGE="tech.mmarca.openwatch.debug"
MAIN_ACTIVITY="tech.mmarca.openwatch.wear.MainActivity"
SERVICE_CLASS="tech.mmarca.openwatch.wear.WearAppService"

APK=""
VARIANT=debug
PACKAGE=""
BUILD=yes
GRANT=yes
STATUS_ONLY=no

while [ $# -gt 0 ]; do
    case "$1" in
        -s|--serial) SERIAL="$2"; shift 2 ;;
        --apk) APK="$2"; shift 2 ;;
        --release) VARIANT=release; shift ;;
        --package) PACKAGE="$2"; shift 2 ;;
        --no-build) BUILD=no; shift ;;
        --no-grant) GRANT=no; shift ;;
        --status) STATUS_ONLY=yes; shift ;;
        -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown argument $1" ;;
    esac
done

resolve_apk() {
    if [ -z "$APK" ]; then
        [ "$VARIANT" = release ] && APK="$RELEASE_APK" || APK="$DEBUG_APK"
        if [ ! -f "$APK" ]; then
            [ "$BUILD" = yes ] || die "$APK is missing and --no-build was given."
            say "Building the watch app ($VARIANT)"
            (cd "$REPO_ROOT" && ./gradlew ":wear:assemble${VARIANT^}" --quiet)
        fi
    fi
    [ -f "$APK" ] || die "APK not found: $APK"
    if [ -z "$PACKAGE" ]; then
        case "$(basename "$APK")" in
            *debug*) PACKAGE="$DEBUG_PACKAGE" ;;
            *) PACKAGE="$RELEASE_PACKAGE" ;;
        esac
    fi
}

grant_permissions() {
    # ACTIVITY_RECOGNITION gates step_counter and step_detector; without it the
    # steps recorder registers nothing. Location stays a wrist prompt (workouts ask).
    local permissions=(android.permission.BLUETOOTH_CONNECT android.permission.POST_NOTIFICATIONS android.permission.ACTIVITY_RECOGNITION)
    if [ "$SDK" -ge 36 ]; then
        permissions+=(android.permission.health.READ_HEART_RATE android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND
            android.permission.health.READ_SKIN_TEMPERATURE android.permission.health.READ_OXYGEN_SATURATION)
    elif [ "$SDK" -ge 33 ]; then
        permissions+=(android.permission.BODY_SENSORS android.permission.BODY_SENSORS_BACKGROUND)
    else
        permissions+=(android.permission.BODY_SENSORS)
    fi
    local permission
    for permission in "${permissions[@]}"; do
        if shell pm grant "$PACKAGE" "$permission" 2>/dev/null; then
            note "granted ${permission##*.}"
        else
            warn "could not grant ${permission##*.}; the app will ask on the wrist"
        fi
    done
}

compile_app() {
    # A sideloaded install sits at the `verify` filter (JIT only) until Android's
    # idle-and-charging dexopt, often the next night: on a Pixel Watch 5 that scrolled
    # the dashboard ~17% janky (p90 65 ms) against ~2% (p90 22 ms) once compiled
    # (measured 2026-11-26 with `dumpsys gfxinfo`). Write the shipped baseline
    # profile, then compile against it now, the AndroidX ProfileInstaller recipe.
    # A debuggable build cannot be compiled (ART keeps it interpreted, ~45-65% janky),
    # so smoothness is never judged on one.
    # Read the flags whole first: under pipefail, `grep -q` quitting early kills the adb
    # pipe and the test reads false, so a debuggable build was "compiled" (2026-11-27).
    local flags
    flags=$(shell dumpsys package "$PACKAGE" | grep pkgFlags || true)
    if [[ "$flags" == *DEBUGGABLE* ]]; then
        warn "debuggable build: ART runs it interpreted, so scrolling is janky by design. To judge smoothness, build with OPENWATCH_PUBLISHED_DEBUG_APK=true ./gradlew :wear:assembleDebug and install that (--release needs the release signing key)."
        return
    fi
    # The receiver answers 1 (profile written) or 2 (already there); anything else leaves
    # speed-profile with no profile to compile against.
    local profile
    profile=$(shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
        -n "$PACKAGE/androidx.profileinstaller.ProfileInstallReceiver" 2>&1 || true)
    if [[ "$profile" != *"result=1"* && "$profile" != *"result=2"* ]]; then
        warn "the baseline profile was not written; compiling without it"
    fi
    if [[ "$(shell cmd package compile -f -m speed-profile "$PACKAGE")" == *Success* ]]; then
        # The install already started the app (package-replaced receiver, the broadcast), and ART
        # does not swap code in a running process: stop it, so the launch below runs the compiled code.
        shell am force-stop "$PACKAGE"
        note "compiled (speed-profile): smooth from the first launch"
    else
        warn "could not compile; the app runs JIT-only until the watch's overnight dexopt"
    fi
}

status() {
    say "Status of $PACKAGE"
    if setup_done; then
        note "setup wizard: complete"
    else
        note "setup wizard: NOT complete (scripts/wear-skip-onboarding.sh, or onboard with the vendor app)"
    fi
    local dump
    dump="$(shell dumpsys package "$PACKAGE" 2>/dev/null || true)"
    # Here-strings, not `echo | grep -q`: under pipefail a dump past the pipe buffer
    # kills echo when grep stops early, and the match reads false.
    if ! grep -q 'versionName=' <<<"$dump"; then
        note "app: not installed"
        return
    fi
    note "app: $(echo "$dump" | sed -n 's/^ *versionName=//p' | head -1) (code $(echo "$dump" | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -1))"
    local permission
    for permission in BLUETOOTH_CONNECT POST_NOTIFICATIONS ACTIVITY_RECOGNITION READ_HEART_RATE READ_HEALTH_DATA_IN_BACKGROUND READ_SKIN_TEMPERATURE READ_OXYGEN_SATURATION BODY_SENSORS BODY_SENSORS_BACKGROUND; do
        if grep -Eq "\.$permission: granted=true" <<<"$dump"; then
            note "granted: $permission"
        elif grep -Eq "\.$permission: granted=false" <<<"$dump"; then
            note "missing: $permission"
        fi
    done
    # dumpsys shortens a class under the package's own name (release: tech.mmarca.openwatch/.wear.WearAppService).
    if [[ "$(shell dumpsys activity services "$PACKAGE")" == *"$PACKAGE/"*"${SERVICE_CLASS##*.}"* ]]; then
        note "phone link service: running"
    else
        note "phone link service: not running (open the app on the watch and grant Nearby devices)"
    fi
}

require_watch

if [ "$STATUS_ONLY" = yes ]; then
    [ -n "$PACKAGE" ] || PACKAGE="$([ "$VARIANT" = release ] && echo "$RELEASE_PACKAGE" || echo "$DEBUG_PACKAGE")"
    status
    exit 0
fi

resolve_apk
say "Installing $(basename "$APK") as $PACKAGE"
adbw install -r "$APK" | sed 's/^/  /'
shell pm list packages "$PACKAGE" | grep -qx "package:$PACKAGE" \
    || die "$PACKAGE is not installed; is --package right for this APK?"
if [ "$GRANT" = yes ]; then
    say "Permissions"
    grant_permissions
fi
say "Compiling"
compile_app
say "Launching"
shell am start -W -n "$PACKAGE/$MAIN_ACTIVITY" >/dev/null && note "the watch app is on screen"
sleep 3
status
cat <<TEXT

Next, on the phone:
  1. Bluetooth settings: pair the watch (it may be listed twice; either entry works).
  2. OpenWatch, home screen, add watch: it is offered as a Wear OS watch.
  3. On the watch's screen in OpenWatch, "Validate Wear OS App" should answer.
TEXT
