#!/bin/bash
# Proves tools/check-network-doors.sh: the tree as it is passes, and each file here, put into a
# copy of the tree, makes it fail with the line its first comment expects. A file that names a path
# after "replaces:" takes that file's place; any other goes in as a file of its own.
#
#   tools/tests/network-doors/run.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
CHECK="$ROOT/tools/check-network-doors.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for tree in app/src/main/kotlin app/src/release/kotlin core/src/main/kotlin; do
  if [ -d "$ROOT/$tree" ]; then
    mkdir -p "$work/$tree"
    cp -r "$ROOT/$tree/." "$work/$tree/"
  fi
done

fail=0
if out=$(bash "$CHECK" "$work" 2>&1); then
  echo "ok   the tree as it is passes"
else
  echo "FAIL the tree as it is does not pass:"
  printf '%s\n' "$out" | grep '^FAIL' | sed 's/^/       /'
  fail=1
fi

count=0
for fixture in "$HERE"/*.kt; do
  name=$(basename "$fixture" .kt)
  expect=$(sed -n 's|^// expect: ||p' "$fixture" | head -1)
  replaces=$(sed -n 's|^// replaces: ||p' "$fixture" | head -1)
  if [ -z "$expect" ]; then
    echo "FAIL $name says nothing about what it should fail with"
    fail=1
    continue
  fi
  if [ -n "$replaces" ]; then
    target="$work/$replaces"
    cp "$target" "$work/kept"
  else
    target="$work/app/src/main/kotlin/fixture/$name.kt"
    mkdir -p "$(dirname "$target")"
  fi
  cp "$fixture" "$target"
  status=0
  out=$(bash "$CHECK" "$work" 2>&1) || status=$?
  if [ -n "$replaces" ]; then mv "$work/kept" "$target"; else rm -f "$target"; fi
  if [ "$status" -eq 1 ] && printf '%s\n' "$out" | grep -qF "FAIL $expect"; then
    count=$((count + 1))
  else
    echo "FAIL $name went through (exit $status); expected a line starting FAIL $expect"
    fail=1
  fi
done

[ "$fail" -eq 0 ] && echo "ok   each of the $count ways to the network fails the check"
exit "$fail"
