#!/usr/bin/env bash
# Builds the APK fixtures and records what Google's own tools say about each one.
#   make-test-apks.sh            both sets, each with new keys
#   make-test-apks.sh apk        the inspection fixtures, fixtures/apk
#   make-test-apks.sh signing    the signature fixtures, fixtures/signing
# Keys are generated in a temp dir and deleted on exit; no private key ever lands in the repo.
set -euo pipefail

sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
bt=${BUILD_TOOLS:-$sdk/build-tools/37.0.0}
platform=${PLATFORM_JAR:-$sdk/platforms/android-37.0/android.jar}
aapt2=$bt/aapt2
zipalign=$bt/zipalign
apksigner=$bt/apksigner

root=$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)
fixtures=$root/core/src/test/resources/fixtures
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for tool in "$aapt2" "$zipalign" "$apksigner" "$platform"; do
    [[ -e $tool ]] || { echo "missing $tool" >&2; exit 1; }
done
for cmd in keytool jarsigner python3 jq; do
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

jar_sign() {
    local in=$1 target=$2 key=$3 algorithm=$4
    jarsigner -keystore "$work/$key.p12" -storetype PKCS12 -storepass "$pass" -sigalg "$algorithm" -digestalg SHA-256 \
        -signedjar "$target" "$in" "$key" >/dev/null
}

badging_field() {
    sed -n "s/^package:.* $1='\([^']*\)'.*/\1/p" <<<"$2" | head -1
}

certs_of() {
    sed -n 's/^.*Signer.* certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' <<<"$1" | sort -u | jq -R . | jq -s .
}

# apksigner prints every signer of a v3 and a v3.1 block with the versions it is for; this keeps the ones for one version.
certs_for() {
    awk -v level="$1" '/ certificate SHA-256 digest: / {
        lo = 0; hi = 2147483647
        if (match($0, /minSdkVersion=[0-9]+/)) lo = substr($0, RSTART + 14, RLENGTH - 14)
        if (match($0, /maxSdkVersion=[0-9]+/)) hi = substr($0, RSTART + 14, RLENGTH - 14)
        if (level + 0 >= lo + 0 && level + 0 <= hi + 0) print $NF
    }' <<<"$2" | sort -u | jq -R . | jq -s .
}

# What a device of one Android version goes by, for each version in the list: null where apksigner refuses the file.
by_sdk() {
    local apk=$1 level verify
    shift
    for level in "$@"; do
        if verify=$("$apksigner" verify -v --print-certs --min-sdk-version "$level" --max-sdk-version "$level" "$apk" 2>/dev/null); then
            jq -n --arg level "$level" --argjson certificates "$(certs_for "$level" "$verify")" '{($level): $certificates}'
        else
            jq -n --arg level "$level" '{($level): null}'
        fi
    done | jq -s add
}

