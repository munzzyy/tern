#!/bin/bash
# Fails when the code that ships has a way to the network other than the one door.
#
# Every request leaves through net/UrlConnectionHttp.kt, which opens its connection through the
# proxy the user chose and hands the name of the host to that proxy unresolved. A second place that
# opens a connection, makes a socket or resolves a name would go round the proxy, and nothing else
# would notice. The handoff in core/handoff listens on the local network; it alone makes sockets.
#
#   tools/check-network-doors.sh [root of the source tree]
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
DOOR="app/src/main/kotlin/io/github/munzzyy/stamp/net/UrlConnectionHttp.kt"
HANDOFF="core/src/main/kotlin/io/github/munzzyy/stamp/core/handoff/"
fail=0

cd "$ROOT"
[ -f "$DOOR" ] || { echo "FAIL $DOOR is missing, so there is no door to compare with"; exit 1; }
trees=()
for tree in app/src/main app/src/release core/src/main; do
  [ -d "$tree" ] && trees+=("$tree")
done

# what it is, the pattern, the path that may hold it (a file, a folder ending in /, or - for none)
check() {
  local what="$1" pattern="$2" allowed="$3" hits
  hits=$(grep -rnE --include='*.kt' --include='*.java' -- "$pattern" "${trees[@]}" || true)
  if [ "$allowed" != "-" ] && [ -n "$hits" ]; then
    hits=$(printf '%s\n' "$hits" | grep -vF -- "$allowed" || true)
  fi
  if [ -n "$hits" ]; then
    echo "FAIL $what"
    printf '%s\n' "$hits" | sed 's/^/       /'
    fail=1
  else
    echo "ok   $what: none"
  fi
}

through=$(grep -cF 'openConnection(proxy())' "$DOOR" || true)
direct=$(grep -nE 'openConnection\(|\.openStream\(' "$DOOR" | grep -vF 'openConnection(proxy())' || true)
if [ "$through" -ge 1 ] && [ -z "$direct" ]; then
  echo "ok   the door opens every connection through the proxy it is given"
else
  echo "FAIL the door opens a connection without the proxy it is given"
  printf '%s\n' "$direct" | sed 's/^/       /'
  fail=1
fi

check "a connection opened outside $DOOR" 'openConnection\(|\.openStream\(' "$DOOR:"
check "a socket made outside $HANDOFF" '(^|[^A-Za-z0-9_])(Socket|ServerSocket|DatagramSocket|MulticastSocket)\(|createSocket\(|SocketChannel|DatagramChannel' "$HANDOFF"
check "an address made from a name outside $HANDOFF, which resolves it on this device" '(^|[^A-Za-z0-9_])InetSocketAddress\(' "$HANDOFF"
check "a name resolved on this device" 'getByName\(|getAllByName\(' "-"

[ "$fail" -eq 0 ] && echo "ok   the network has one door"
exit "$fail"
