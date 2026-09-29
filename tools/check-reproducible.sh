#!/usr/bin/env bash
# Builds the release twice, from two fresh copies of the committed tree in two folders of
# different names, without the build cache, and fails when the two files differ.
#
#   tools/check-reproducible.sh <empty or new folder to work in>
#
# The folder must not be on a disk that lives in memory when memory is short: two builds need
# about 3 GB of disk. What is compared is the unsigned file. A signature is put on afterwards
# and does not change what is signed.
set -euo pipefail

work="${1:?usage: tools/check-reproducible.sh <folder to work in>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
apk=app/build/outputs/apk/release/app-release-unsigned.apk

if [ -n "$(git -C "$root" status --porcelain)" ]; then
  echo "note the working tree has changes that are not committed; they are not part of what is built"
fi
mkdir -p "$work"
if [ -n "$(ls -A "$work")" ]; then
  echo "FAIL $work is not empty"; exit 1
fi
first="$work/a/stamp"
second="$work/another/and/deeper/folder/stamp"
mkdir -p "$(dirname "$first")" "$(dirname "$second")"
git clone --quiet "$root" "$first"
git clone --quiet "$root" "$second"

for copy in "$first" "$second"; do
  ( cd "$copy" && ./gradlew --no-daemon --no-build-cache --console=plain :app:assembleRelease > build.log 2>&1 ) || {
    echo "FAIL the build in $copy failed, see $copy/build.log"; exit 1
  }
done

one="$(sha256sum "$first/$apk" | cut -d' ' -f1)"
two="$(sha256sum "$second/$apk" | cut -d' ' -f1)"
echo "commit  $(git -C "$first" rev-parse HEAD)"
echo "first   $one"
echo "second  $two"
if [ "$one" = "$two" ]; then
  echo "ok   the two builds are the same file, $(stat -c %s "$first/$apk") bytes"
else
  echo "FAIL the two builds differ"
  exit 1
fi
