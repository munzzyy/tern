#!/usr/bin/env bash
# Builds the APK inspection fixtures and records what Google's own tools say about each one.
# Keys are generated in a temp dir and deleted on exit; no private key ever lands in the repo.
set -euo pipefail

sdk=${ANDROID_HOME:-/home/cole/Android/Sdk}
bt=${BUILD_TOOLS:-$sdk/build-tools/37.0.0}
platform=${PLATFORM_JAR:-$sdk/platforms/android-37.0/android.jar}
aapt2=$bt/aapt2
zipalign=$bt/zipalign
apksigner=$bt/apksigner

root=$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)
out=$root/core/src/test/resources/fixtures/apk
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for tool in "$aapt2" "$zipalign" "$apksigner" "$platform"; do
    [[ -e $tool ]] || { echo "missing $tool" >&2; exit 1; }
done
for cmd in keytool python3 jq; do
    command -v "$cmd" >/dev/null || { echo "missing $cmd" >&2; exit 1; }
done

pkg=com.example.app
pass=fixture

keystore() {
    local name=$1 alg=$2 size=$3
    keytool -genkeypair -keystore "$work/$name.p12" -storetype PKCS12 -storepass "$pass" -keypass "$pass" \
        -alias "$name" -keyalg "$alg" -keysize "$size" -validity 10000 \
        -dname "CN=Example Fixture $name, O=Example" >/dev/null 2>&1
}

signer_args() {
    echo --ks "$work/$1.p12" --ks-pass "pass:$pass" --ks-key-alias "$1"
}

write_manifest() {
    local file=$1 code=$2 name=$3 min=$4 split=$5 body=$6 target=${7:-35} split_attr=""
    [[ -n $split ]] && split_attr=" split=\"$split\""
    cat >"$file" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$pkg"$split_attr
    android:versionCode="$code"
    android:versionName="$name">
    <uses-sdk android:minSdkVersion="$min" android:targetSdkVersion="$target" />
$body
</manifest>
EOF
}

app_body='    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <application android:hasCode="false" android:label="Fixture" />'
split_body='    <application android:hasCode="false" />'

link() {
    local manifest=$1 target=$2 abi=${3:-}
    "$aapt2" link -o "$work/linked.apk" -I "$platform" --manifest "$manifest"
    if [[ -n $abi ]]; then
        python3 - "$work/linked.apk" "$abi" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.writestr(zipfile.ZipInfo(f"lib/{sys.argv[2]}/libfixture.so", (2020, 1, 1, 0, 0, 0)), b"\x7fELF fixture", zipfile.ZIP_DEFLATED)
EOF
    fi
    "$zipalign" -f 4 "$work/linked.apk" "$target"
}

sign() {
    local in=$1 target=$2 key=$3
    shift 3
    # shellcheck disable=SC2046
    "$apksigner" sign $(signer_args "$key") "$@" --in "$in" --out "$target"
    rm -f "$target.idsig"
}

keystore alpha RSA 2048
keystore beta EC 256
keystore gamma RSA 2048

rm -rf "$out"
mkdir -p "$out"

write_manifest "$work/v1.xml" 1 1.0 21 "" "$app_body"
link "$work/v1.xml" "$work/app-v1.unsigned.apk"
sign "$work/app-v1.unsigned.apk" "$out/app-v1.apk" alpha --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true

write_manifest "$work/v2.xml" 2 2.0 21 "" "$app_body"
link "$work/v2.xml" "$work/app-v2.unsigned.apk"
sign "$work/app-v2.unsigned.apk" "$out/app-v2.apk" alpha --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true
sign "$work/app-v2.unsigned.apk" "$out/app-v2-otherkey.apk" beta --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true

# apksigner refuses a v1-only signature once targetSdk reaches 30.
write_manifest "$work/jar.xml" 1 1.0-jar 16 "" "$app_body" 29
link "$work/jar.xml" "$work/app-jar.unsigned.apk"
sign "$work/app-jar.unsigned.apk" "$out/app-jar-only.apk" alpha --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false

