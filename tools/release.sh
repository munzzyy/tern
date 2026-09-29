#!/bin/bash
# Builds and signs the release APK into dist/. Never run by CI.
#
# The keystore lives outside the repository and its password stays in the system keyring
# (secret-tool lookup service jackdaw-keystore key release); nothing here prints it.
#
# apksigner comes from build-tools 34.0.0 on purpose: F-Droid's apksigcopier verifies and copies
# signatures made by that version and rejects what newer build-tools emit.
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="${JACKDAW_KEYSTORE:-$HOME/keys/jackdaw-release.jks}"
ALIAS=jackdaw-release
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
SIGN_TOOLS_VERSION=34.0.0
APKSIGNER="$SDK/build-tools/$SIGN_TOOLS_VERSION/apksigner"

[ -f "$KEYSTORE" ] || { echo "keystore not found: $KEYSTORE (see docs/RELEASING.md)"; exit 1; }
[ -x "$APKSIGNER" ] || { echo "build-tools $SIGN_TOOLS_VERSION is not installed"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree is not clean"; exit 1; }

KSPW=$(secret-tool lookup service jackdaw-keystore key release) || {
  echo "no keystore password in the keyring (service jackdaw-keystore key release)"; exit 1;
}
export KSPW

ANDROID_HOME="$SDK" ./gradlew --no-daemon clean :core:test :app:assembleRelease

VERSION=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | cut -d'"' -f2)
UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk
bash tools/check-apk.sh "$UNSIGNED"

mkdir -p dist
OUT="dist/jackdaw-$VERSION.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" --ks-pass env:KSPW --out "$OUT" "$UNSIGNED"
unset KSPW
"$APKSIGNER" verify --print-certs "$OUT" | head -4

# Stable name, so releases/latest/download/jackdaw.apk keeps working across versions.
cp "$OUT" dist/jackdaw.apk
( cd dist && sha256sum "jackdaw-$VERSION.apk" > "jackdaw-$VERSION.apk.sha256" && sha256sum jackdaw.apk > jackdaw.apk.sha256 )
rm -f "$OUT.idsig" dist/jackdaw.apk.idsig

echo "== artifacts =="
ls -l dist/
cat "dist/jackdaw-$VERSION.apk.sha256"
