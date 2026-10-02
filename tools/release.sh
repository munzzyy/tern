#!/bin/bash
# Runs every gate CI runs, then builds and signs the release APK into dist/. Never run by CI.
# With --gates-only it stops after the gates and needs no key.
#
# The keystore lives outside the repository and its password stays in the system keyring
# (secret-tool lookup service tern-keystore key upload); nothing here prints it.
#
# apksigner comes from build-tools 34.0.0 on purpose: F-Droid's apksigcopier verifies and copies
# signatures made by that version and rejects what newer build-tools emit.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="${TERN_KEYSTORE:-$HOME/keys/tern-upload.jks}"
ALIAS=tern-upload
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME="$SDK"
SIGN_TOOLS_VERSION=34.0.0
APKSIGNER="$SDK/build-tools/$SIGN_TOOLS_VERSION/apksigner"
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk

# In the order of the build job in .github/workflows/ci.yml, plus the release notes.
gates() {
  bash tools/check-wrapper.sh
  python3 tools/strings.py check app/src/main/res
  bash tools/check-network-doors.sh
  bash tools/tests/network-doors/run.sh
  ./gradlew --no-daemon :core:test
  bash tools/check-handoff-page.sh --no-browser
  node tools/check-site.js
  ./gradlew --no-daemon :app:testDebugUnitTest
  ./gradlew --no-daemon :app:assembleRelease
  ./gradlew --no-daemon :app:lintRelease
  bash tools/check-java-api.sh
  bash tools/check-apk.sh "$UNSIGNED"
  bash tools/check-release-notes.sh
}

case "${1:-}" in
  --gates-only) gates; echo "== every gate passed =="; exit 0 ;;
  "") ;;
  *) echo "usage: tools/release.sh [--gates-only]"; exit 1 ;;
esac

[ -f "$KEYSTORE" ] || { echo "keystore not found: $KEYSTORE (see docs/RELEASING.md)"; exit 1; }
[ -x "$APKSIGNER" ] || { echo "build-tools $SIGN_TOOLS_VERSION is not installed"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean"; exit 1; }

./gradlew --no-daemon clean
gates

KSPW=$(secret-tool lookup service tern-keystore key upload) || {
  echo "no keystore password in the keyring (service tern-keystore key upload)"; exit 1;
}
export KSPW

VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)

mkdir -p dist
OUT="dist/tern-$VERSION.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass env:KSPW --out "$OUT" "$UNSIGNED"
unset KSPW
"$APKSIGNER" verify --print-certs "$OUT" | head -4

# Stable name, so releases/latest/download/tern.apk keeps working across versions.
cp "$OUT" dist/tern.apk
( cd dist && sha256sum "tern-$VERSION.apk" > "tern-$VERSION.apk.sha256" && sha256sum tern.apk > tern.apk.sha256 )
rm -f "$OUT.idsig" dist/tern.apk.idsig

echo "== artifacts =="
ls -l dist/
cat "dist/tern-$VERSION.apk.sha256"
