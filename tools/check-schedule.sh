#!/usr/bin/env bash
# Measures the background check on one device: the periodic job is there after a reboot and after
# Tern replaces itself, and it runs in a Doze maintenance window without being forced.
#
#   tools/check-schedule.sh <serial>
#
# It sets the debug build to check every 15 minutes. The Doze part steps the device through
# maintenance windows for up to 40 minutes and puts the battery and Doze back afterwards.
set -euo pipefail

serial="${1:?usage: tools/check-schedule.sh <serial>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
adb="${ANDROID_HOME:?set ANDROID_HOME}/platform-tools/adb"
app="$root/app/build/outputs/apk/debug/app-debug.apk"
package="io.github.munzzyy.tern.debug"
job_id=1
minutes=15
boot_seconds=600
doze_seconds=2400
window_seconds=60

[ -f "$app" ] || { echo "FAIL build first: ./gradlew :app:assembleDebug"; exit 1; }

on() { "$adb" -s "$serial" "$@"; }
shell() { on shell "$@" | tr -d '\r'; }
fail() { echo "FAIL $*"; exit 1; }

boot_id() { shell cat /proc/sys/kernel/random/boot_id 2> /dev/null || true; }

reboot() {
    local before deadline=$((SECONDS + boot_seconds))
    before="$(boot_id)"
    on reboot
    until [ "$(boot_id)" != "$before" ] && [ "$(shell getprop sys.boot_completed 2> /dev/null)" = 1 ]; do
        [ "$SECONDS" -lt "$deadline" ] || fail "$serial did not come back within $boot_seconds s"
        sleep 3
    done
}

# The job's own entry, which lists it as PERSISTED and with its period, or nothing.
job() {
    shell dumpsys jobscheduler "$package" | python3 -c '
import re, sys
package, job_id = sys.argv[1], sys.argv[2]
lines = sys.stdin.read().splitlines()
for i, line in enumerate(lines):
    if re.match(r"\s*JOB #\S+/%s: \S+ %s/" % (job_id, re.escape(package)), line):
        indent = len(line) - len(line.lstrip())
        block = [line]
        for rest in lines[i + 1:]:
            if rest.strip() and len(rest) - len(rest.lstrip()) <= indent:
                break
            block.append(rest)
        print("\n".join(block))
        break
' "$package" "$job_id"
}

scheduled() {
    local entry
    entry="$(job)"
    [ -n "$entry" ] || return 1
    grep -q 'PERSISTED' <<< "$entry" || return 1
    grep -q "PERIODIC: interval=+${minutes}m0s0ms" <<< "$entry"
}

expect_scheduled() {
    local deadline=$((SECONDS + 60))
    until scheduled; do
        if [ "$SECONDS" -ge "$deadline" ]; then
            echo "     what the device lists as job $job_id of $package:"
            job | grep -E 'JOB #|PERIODIC|PERSISTED' | sed 's/^/     /' || echo "     nothing"
            fail "$1"
        fi
        sleep 2
    done
    echo "ok   $2"
}

# Seconds since the job last started, from the job history, or nothing when it has not.
last_start() {
    shell dumpsys jobscheduler | python3 -c '
import re, sys
package, job_id = sys.argv[1], sys.argv[2]
best = None
for line in sys.stdin:
    m = re.match(r"\s*([+-][0-9dhms]+)\s+START(?:-P)?: #\S+/%s .*\b%s/" % (job_id, re.escape(package)), line)
    if not m:
        continue
    ago = 0.0
    for amount, unit in re.findall(r"(\d+)(ms|d|h|m|s)", m.group(1)):
        ago += int(amount) * {"d": 86400, "h": 3600, "m": 60, "s": 1, "ms": 0.001}[unit]
    best = ago if best is None else min(best, ago)
if best is not None:
    print(int(best))
' "$package" "$job_id"
}

on install -r -t "$app" > /dev/null
shell am force-stop "$package"
prefs="$(mktemp)"
trap 'rm -f "$prefs"' EXIT
shell run-as "$package" cat shared_prefs/settings.xml > "$prefs" 2> /dev/null || : > "$prefs"
python3 - "$prefs" "$minutes" <<'EOF'
import sys
import xml.etree.ElementTree as ET
path, minutes = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except (ET.ParseError, FileNotFoundError):
    root = ET.Element("map")
for old in root.findall("int[@name='checkEveryMinutes']"):
    root.remove(old)
ET.SubElement(root, "int", name="checkEveryMinutes", value=minutes)
ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
EOF
on shell "run-as $package sh -c 'mkdir -p shared_prefs && cat > shared_prefs/settings.xml'" < "$prefs"
shell am start -n "$package/io.github.munzzyy.tern.MainActivity" > /dev/null
expect_scheduled "Tern did not schedule its check" "the check is scheduled every $minutes minutes and persisted"

reboot
expect_scheduled "the check was gone after a reboot" "the check is still scheduled after a reboot"

on install -r -t "$app" > /dev/null
expect_scheduled "the check was gone after Tern replaced itself" "the check is still scheduled after Tern replaced itself"

deep_was="$(shell dumpsys deviceidle enabled deep 2> /dev/null || echo 1)"
restore() {
    shell dumpsys deviceidle unforce > /dev/null || true
    [ "$deep_was" = 1 ] || shell dumpsys deviceidle disable deep > /dev/null || true
    shell dumpsys battery reset > /dev/null || true
    rm -f "$prefs"
}
trap restore EXIT
shell dumpsys battery unplug
shell dumpsys deviceidle enable deep > /dev/null
shell dumpsys deviceidle force-idle > /dev/null
dozing_since=$SECONDS
echo "     dozing; stepping through maintenance windows for up to $((doze_seconds / 60)) minutes"
while [ $((SECONDS - dozing_since)) -lt "$doze_seconds" ]; do
    state="$(shell dumpsys deviceidle step deep | sed -n 's/^Stepped to deep: //p')"
    sleep "$window_seconds"
    ago="$(last_start)"
    if [ -n "$ago" ] && [ "$ago" -lt $((SECONDS - dozing_since)) ]; then
        echo "ok   the check started in Doze, ${ago} s ago, in state ${state:-unknown}, $(((SECONDS - dozing_since) / 60)) minutes in"
        exit 0
    fi
done
fail "the check did not start in $((doze_seconds / 60)) minutes of Doze"
