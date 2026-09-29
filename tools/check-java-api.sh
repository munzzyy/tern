#!/bin/bash
# Runs tools/check-java-api.py on both modules, against minSdk, with what the installed SDK offers.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
MIN_SDK="$(grep -oE 'minSdk = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')"
API="$(ls -d "$ANDROID_HOME"/platforms/android-*/data/api-versions.xml | sort -V | tail -1)"
D8="$(ls -d "$ANDROID_HOME"/build-tools/*/lib/d8.jar | sort -V | tail -1)"
CORE=core/build/classes/kotlin/main
APP="$(dirname "$(find app/build/intermediates -path '*compileReleaseKotlin*' -name 'MainActivity.class' -o -path '*compileDebugKotlin*' -name 'MainActivity.class' | head -1)" 2>/dev/null || true)"
[ -d "$CORE" ] || { echo "FAIL $CORE is missing: build first"; exit 1; }
[ -n "$APP" ] || { echo "FAIL the app's classes are missing: build first"; exit 1; }
APP="${APP%/io/github/munzzyy/tern}"
python3 tools/check-java-api.py "$API" "$D8" "$MIN_SDK" "$CORE"
python3 tools/check-java-api.py "$API" "$D8" "$MIN_SDK" "$APP"
