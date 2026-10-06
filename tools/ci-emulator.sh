#!/usr/bin/env bash
# Boots a throwaway Android 16 emulator, runs the engine's device tests on it and stops it again.
#
#   tools/ci-emulator.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:?set ANDROID_HOME}"
image="system-images;android-36;default;x86_64"
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
boot_seconds=600
settle_seconds=300
suite_seconds=3600

install() { (yes || true) | "$sdkmanager" "$@" > /dev/null; }
[ -x "$adb" ] || install platform-tools
[ -x "$emulator" ] || install emulator
[ -f "$sdk/system-images/android-36/default/x86_64/system.img" ] || install "$image"

# The emulator wants about 7 GB free next to the AVD, more than a /tmp in memory may have.
mkdir -p "$root/build"
work="$(mktemp -d "$root/build/ci-emulator.XXXXXX")"
export ANDROID_AVD_HOME="$work/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$avdmanager" create avd -n tern-ci -k "$image" -d pixel_6 > /dev/null

taken() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null; }
port=""
for p in $(seq 5554 2 5680); do
    if ! taken "$p" && ! taken $((p + 1)); then
        port=$p
        break
    fi
done
[ -n "$port" ] || { echo "FAIL no free emulator port between 5554 and 5681"; exit 1; }
serial="emulator-$port"

pid=""
watchdog=""
stop() {
    [ -z "$watchdog" ] || kill "$watchdog" 2> /dev/null || true
    if [ -n "$pid" ]; then
        "$adb" -s "$serial" emu kill > /dev/null 2>&1 || true
        for _ in $(seq 1 30); do
            kill -0 "$pid" 2> /dev/null || break
            sleep 1
        done
        kill -9 "$pid" 2> /dev/null || true
    fi
    rm -rf "$work"
}
trap stop EXIT

cd "$root"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest

# Booting under a Gradle build on four cores starves System UI into an ANR dialog that hides the installer.
"$emulator" -avd tern-ci -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect \
    > "$work/emulator.log" 2>&1 &
pid=$!

deadline=$((SECONDS + boot_seconds))
until [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; do
    if ! kill -0 "$pid" 2> /dev/null; then
        echo "FAIL the emulator stopped before it booted"
        tail -20 "$work/emulator.log"
        exit 1
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "FAIL $serial did not boot within $boot_seconds s"
        tail -20 "$work/emulator.log"
        exit 1
    fi
    sleep 5
done
echo "$serial booted"

# Booted is not settled: until the boot work is done, install results can come late enough to time tests out.
deadline=$((SECONDS + settle_seconds))
timeout "$settle_seconds" "$adb" -s "$serial" shell am wait-for-broadcast-idle > /dev/null || echo "note: the boot broadcasts did not drain"
quiet=0
until [ "$quiet" -ge 3 ]; do
    idle="$("$adb" -s "$serial" shell 'head -1 /proc/stat; sleep 5; head -1 /proc/stat' 2> /dev/null |
        awk '{ t = 0; for (i = 2; i <= NF; i++) t += $i; tot[NR] = t; idl[NR] = $5 + $6 }
             END { if (NR == 2 && tot[2] > tot[1]) printf "%d", 100 * (idl[2] - idl[1]) / (tot[2] - tot[1]) }')" || idle=""
    if [ -n "$idle" ] && [ "$idle" -ge 60 ]; then quiet=$((quiet + 1)); else quiet=0; fi
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "note: $serial is still busy after $settle_seconds s (${idle:-?}% idle), going on"
        break
    fi
done
echo "$serial settled (${idle:-?}% idle)"
"$adb" -s "$serial" logcat -b events -d -s am_anr | grep am_anr || true

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$adb" -s "$serial" shell settings put global "$scale" 0
done
"$adb" -s "$serial" shell svc power stayon true
"$adb" -s "$serial" shell wm dismiss-keyguard

# An emulator that dies mid-run, killed for memory say, leaves adb waiting for it forever.
(
    while kill -0 "$pid" 2> /dev/null; do sleep 5; done
    pkill -f "[p]latform-tools/adb -s $serial " || true
) &
watchdog=$!

status=0
timeout "$suite_seconds" bash tools/device-suite.sh "$serial" -e package io.github.munzzyy.tern.enginetest | tee "$work/suite.txt" || status=$?
if [ "$status" -ne 0 ]; then
    "$adb" -s "$serial" logcat -b events -d -s am_anr am_kill 2> /dev/null | grep -E 'am_anr|am_kill.*anr' || true
fi
if ! kill -0 "$pid" 2> /dev/null; then
    echo "FAIL the emulator stopped during the run"
    status=1
fi
# device-suite.sh gives the install permission from the host, so no test may skip for want of it.
if grep -q '^skipped.*grant it from the host first' "$work/suite.txt"; then
    echo "FAIL a test stepped aside for the install permission the host should have given"
    status=1
fi
exit "$status"