describe() {
    local apk=$1 badging verify all certs schemes lineage lines split min target perms levels
    shift
    badging=$("$aapt2" dump badging "$apk" 2>/dev/null)
    split=$(badging_field split "$badging")
    min=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
    target=$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
    perms=$(sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" <<<"$badging" | jq -R . | jq -s .)
    if verify=$("$apksigner" verify -v --print-certs "$apk" 2>/dev/null); then
        certs=$(certs_of "$verify")
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
    levels='null'
    [[ $# -gt 0 ]] && levels=$(by_sdk "$apk" "$@")
    jq -n --arg package "$(badging_field name "$badging")" \
        --arg versionCode "$(badging_field versionCode "$badging")" \
        --arg versionName "$(badging_field versionName "$badging")" \
        --arg split "$split" --arg min "$min" --arg target "$target" \
        --argjson permissions "$perms" --argjson certificates "$certs" \
        --argjson schemes "$schemes" --argjson lineage "$lineage" --argjson levels "$levels" \
        '{package: $package, versionCode: ($versionCode | tonumber), versionName: $versionName,
          split: (if $split == "" then null else $split end),
          minSdk: (if $min == "" then null else ($min | tonumber) end),
          targetSdk: (if $target == "" then null else ($target | tonumber) end),
          permissions: $permissions, certificates: $certificates, schemes: $schemes, lineage: $lineage}
         + (if $levels == null then {} else {bySdk: $levels} end)'
}

write_expected() {
    local out=$1 first=1 apk
    shift
    {
        echo '{'
        for apk in "$out"/*.apk; do
            [[ $first == 1 ]] || echo ','
            first=0
            printf '%s: ' "$(jq -Rn --arg n "$(basename "$apk")" '$n')"
            describe "$apk" "$@"
        done
        echo '}'
    } | jq --sort-keys . >"$out/expected.json"
}

build_apk_set() {
    local out=$fixtures/apk config abi

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

    write_expected "$out"
    ls -l "$out"
}

# Adds one stored entry so that the entries of the file end exactly at the offset asked for.
pad_to() {
    python3 - "$1" "$2" "$3" <<'EOF'
import shutil, struct, sys, zipfile
source, target, end = sys.argv[1], sys.argv[2], int(sys.argv[3])
data = open(source, "rb").read()
eocd = data.rfind(b"PK\x05\x06")
entries_end = struct.unpack_from("<I", data, eocd + 16)[0]
name = "assets/pad.bin"
length = end - entries_end - 30 - len(name)
if length < 0:
    sys.exit(f"{source} is already past {end}")
shutil.copy(source, target)
with zipfile.ZipFile(target, "a") as z:
    info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    # 251 is prime, so no two chunks of 1 MiB hold the same bytes.
    z.writestr(info, bytes(i % 251 for i in range(length)))
EOF
}

signing_block_offset() {
    python3 - "$1" <<'EOF'
import struct, sys
data = open(sys.argv[1], "rb").read()
eocd = data.rfind(b"PK\x05\x06")
cd = struct.unpack_from("<I", data, eocd + 16)[0]
if data[cd - 16:cd] != b"APK Sig Block 42":
    sys.exit("no signing block")
print(cd - struct.unpack_from("<Q", data, cd - 24)[0] - 8)
EOF
}

build_signing_set() {
    local out=$fixtures/signing apk mib=$((1024 * 1024)) at
    local v1=(--v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false)
    local v2=(--v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false)
    local v3=(--v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true)
    local v2v3=(--v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true)
    local all=(--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true)

    keystore rsa2048 RSA 2048
    keystore rsa4096 RSA 4096
    keystore ec256 EC 256
    keystore ec384 EC 384
    keystore dsa2048 DSA 2048
    keystore second EC 256
    keystore third RSA 2048

    rm -rf "$out"
    mkdir -p "$out"

    write_manifest "$work/s21.xml" 7 7.0 21 "" "$app_body"
    link "$work/s21.xml" "$work/s21.apk"
    write_manifest "$work/s24.xml" 7 7.0 24 "" "$app_body"
    link "$work/s24.xml" "$work/s24.apk"
    write_manifest "$work/s28.xml" 7 7.0 28 "" "$app_body"
    link "$work/s28.xml" "$work/s28.apk"
    write_manifest "$work/s21t29.xml" 7 7.0 21 "" "$app_body" 29
    link "$work/s21t29.xml" "$work/s21t29.apk"
    write_manifest "$work/s21t30.xml" 7 7.0 21 "" "$app_body" 30
    link "$work/s21t30.xml" "$work/s21t30.apk"

    sign "$work/s24.apk" "$out/v2-rsa-sha256.apk" rsa2048 "${v2[@]}"
    sign "$work/s28.apk" "$out/v3-rsa-sha256.apk" rsa2048 "${v3[@]}"
    sign "$work/s24.apk" "$out/v2v3-ec-sha256.apk" ec256 "${v2v3[@]}"
    sign "$work/s24.apk" "$out/v2v3-rsa-sha512.apk" rsa4096 "${v2v3[@]}"
    sign "$work/s24.apk" "$out/v2v3-ec-sha512.apk" ec384 "${v2v3[@]}"
    sign "$work/s24.apk" "$out/v2-dsa-sha256.apk" dsa2048 "${v2[@]}"
    sign "$work/s21.apk" "$out/v1v2v3-rsa-sha256.apk" rsa2048 "${all[@]}"
    sign "$work/s21t29.apk" "$out/v1-rsa-target29.apk" rsa2048 "${v1[@]}"

    # shellcheck disable=SC2046
    sign "$work/s21t29.apk" "$out/v1-two-signers.apk" rsa2048 --next-signer $(signer_args ec256) "${v1[@]}"
    # shellcheck disable=SC2046
    sign "$work/s24.apk" "$out/v2-two-signers.apk" rsa2048 --next-signer $(signer_args ec256) "${v2[@]}"

    # apksigner will not make this one, and Android 11 will not take it: a JAR signature alone on a target of 30.
    jar_sign "$work/s21t30.apk" "$out/v1-rsa-target30.apk" rsa2048 SHA256withRSA
    # An entry added by a second signer: the first one has signed everything but that entry.
    jar_sign "$work/s21t29.apk" "$work/uneven.apk" rsa2048 SHA256withRSA
    python3 - "$work/uneven.apk" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "a") as z:
    z.writestr(zipfile.ZipInfo("assets/late.txt", (2020, 1, 1, 0, 0, 0)), b"added by the second signer", zipfile.ZIP_DEFLATED)
EOF
    jar_sign "$work/uneven.apk" "$out/v1-signers-differ.apk" ec256 SHA256withECDSA

    # shellcheck disable=SC2046
    "$apksigner" rotate --out "$work/lineage2" --old-signer $(signer_args rsa2048) --new-signer $(signer_args second)
    # shellcheck disable=SC2046
    "$apksigner" rotate --in "$work/lineage2" --out "$work/lineage3" --old-signer $(signer_args second) --new-signer $(signer_args third)

    # Rotation from Android 9 on: the rotated signer and its lineage sit in the v3 block.
    # shellcheck disable=SC2046
    "$apksigner" sign $(signer_args rsa2048) --next-signer $(signer_args second) --lineage "$work/lineage2" \
        --rotation-min-sdk-version 28 "${v3[@]}" --in "$work/s28.apk" --out "$out/v3-rotated.apk"
    # The same for an app that also runs on Android 7: the v2 block is signed by the first key, the v3 block by the second.
    # shellcheck disable=SC2046
    "$apksigner" sign $(signer_args rsa2048) --next-signer $(signer_args second) --lineage "$work/lineage2" \
        --rotation-min-sdk-version 28 "${v2v3[@]}" --in "$work/s24.apk" --out "$out/v2v3-rotated.apk"
    # Rotation from Android 13 on, twice: the v3.1 block, and a lineage with a link in the middle.
    # shellcheck disable=SC2046
    "$apksigner" sign $(signer_args rsa2048) --next-signer $(signer_args second) --next-signer $(signer_args third) \
        --lineage "$work/lineage3" "${all[@]}" --in "$work/s21.apk" --out "$out/v31-rotated-twice.apk"

    pad_to "$work/s24.apk" "$work/over.apk" $((mib + 300000))
    sign "$work/over.apk" "$out/big-two-chunks.apk" rsa2048 "${v2v3[@]}" --alignment-preserved true
    pad_to "$work/s24.apk" "$work/boundary.apk" "$mib"
    sign "$work/boundary.apk" "$out/big-one-chunk-exactly.apk" ec256 "${v2v3[@]}" --alignment-preserved true
    at=$(signing_block_offset "$out/big-one-chunk-exactly.apk")
    [[ $at == "$mib" ]] || { echo "big-one-chunk-exactly.apk has its signing block at $at, not at $mib" >&2; exit 1; }
    at=$(signing_block_offset "$out/big-two-chunks.apk")
    [[ $at -gt $mib && $at -lt $((2 * mib)) ]] || { echo "big-two-chunks.apk has its signing block at $at" >&2; exit 1; }

    rm -f "$out"/*.idsig

    for apk in "$out"/*.apk; do
        case $(basename "$apk") in
            v1-rsa-target30.apk | v1-signers-differ.apk)
                if "$apksigner" verify -v "$apk" >/dev/null 2>&1; then
                    echo "apksigner accepts $apk, which is there to be refused" >&2
                    exit 1
                fi
                ;;
            *)
                if ! "$apksigner" verify -v "$apk" >/dev/null 2>&1; then
                    echo "apksigner rejects $apk" >&2
                    exit 1
                fi
                ;;
        esac
    done

    write_expected "$out" 24 27 28 29 30 32 33 36
    ls -l "$out"
}

case ${1:-all} in
    apk) build_apk_set ;;
    signing) build_signing_set ;;
    all)
        build_apk_set
        build_signing_set
        ;;
    *)
        echo "usage: $0 [apk|signing]" >&2
        exit 1
        ;;
esac
