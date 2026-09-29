#!/usr/bin/env python3
"""Gives the app another name everywhere: package, folders, file names, text.

  tools/rename.py <Old> <New>

The old name is replaced only where it is the name, never inside another word: capitalised when no
capital letter stands right before it (so "StampApp" and "anExportOfStamps" change, "AStampPad"
does not), in small letters only as a word of its own (so "timestamp" and "stamped" stay), in
capitals only outside a longer word in capitals. A suffix after the capitalised name is kept, which
is what the translations that inflect it need. Test fixtures are left alone, because some are
signed and a changed byte breaks them; regenerate the ones that name the app with their scripts.
"""
import re
import subprocess
import sys
from pathlib import Path

KEEP_PREFIXES = ("core/src/test/resources/", "app/src/androidTest/assets/")
KEEP_FILES = {"tools/make-test-apks.sh", "tools/make-test-repo.sh", "tools/rename.py"}


def rules(old, new):
    return [
        (re.compile(r"(?<![A-Z])" + old), new),
        (re.compile(r"(?<![A-Za-z])" + old.lower() + r"(?![a-z])"), new.lower()),
        (re.compile(r"(?<![A-Z])" + old.upper() + r"(?![A-Z])"), new.upper()),
        (re.compile(r"-D" + old.lower() + r"\."), "-D" + new.lower() + "."),
    ]


def rename(text, table):
    for pattern, replacement in table:
        text = pattern.sub(replacement, text)
    return text


def kept(path):
    return path.startswith(KEEP_PREFIXES) or path in KEEP_FILES


def main():
    if len(sys.argv) != 3 or not all(re.fullmatch(r"[A-Z][a-z]+", n) for n in sys.argv[1:]):
        sys.exit("usage: tools/rename.py <Old> <New>, each one capitalised word of letters")
    old, new = sys.argv[1], sys.argv[2]
    table = rules(old, new)
    root = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True).stdout.strip())
    if subprocess.run(["git", "status", "--porcelain"], cwd=root, capture_output=True, text=True).stdout.strip():
        sys.exit("Commit first: the rename wants a clean tree")
    files = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, check=True).stdout.splitlines()

    changed = 0
    for name in files:
        if kept(name):
            continue
        path = root / name
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, IsADirectoryError, FileNotFoundError):
            continue
        renamed = rename(text, table)
        if renamed != text:
            path.write_text(renamed, encoding="utf-8")
            changed += 1

    moved = 0
    for name in sorted(files, key=lambda n: -n.count("/")):
        if kept(name):
            continue
        target = rename(name, table)
        if target != name and (root / name).exists():
            (root / target).parent.mkdir(parents=True, exist_ok=True)
            subprocess.run(["git", "mv", name, target], cwd=root, check=True)
            moved += 1
    for folder in sorted((p for p in root.rglob("*") if p.is_dir() and ".git" not in p.parts), key=lambda p: -len(p.parts)):
        if not any(folder.iterdir()):
            folder.rmdir()

    print(f"{changed} files changed, {moved} moved")
    left = [n for n in subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True).stdout.splitlines()
            if not kept(n) and (root / n).is_file() and any(p.search((root / n).read_text(encoding="utf-8", errors="ignore")) for p, _ in table[:3])]
    print(f"Still naming {old} outside the fixtures:", *left or ["nothing"], sep="\n  ")


if __name__ == "__main__":
    main()