write_manifest "$work/v2only.xml" 2 2.0 24 "" "$app_body"
link "$work/v2only.xml" "$work/app-v2only.unsigned.apk"
sign "$work/app-v2only.unsigned.apk" "$out/app-v2-only.apk" alpha --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false

write_manifest "$work/rotated.xml" 4 4.0 24 "" "$app_body"
link "$work/rotated.xml" "$work/app-rotated.unsigned.apk"
# shellcheck disable=SC2046
"$apksigner" rotate --out "$work/lineage" --old-signer $(signer_args alpha) --new-signer $(signer_args gamma)
# shellcheck disable=SC2046
"$apksigner" sign $(signer_args alpha) --next-signer $(signer_args gamma) --lineage "$work/lineage" \
    --in "$work/app-rotated.unsigned.apk" --out "$out/app-rotated.apk"
rm -f "$out/app-rotated.apk.idsig"

mkdir -p "$work/split"
write_manifest "$work/split/base.xml" 3 3.0 24 "" "$app_body"
link "$work/split/base.xml" "$work/split/base.apk"
sign "$work/split/base.apk" "$out/split-base.apk" alpha
for config in arm64_v8a x86_64 armeabi_v7a xxhdpi mdpi en de; do
    abi=""
    case $config in arm64_v8a | x86_64 | armeabi_v7a) abi=${config//_v/-v} ;; esac
    write_manifest "$work/split/$config.xml" 3 3.0 24 "config.$config" "$split_body"
    link "$work/split/$config.xml" "$work/split/$config.apk" "$abi"
    sign "$work/split/$config.apk" "$out/split-config.$config.apk" alpha
done

python3 - "$work/app-v1.unsigned.apk" "$out/app-zip64.apk" <<'EOF'
import struct, sys
data = open(sys.argv[1], "rb").read()
eocd = data.rfind(b"PK\x05\x06")
count, cd_size, cd_offset = struct.unpack_from("<HII", data, eocd + 10)
central = bytearray()
pos = cd_offset
for _ in range(count):
    head = bytearray(data[pos:pos + 46])
    name_len, extra_len, comment_len = struct.unpack_from("<HHH", head, 28)
    crc, csize, usize = struct.unpack_from("<III", head, 16)
    lho = struct.unpack_from("<I", head, 42)[0]
    name = data[pos + 46:pos + 46 + name_len]
    extra = data[pos + 46 + name_len:pos + 46 + name_len + extra_len]
    comment = data[pos + 46 + name_len + extra_len:pos + 46 + name_len + extra_len + comment_len]
    zip64 = struct.pack("<HHQQQ", 1, 24, usize, csize, lho)
    struct.pack_into("<II", head, 20, 0xFFFFFFFF, 0xFFFFFFFF)
    struct.pack_into("<H", head, 30, extra_len + len(zip64))
    struct.pack_into("<I", head, 42, 0xFFFFFFFF)
    central += head + name + extra + zip64 + comment
    pos += 46 + name_len + extra_len + comment_len
body = data[:cd_offset]
eocd64_offset = len(body) + len(central)
eocd64 = struct.pack("<IQHHIIQQQQ", 0x06064B50, 44, 45, 45, 0, 0, count, count, len(central), cd_offset)
locator = struct.pack("<IIQI", 0x07064B50, 0, eocd64_offset, 1)
end = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, 0xFFFF, 0xFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0)
open(sys.argv[2], "wb").write(body + central + eocd64 + locator + end)
EOF

python3 - "$out" <<'EOF'
import json, sys, zipfile
out = sys.argv[1]
configs = ["arm64_v8a", "x86_64", "armeabi_v7a", "xxhdpi", "mdpi", "en", "de"]
stamp = (2020, 1, 1, 0, 0, 0)

def add(z, name, payload, method):
    info = zipfile.ZipInfo(name, stamp)
    info.compress_type = method
    z.writestr(info, payload)

def read(name):
    return open(f"{out}/{name}", "rb").read()

