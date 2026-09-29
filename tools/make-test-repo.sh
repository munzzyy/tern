#!/usr/bin/env bash
# Builds the synthetic F-Droid repository fixtures used by FDroidRepoSourceTest.
# Requires jarsigner, keytool, jar, zip, python3 on PATH. Never commits the keystore.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/core/src/test/resources/fixtures/fdroid"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT/repo" "$OUT/repo-v1"
STOREPASS=jackdawtest

keytool -genkeypair -alias good -keystore "$WORK/good.jks" -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Jackdaw Test Repo" -storetype PKCS12 >/dev/null

keytool -genkeypair -alias other -keystore "$WORK/other.jks" -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Jackdaw Test Repo Impostor" -storetype PKCS12 >/dev/null

python3 - "$WORK/index-v2.json" <<'PY'
import json, sys

path = sys.argv[1]


def hex64(digit):
    return str(digit) * 64


index = {
    "repo": {"timestamp": 1700000000000, "version": 20000, "name": {"en-US": "Jackdaw Test Repo"}},
    "packages": {
        "org.example.one": {
            "metadata": {
                "name": {"en-US": "Example One"},
                "summary": {"en-US": "An example app with split builds"},
            },
            "versions": {
                "v30": {
                    "added": 1700000000000,
                    "file": {"name": "/org.example.one_30.apk", "sha256": hex64(1), "size": 1000},
                    "manifest": {
                        "versionName": "3.0",
                        "versionCode": 30,
                        "usesSdk": {"minSdkVersion": 99, "targetSdkVersion": 34},
                        "signer": {"sha256": [hex64(2)]},
                        "nativecode": [],
                    },
                    "releaseChannels": [],
                },
                "v20beta": {
                    "added": 1699000000000,
                    "file": {"name": "/org.example.one_20.apk", "sha256": hex64(3), "size": 900},
                    "manifest": {
                        "versionName": "2.0-beta",
                        "versionCode": 20,
                        "usesSdk": {"minSdkVersion": 21, "targetSdkVersion": 34},
                        "signer": {"sha256": [hex64(2)]},
                        "nativecode": [],
                    },
                    "releaseChannels": ["Beta"],
                },
                "v10arm64": {
                    "added": 1698000000000,
                    "file": {"name": "/org.example.one_10_arm64.apk", "sha256": hex64(4), "size": 800},
                    "manifest": {
                        "versionName": "1.0",
                        "versionCode": 10,
                        "usesSdk": {"minSdkVersion": 21, "targetSdkVersion": 34},
                        "signer": {"sha256": [hex64(2)]},
                        "nativecode": ["arm64-v8a"],
                    },
                    "releaseChannels": [],
                },
                "v10armv7": {
                    "added": 1698000000000,
                    "file": {"name": "/org.example.one_10_armv7.apk", "sha256": hex64(5), "size": 700},
                    "manifest": {
                        "versionName": "1.0",
                        "versionCode": 10,
                        "usesSdk": {"minSdkVersion": 21, "targetSdkVersion": 34},
                        "signer": {"sha256": [hex64(2)]},
                        "nativecode": ["armeabi-v7a"],
                    },
                    "releaseChannels": [],
                },
            },
        },
        "org.example.two": {
            "metadata": {
                "name": {"en-US": "Example Two"},
                "summary": {"en-US": "A second example app"},
            },
            "versions": {
                "v5": {
                    "added": 1697000000000,
                    "whatsNew": {"de": "Fehler behoben.", "en-US": "Fixed the crash on start."},
                    "file": {"name": "/org.example.two_5.apk", "sha256": hex64(6), "size": 600},
                    "manifest": {
                        "versionName": "0.5",
                        "versionCode": 5,
                        "usesSdk": {"minSdkVersion": 21, "targetSdkVersion": 34},
                        "signer": {"sha256": [hex64(2)]},
                        "nativecode": [],
                    },
                    "releaseChannels": [],
                },
            },
        },
    },
}

with open(path, "w") as f:
    json.dump(index, f, sort_keys=True)
PY

INDEX_SHA=$(sha256sum "$WORK/index-v2.json" | cut -d' ' -f1)
INDEX_SIZE=$(stat -c%s "$WORK/index-v2.json")

