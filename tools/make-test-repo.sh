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
# The real F-Droid repository writes entry.json first and the manifest last. A reader that
# expects the manifest up front sees such an archive as unsigned, so one fixture has that order.
python3 - "$WORK/entry.jar" "$OUT/repo/entry-manifest-last.jar" <<'PY'
import sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    names = [n for n in zin.namelist() if not n.endswith("/")]
    order = sorted(names, key=lambda n: (n.startswith("META-INF/"), n.endswith("MANIFEST.MF"), n))
    for name in order:
        zout.writestr(zin.getinfo(name), zin.read(name))
PY

signed_variant entry-older 1600000000000 "/index-v2.json"
signed_variant entry-newer 1800000000000 "/index-v2.json"
signed_variant entry-escaping 1700000000000 "/../../other/index-v2.json"

# The repository one publication later: org.example.two gains 0.6 and loses 0.5, org.example.one
# is untouched. Published as a full index and as a diff against the index above.
mkdir -p "$OUT/repo/diff"
python3 - "$WORK/index-v2.json" "$OUT/repo/index-v2-next.json" "$OUT/repo/diff/1700000000000.json" "$OUT/repo/diff/removed.json" <<'PY'
import json, sys

old_path, next_path, diff_path, removed_path = sys.argv[1:5]
index = json.load(open(old_path))
added = {
    "added": 1800000000000,
    "whatsNew": {"en-US": "Faster start."},
    "file": {"name": "/org.example.two_6.apk", "sha256": "7" * 64, "size": 650},
    "manifest": {
        "versionName": "0.6",
        "versionCode": 6,
        "usesSdk": {"minSdkVersion": 21, "targetSdkVersion": 34},
        "signer": {"sha256": ["2" * 64]},
        "nativecode": [],
    },
    "releaseChannels": [],
}
index["repo"]["timestamp"] = 1800000000000
two = index["packages"]["org.example.two"]
del two["versions"]["v5"]
two["versions"]["v6"] = added
two["metadata"]["summary"] = {"en-US": "A second example app, now faster"}
json.dump(index, open(next_path, "w"), sort_keys=True)

patch = {
    "repo": {"timestamp": 1800000000000},
    "packages": {
        "org.example.two": {
            "metadata": {"summary": {"en-US": "A second example app, now faster"}},
            "versions": {"v5": None, "v6": added},
        },
        "org.example.three": {"metadata": {"name": {"en-US": "Example Three"}}, "versions": {}},
    },
}
json.dump(patch, open(diff_path, "w"), sort_keys=True)
json.dump({"repo": {"timestamp": 1800000000000}, "packages": {"org.example.two": None}}, open(removed_path, "w"), sort_keys=True)
PY

signed_next() {
  local name="$1" diff_file="$2" diff_sha="$3"
  local next_sha next_size diff_size
  next_sha=$(sha256sum "$OUT/repo/index-v2-next.json" | cut -d' ' -f1)
  next_size=$(stat -c%s "$OUT/repo/index-v2-next.json")
  diff_size=$(stat -c%s "$OUT/repo/diff/$diff_file")
  [ -n "$diff_sha" ] || diff_sha=$(sha256sum "$OUT/repo/diff/$diff_file" | cut -d' ' -f1)
  mkdir -p "$WORK/$name"
  python3 - "$WORK/$name/entry.json" "$next_sha" "$next_size" "$diff_file" "$diff_sha" "$diff_size" <<'PY'
import json, sys

path, next_sha, next_size, diff_file, diff_sha, diff_size = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5], int(sys.argv[6])
entry = {
    "timestamp": 1800000000000,
    "version": 20000,
    "maxAge": 14,
    "index": {"name": "/index-v2-next.json", "sha256": next_sha, "size": next_size, "numPackages": 2},
    "diffs": {
        "1700000000000": {"name": "/diff/" + diff_file, "sha256": diff_sha, "size": diff_size, "numPackages": 2},
        "1650000000000": {"name": "/diff/1650000000000.json", "sha256": "9" * 64, "size": 10, "numPackages": 1},
    },
}
with open(path, "w") as f:
    json.dump(entry, f, sort_keys=True)
PY
  (cd "$WORK/$name" && jar cf "../$name.jar" entry.json)
  jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
    "$WORK/$name.jar" good >/dev/null
  cp "$WORK/$name.jar" "$OUT/repo/$name.jar"
}
signed_next entry-next 1700000000000.json ""
signed_next entry-next-removed removed.json ""
signed_next entry-next-wrong-diff-hash 1700000000000.json "$(printf '8%.0s' $(seq 1 64))"

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
# The shape of a real index-v1.json: "apps" is a list, "packages" maps each package to its versions.
index = {
    "repo": {"timestamp": 1700000000000, "name": "Jackdaw Test Repo"},
    "requests": {"install": [], "uninstall": []},
    "apps": [
        {"packageName": "org.example.one", "name": "Example One"},
        {"packageName": "org.example.two", "name": "Example Two"},
    ],
    "packages": {
        "org.example.two": [
            {
                "packageName": "org.example.two",
                "versionName": "9.0",
                "versionCode": 90,
                "apkName": "org.example.two_90.apk",
                "hash": "8" * 64,
                "hashType": "sha256",
                "size": 700,
                "minSdkVersion": 21,
                "added": 1700000000000,
            },
        ],
        "org.example.one": [
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
                "added": 1700000000000,
            },
        ],
    },
}
with open(path, "w") as f:
    json.dump(index, f, sort_keys=True)
PY
(cd "$WORK" && jar cf index-v1.jar index-v1.json)
jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA256withRSA -digestalg SHA-256 \
  "$WORK/index-v1.jar" good >/dev/null
cp "$WORK/index-v1.jar" "$OUT/repo-v1/index-v1.jar"

# What an old repository still serves: the same index under a SHA-1 signature.
(cd "$WORK" && mkdir sha1 && cp index-v1.json sha1/ && cd sha1 && jar cf index-v1.jar index-v1.json)
jarsigner -keystore "$WORK/good.jks" -storepass "$STOREPASS" -sigalg SHA1withRSA -digestalg SHA1 \
  -J-Djava.security.properties=/dev/null "$WORK/sha1/index-v1.jar" good >/dev/null 2>&1
cp "$WORK/sha1/index-v1.jar" "$OUT/repo-v1/index-v1-sha1.jar"
printf '%s' "$FINGERPRINT" > "$OUT/repo-v1/fingerprint.txt"

echo "Wrote fixtures to $OUT"