obb = "Android/obb/com.example.app/main.3.com.example.app.obb"
manifest = {
    "xapk_version": 2,
    "package_name": "com.example.app",
    "name": "Fixture",
    "version_code": "3",
    "version_name": "3.0",
    "min_sdk_version": "24",
    "target_sdk_version": "35",
    "split_apks": [{"file": "com.example.app.apk", "id": "base"}]
        + [{"file": f"config.{c}.apk", "id": f"config.{c}"} for c in configs],
    "expansions": [{"file": obb, "install_location": "EXTERNAL_STORAGE", "install_path": obb}],
}
with zipfile.ZipFile(f"{out}/bundle.xapk", "w") as z:
    add(z, "manifest.json", json.dumps(manifest, indent=2).encode(), zipfile.ZIP_DEFLATED)
    add(z, "com.example.app.apk", read("split-base.apk"), zipfile.ZIP_STORED)
    for c in configs:
        add(z, f"config.{c}.apk", read(f"split-config.{c}.apk"), zipfile.ZIP_STORED)
    add(z, obb, b"expansion fixture", zipfile.ZIP_STORED)

with zipfile.ZipFile(f"{out}/bundle.apks", "w") as z:
    add(z, "toc.pb", b"", zipfile.ZIP_DEFLATED)
    add(z, "splits/base-master.apk", read("split-base.apk"), zipfile.ZIP_DEFLATED)
    for c in configs:
        add(z, f"splits/base-{c}.apk", read(f"split-config.{c}.apk"), zipfile.ZIP_DEFLATED)
EOF

badging_field() {
    sed -n "s/^package:.* $1='\([^']*\)'.*/\1/p" <<<"$2" | head -1
}

describe() {
    local apk=$1 badging verify all certs schemes lineage lines split min target perms
    badging=$("$aapt2" dump badging "$apk" 2>/dev/null)
    split=$(badging_field split "$badging")
    min=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
    target=$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
    perms=$(sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" <<<"$badging" | jq -R . | jq -s .)
    if verify=$("$apksigner" verify -v --print-certs "$apk" 2>/dev/null); then
        certs=$(sed -n 's/^.*Signer.* certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<<"$verify" | sort -u | jq -R . | jq -s .)
        # apksigner skips JAR signatures for minSdk 24 and up unless asked about older releases.
        all=$("$apksigner" verify -v --min-sdk-version 18 "$apk" 2>/dev/null || true)
        schemes=$(sed -n 's/^Verified using v\([0-9.]*\) scheme.*: true$/\1/p' <<<"$verify"$'\n'"$all" | sed 's/3\.1/31/' | sort -un | jq -R 'tonumber' | jq -s .)
    else
        certs='[]'
        schemes='[]'
    fi
    lineage='[]'
    if lines=$("$apksigner" lineage --in "$apk" --print-certs 2>/dev/null); then
        lineage=$(sed -n 's/^Signer #[0-9]* in lineage certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<<"$lines" | jq -R . | jq -s .)
    fi
    jq -n --arg package "$(badging_field name "$badging")" \
        --arg versionCode "$(badging_field versionCode "$badging")" \
        --arg versionName "$(badging_field versionName "$badging")" \
        --arg split "$split" --arg min "$min" --arg target "$target" \
        --argjson permissions "$perms" --argjson certificates "$certs" \
        --argjson schemes "$schemes" --argjson lineage "$lineage" \
        '{package: $package, versionCode: ($versionCode | tonumber), versionName: $versionName,
          split: (if $split == "" then null else $split end),
          minSdk: (if $min == "" then null else ($min | tonumber) end),
          targetSdk: (if $target == "" then null else ($target | tonumber) end),
          permissions: $permissions, certificates: $certificates, schemes: $schemes, lineage: $lineage}'
}

{
    echo '{'
    first=1
    for apk in "$out"/*.apk; do
        [[ $first == 1 ]] || echo ','
        first=0
        printf '%s: ' "$(jq -Rn --arg n "$(basename "$apk")" '$n')"
        describe "$apk"
    done
    echo '}'
} | jq --sort-keys . >"$out/expected.json"

ls -l "$out"
