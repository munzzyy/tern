#!/usr/bin/env bash
# Runs the device tests on one device and says what ran, what failed and what was skipped.
#
#   tools/device-suite.sh <serial> [arguments for am instrument, such as -e class a.b.SomeTest]
#
# Gradle's connected task installs the app fresh and removes it afterwards, so a permission given
# from the host does not live to the next run. Before Android 12 the tests cannot give themselves
# the permission to install apps: the change kills the app they run in. Without it every test that
# installs something steps aside, and a run with fifteen tests skipped looks like a pass.
# This installs both files, gives the permission from the host, runs the tests and prints every
# skipped test by name. When the app dies in the middle, it names the test that was running and
# prints what Android wrote down about the crash.
set -euo pipefail

serial="${1:?usage: tools/device-suite.sh <serial> [arguments for am instrument]}"
shift
root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
app="$root/app/build/outputs/apk/debug/app-debug.apk"
tests="$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
package="io.github.munzzyy.stamp.debug"

if [ ! -f "$app" ] || [ ! -f "$tests" ]; then
    echo "FAIL build first: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest"
    exit 1
fi

"$adb" -s "$serial" install -r -t "$app" > /dev/null
"$adb" -s "$serial" install -r -t "$tests" > /dev/null
"$adb" -s "$serial" shell appops set "$package" REQUEST_INSTALL_PACKAGES allow

raw="$(mktemp)"
crash="$(mktemp)"
trap 'rm -f "$raw" "$crash"' EXIT
"$adb" -s "$serial" logcat -b crash -c || true
"$adb" -s "$serial" shell am instrument -w -r "$@" "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$raw" || true
"$adb" -s "$serial" logcat -b crash -d > "$crash" 2> /dev/null || true

python3 - "$raw" "$crash" <<'EOF'
import sys

passed, failed, skipped = [], [], []
fields, key, finished, running, last = {}, None, False, None, []
for line in open(sys.argv[1], errors="replace").read().splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
        fields[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.split(": ")[1])
        name = fields.get("class", "?").rsplit(".", 1)[-1] + "." + fields.get("test", "?")
        running = name if code == 1 else None
        if code == 0:
            passed.append(name)
        elif code in (-1, -2):
            failed.append((name, fields.get("stack", "")))
        elif code in (-3, -4):
            skipped.append((name, fields.get("stack", "").splitlines()[0] if fields.get("stack") else ""))
        fields, key = {}, None
    elif line.startswith("INSTRUMENTATION_CODE: "):
        finished = line.strip().endswith("-1")
    elif line.startswith("INSTRUMENTATION_RESULT: ") or line.startswith("INSTRUMENTATION_"):
        key = None
        last.append(line)
    elif key is not None:
        fields[key] += "\n" + line

for name, stack in failed:
    print("FAILED ", name)
    for row in stack.splitlines()[:12]:
        print("        " + row)
for name, why in skipped:
    print("skipped", name, "  " + why[:200] if why else "")
print(f"{len(passed)} passed, {len(failed)} failed, {len(skipped)} skipped")
if not finished:
    print("FAIL the run did not finish: the app under test was killed or crashed")
    print("     while running:", running or "no test, between two of them")
    for row in last[-4:]:
        print("     " + row[:300])
    for row in open(sys.argv[2], errors="replace").read().splitlines()[-40:]:
        print("     " + row[:300])
    sys.exit(1)
if not passed and not failed:
    print("FAIL no test ran")
    sys.exit(1)
sys.exit(1 if failed else 0)
EOF