python3 - "$WORK/entry.json" "$INDEX_SHA" "$INDEX_SIZE" <<'PY'
import json, sys

path, index_sha, index_size = sys.argv[1], sys.argv[2], int(sys.argv[3])
entry = {
    "timestamp": 1700000000000,
    "version": 20000,
    "maxAge": 14,
    "index": {"name": "/index-v2.json", "sha256": index_sha, "size": index_size, "numPackages": 2},
    "diffs": {},
}
with open(path, "w") as f:
    json.dump(entry, f, sort_keys=True)
PY

(cd "$WORK" && jar cf entry.jar entry.json)
jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  "$WORK/entry.jar" good >/dev/null

keytool -exportcert -alias good -keystore "$WORK/good.jks" -storepass "$STOREPASS" -file "$WORK/good.der" >/dev/null
FINGERPRINT=$(sha256sum "$WORK/good.der" | cut -d' ' -f1)
printf '%s' "$FINGERPRINT" > "$OUT/repo/fingerprint.txt"

cp "$WORK/index-v2.json" "$OUT/repo/index-v2.json"
cp "$WORK/entry.jar" "$OUT/repo/entry.jar"

(cd "$WORK" && jar cf entry-rekeyed.jar entry.json)
jarsigner -keystore "$WORK/other.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  "$WORK/entry-rekeyed.jar" other >/dev/null
cp "$WORK/entry-rekeyed.jar" "$OUT/repo/entry-rekeyed.jar"

# Validly signed by the good key, but wrong in a way only the content shows:
# an index published before the one above, and an index name that leaves the repository.
signed_variant() {
  local name="$1" timestamp="$2" index_name="$3"
  mkdir -p "$WORK/$name"
  python3 - "$WORK/$name/entry.json" "$INDEX_SHA" "$INDEX_SIZE" "$timestamp" "$index_name" <<'PY'
import json, sys

path, index_sha, index_size, timestamp, index_name = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
entry = {
    "timestamp": timestamp,
    "version": 20000,
    "maxAge": 14,
    "index": {"name": index_name, "sha256": index_sha, "size": index_size, "numPackages": 2},
    "diffs": {},
}
with open(path, "w") as f:
    json.dump(entry, f, sort_keys=True)
PY
  (cd "$WORK/$name" && jar cf "../$name.jar" entry.json)
  jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
    "$WORK/$name.jar" good >/dev/null
  cp "$WORK/$name.jar" "$OUT/repo/$name.jar"
}
signed_variant entry-older 1600000000000 "/index-v2.json"
signed_variant entry-newer 1800000000000 "/index-v2.json"
signed_variant entry-escaping 1700000000000 "/../../other/index-v2.json"

python3 - "$WORK/entry.jar" "$WORK/entry-tampered.jar" <<'PY'
import json, sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
bad_entry = json.dumps({
    "timestamp": 1700000000000,
    "version": 20000,
    "maxAge": 14,
    "index": {"name": "/index-v2.json", "sha256": "0" * 64, "size": 1, "numPackages": 2},
    "diffs": {},
}).encode("utf-8")

with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w") as zout:
    for item in zin.infolist():
        data = bad_entry if item.filename == "entry.json" else zin.read(item.filename)
        zout.writestr(item, data)
PY
cp "$WORK/entry-tampered.jar" "$OUT/repo/entry-tampered.jar"

python3 - "$WORK/index-v1.json" <<'PY'
import json, sys

path = sys.argv[1]
index = {
    "repo": {"timestamp": 1700000000000, "name": "Jackdaw Test Repo"},
    "packages": [
        {
            "packageName": "org.example.one",
            "versionName": "1.0",
            "versionCode": 10,
            "apkName": "org.example.one_10.apk",
            "hash": "7" * 64,
            "hashType": "sha256",
            "size": 500,
            "minSdkVersion": 21,
            "nativecode": [],
        },
    ],
}
with open(path, "w") as f:
    json.dump(index, f, sort_keys=True)
PY
(cd "$WORK" && jar cf index-v1.jar index-v1.json)
jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  "$WORK/index-v1.jar" good >/dev/null
cp "$WORK/index-v1.jar" "$OUT/repo-v1/index-v1.jar"
printf '%s' "$FINGERPRINT" > "$OUT/repo-v1/fingerprint.txt"

echo "Wrote fixtures to $OUT"
