#!/usr/bin/env bash
# Writes what qrencode makes of a dozen texts into the fixtures that QrEncoderTest compares with,
# then draws the codes of Stamp's own encoder and reads them back with zbarimg.
# Requires qrencode, zbarimg and python3 with Pillow on PATH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/core/src/test/resources/fixtures/qr"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2}

rm -rf "$OUT"
mkdir -p "$OUT"

# One file for each text: the text on the first line, then the squares, # for dark and . for light.
python3 - "$OUT" <<'PY'
import subprocess, sys
from pathlib import Path

out = Path(sys.argv[1])
filler = "Stamp checks every file before the installer of this device sees it. "


def cut(length):
    return (filler * 4)[:length]


texts = [
    ("01-one-letter", "A"),
    ("02-version-1-full", cut(14)),
    ("03-version-2", cut(15)),
    ("04-version-3", "https://example.org/stamp-apps.json"),
    ("05-address-short", "http://10.0.0.2:1024/" + "a" * 26),
    ("06-address-usual", "http://192.168.1.23:48211/mfrggzdfmztwq2lknnwg23tpoa"),
    ("07-address-longest", "http://192.168.100.200:65535/abcdefghijklmnopqrstuvwxyz"),
    ("08-not-ascii", "Gr\u00fc\u00dfe aus K\u00f6ln, \u6771\u4eac"),
    ("09-version-5", cut(84)),
    ("10-version-6", cut(106)),
    ("11-version-7", cut(122)),
    ("12-version-8", cut(152)),
    ("13-version-9", cut(180)),
    ("14-version-10-full", cut(213)),
]
for name, text in texts:
    art = subprocess.run(
        ["qrencode", "-t", "ASCII", "-m", "0", "-8", "-l", "M", "-o", "-", "--", text],
        check=True, capture_output=True, text=True,
    ).stdout.splitlines()
    size = len(art)
    rows = ["".join("#" if line.ljust(2 * size)[2 * x] == "#" else "." for x in range(size)) for line in art]
    (out / f"{name}.txt").write_text(text + "\n" + "\n".join(rows) + "\n", encoding="utf-8")
print(f"wrote {len(texts)} fixtures to {out}")
PY

cd "$ROOT"
./gradlew --no-daemon --console=plain -q :core:testClasses
KOTLIN=$(sed -n 's/.*org\.jetbrains\.kotlin\.jvm") version "\([^"]*\)".*/\1/p' build.gradle.kts)
STDLIB=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/$KOTLIN" -name "kotlin-stdlib-$KOTLIN.jar" | head -1)
CLASSES="$ROOT/core/build/classes/kotlin/main:$ROOT/core/build/classes/kotlin/test:$STDLIB"

fail=0
for fixture in "$OUT"/*.txt; do
  name=$(basename "$fixture" .txt)
  head -1 "$fixture" > "$WORK/$name.text"
  "$JAVA_HOME/bin/java" -cp "$CLASSES" io.github.munzzyy.stamp.core.qr.QrSquaresKt "$WORK/$name.text" > "$WORK/$name.squares"
  python3 - "$WORK/$name.squares" "$WORK/$name.png" <<'PY'
import sys
from PIL import Image

rows = open(sys.argv[1], encoding="utf-8").read().splitlines()[1:]
size, border, scale = len(rows), 4, 8
image = Image.new("L", ((size + 2 * border) * scale,) * 2, 255)
for y, row in enumerate(rows):
    for x, square in enumerate(row):
        if square == "#":
            image.paste(0, ((x + border) * scale, (y + border) * scale, (x + border + 1) * scale, (y + border + 1) * scale))
image.save(sys.argv[2])
PY
  # Without -Sbinary zbarimg guesses a character set and spoils text that is not ASCII.
  zbarimg --quiet --raw -Sbinary "$WORK/$name.png" > "$WORK/$name.read" || true
  head -c -1 "$WORK/$name.text" > "$WORK/$name.expected"
  if cmp -s "$WORK/$name.expected" "$WORK/$name.read"; then
    echo "ok   $name: $(head -1 "$WORK/$name.squares"), zbarimg read back $(stat -c %s "$WORK/$name.read") bytes, the same as went in"
  else
    echo "FAIL $name: zbarimg read $(stat -c %s "$WORK/$name.read") bytes that are not the text"
    fail=1
  fi
done
exit $fail
