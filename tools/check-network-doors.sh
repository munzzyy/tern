#!/bin/bash
# Fails when the code that ships has a way to the network other than the one door.
#
# Every request leaves through net/UrlConnectionHttp.kt, which opens its connection through the
# proxy the user chose and hands the name of the host to that proxy unresolved. A second place that
# opens a connection, makes a socket or resolves a name would go round the proxy, and nothing else
# would notice. The handoff in core/handoff listens on the local network, and net/ProxyProbe.kt asks
# the proxy on this device whether it is there. No other place makes a socket, and nothing else that
# fetches by itself is used: java.net.URL outside the door, DownloadManager, HttpEngine or Cronet,
# a web view, DnsResolver or an HTTP library. tools/tests/network-doors holds a file for each way,
# and tools/tests/network-doors/run.sh proves each one fails this script.
#
#   tools/check-network-doors.sh [root of the source tree]
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
DOOR="app/src/main/kotlin/io/github/munzzyy/tern/net/UrlConnectionHttp.kt"
HANDOFF="core/src/main/kotlin/io/github/munzzyy/tern/core/handoff/"
PROBE="app/src/main/kotlin/io/github/munzzyy/tern/net/ProxyProbe.kt"
fail=0

cd "$ROOT"
[ -f "$DOOR" ] || { echo "FAIL $DOOR is missing, so there is no door to compare with"; exit 1; }
trees=()
for tree in app/src/main app/src/release core/src/main; do
  [ -d "$tree" ] && trees+=("$tree")
done

# what it is, the pattern, then the paths that may hold it (a file, a folder ending in /, or - for none)
check() {
  local what="$1" pattern="$2" allowed hits
  shift 2
  hits=$(grep -rnE --include='*.kt' --include='*.java' -- "$pattern" "${trees[@]}" || true)
  for allowed in "$@"; do
    if [ "$allowed" != "-" ] && [ -n "$hits" ]; then
      hits=$(printf '%s\n' "$hits" | grep -vF -- "$allowed" || true)
    fi
  done
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
check "a socket made outside $HANDOFF and $PROBE" '(^|[^A-Za-z0-9_])(Socket|ServerSocket|DatagramSocket|MulticastSocket)\(|createSocket\(|SocketChannel|DatagramChannel' "$HANDOFF" "$PROBE:"
check "an address made from a name outside $HANDOFF, which resolves it on this device" '(^|[^A-Za-z0-9_])InetSocketAddress\(' "$HANDOFF" "$PROBE:"

# The probe asks the proxy on this device whether it is there. It may go to 127.0.0.1 and nowhere else.
if [ -f "$PROBE" ]; then
  to=$(grep -nE 'InetSocketAddress\(|\.connect\(' "$PROBE" | grep -vF 'InetSocketAddress(InetAddress.getByAddress(THIS_DEVICE), port)' || true)
  device=$(grep -cF 'THIS_DEVICE = byteArrayOf(127, 0, 0, 1)' "$PROBE" || true)
  if [ -z "$to" ] && [ "$device" -eq 1 ]; then
    echo "ok   the probe connects to 127.0.0.1 and nowhere else"
  else
    echo "FAIL the probe connects to something other than 127.0.0.1"
    printf '%s\n' "$to" | sed 's/^/       /'
    fail=1
  fi
fi
check "a name resolved on this device" 'getByName\(|getAllByName\(' "-"
check "java.net.URL or a URLConnection outside $DOOR, which opens a connection round the proxy" \
  '(^|[^A-Za-z0-9_.])java\.net\.(URL|URLConnection|HttpURLConnection)([^A-Za-z0-9_]|$)|javax\.net\.ssl\.HttpsURLConnection|(^|[^A-Za-z0-9_])(Https?)?URLConnection([^A-Za-z0-9_]|$)|import java\.net\.\*' "$DOOR:"
check "a URL made from a URI outside $DOOR" '\.toURL\(' "$DOOR:"
check "what an address holds read straight from it" '\.getContent\(|URL\([^)]*\)\.(content|readText|readBytes)' "-"
check "Android's download service, which fetches outside Tern" 'DownloadManager\.(Request|Query)|\.enqueue\(' "-"
check "a second HTTP stack: HttpEngine, Cronet, java.net.http, OkHttp, Ktor or Retrofit" \
  'HttpEngine|org\.chromium\.net|(^|[^A-Za-z0-9_])UrlRequest|BidirectionalStream|java\.net\.http\.|okhttp3|io\.ktor|retrofit2' "-"
check "a web view, which loads what a page asks for by itself" 'android\.webkit\.|(^|[^A-Za-z0-9_])WebView|loadUrl\(' "-"
check "Android's name resolver" 'DnsResolver' "-"

[ "$fail" -eq 0 ] && echo "ok   the network has one door"
exit "$fail"
