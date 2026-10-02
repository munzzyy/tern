#!/usr/bin/env bash
# Checks that the version being released has its CHANGELOG.md entry and its F-Droid changelog.
#
#   tools/check-release-notes.sh [path to app/build.gradle.kts]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

gradle_file="${1:-$root/app/build.gradle.kts}"
name=$(grep -oE 'versionName = "[^"]+"' "$gradle_file" | cut -d'"' -f2)
code=$(grep -oE 'versionCode = [0-9]+' "$gradle_file" | grep -oE '[0-9]+')
notes="$root/fastlane/metadata/android/en-US/changelogs/$code.txt"
# F-Droid cuts a changelog longer than this.
max_chars=500
fail=0

if ! awk -v heading="## $name," 'index($0, heading) == 1 { found = 1 } END { exit !found }' "$root/CHANGELOG.md"; then
    echo "FAIL CHANGELOG.md has no heading '## $name, <date>'"
    fail=1
fi
if [ ! -f "$notes" ]; then
    echo "FAIL $notes is missing"
    fail=1
else
    chars=$(LC_ALL=C.UTF-8 wc -m < "$notes")
    if [ "$chars" -gt "$max_chars" ]; then
        echo "FAIL $notes has $chars characters, more than $max_chars"
        fail=1
    fi
fi
[ "$fail" = 0 ] && echo "ok release notes for $name ($code)"
exit "$fail"
