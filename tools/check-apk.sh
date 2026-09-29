#!/bin/bash
# Gates the APK that ships, not the source tree: permissions, cleartext policy, size, leftovers.
set -euo pipefail
APK="${1:?usage: check-apk.sh <apk>}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AAPT=$(ls "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)
MAX_BYTES=$((5 * 1024 * 1024))
fail=0

expected=$(sort <<'LIST'
android.permission.ACCESS_NETWORK_STATE
android.permission.ENFORCE_UPDATE_OWNERSHIP
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_DATA_SYNC
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.QUERY_ALL_PACKAGES
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.REQUEST_DELETE_PACKAGES
android.permission.REQUEST_INSTALL_PACKAGES
android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION
LIST
)
actual=$("$AAPT" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | grep -v 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$' | sort -u)
if [ "$expected" != "$actual" ]; then
  echo "FAIL permissions differ from the documented list"
  diff <(echo "$expected") <(echo "$actual") || true
  fail=1
else
  echo "ok   permissions match the documented list ($(echo "$actual" | wc -l))"
fi

manifest=$("$AAPT" dump xmltree --file AndroidManifest.xml "$APK")
if echo "$manifest" | grep -q 'usesCleartextTraffic.*=true'; then
  echo "FAIL manifest allows cleartext traffic"; fail=1
else
  echo "ok   manifest does not allow cleartext traffic"
fi
if echo "$manifest" | grep -q 'android:debuggable.*=true'; then
  echo "FAIL apk is debuggable"; fail=1
else
  echo "ok   apk is not debuggable"
fi

# Release builds shorten resource paths (res/8G.xml), so the file is looked up by its resource name.
netfile=$("$AAPT" dump resources "$APK" | awk '
  /resource 0x[0-9a-f]+ xml\/network_security_config/ { found = 1; next }
  found && /\(file\)/ { print $3; exit }')
netconfig=""
if [ -n "$netfile" ]; then
  netconfig=$("$AAPT" dump xmltree --file "$netfile" "$APK" || true)
fi
if [ -z "$netconfig" ]; then
  echo "FAIL network security config is missing from the apk"; fail=1
elif echo "$netconfig" | grep -q 'cleartextTrafficPermitted.*=true'; then
  echo "FAIL network security config permits cleartext somewhere"; fail=1
else
  echo "ok   network security config refuses cleartext everywhere"
fi

badging=$("$AAPT" dump badging "$APK")
television=""
echo "$badging" | grep -q "^leanback-launchable-activity: name='[^']" || television="$television no-launcher-entry"
echo "$badging" | grep -q "^application: .* banner='[^']" || television="$television no-banner"
echo "$badging" | grep -q "uses-feature-not-required: name='android.hardware.touchscreen'" || television="$television touchscreen-required"
echo "$badging" | grep -q "uses-feature-not-required: name='android.software.leanback'" || television="$television leanback-required"
if [ -n "$television" ]; then
  echo "FAIL a television could not show or install the app:$television"; fail=1
else
  echo "ok   a television sees the app: launcher entry, banner, no touch screen required"
fi

# A device refuses an apk that has native code, but none for its own processor.
abis=$(unzip -Z1 "$APK" 'lib/*' 2>/dev/null | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)
missing=""
if [ -n "$abis" ]; then
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    case " $abis" in *" $abi "*) ;; *) missing="$missing $abi" ;; esac
  done
fi
if [ -n "$missing" ]; then
  echo "FAIL native code is missing for:$missing"; fail=1
else
  echo "ok   native code for every processor (${abis:-none at all})"
fi

size=$(stat -c %s "$APK")
if [ "$size" -gt "$MAX_BYTES" ]; then
  echo "FAIL apk is $size bytes, budget is $MAX_BYTES"; fail=1
else
  echo "ok   apk is $size bytes (budget $MAX_BYTES)"
fi

# Text, not class names: R8 renames classes, so a class name is absent even when the class is there.
leftovers=$(unzip -p "$APK" 'classes*.dex' | strings | grep -E 'stamp-stand-in-engine|forge\.test' | head -3 || true)
if [ -n "$leftovers" ]; then
  echo "FAIL test code reached the release apk:"; echo "$leftovers"; fail=1
else
  echo "ok   no test code in the release apk"
fi

exit $fail
