#!/usr/bin/env python3
"""Fails when compiled code calls a Java API that the oldest supported Android does not have.

  check-java-api.py <api-versions.xml> <d8.jar> <minSdk> <classes dir> [<classes dir> ...]

core is plain Java code. It is compiled and tested against a desktop JDK, where every call
exists, and Android's lint does not read it. A method Android only gained later compiles, passes
every test, and ends in NoSuchMethodError on an older device. This reads the compiled classes,
collects every reference into java.*, javax.* and org.xml.*, and looks up the version each one
arrived in, in the api-versions.xml that comes with the newest platform of the SDK. The android.jar
of an old platform is no use for this: the one for Android 10 lists methods that Android 10 does
not have. What the build tools rewrite on their own (lambdas, string templates, the methods D8
backports) is allowed.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

JAVAP = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javap") if os.environ.get("JAVA_HOME") else "javap"
JAVA = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java") if os.environ.get("JAVA_HOME") else "java"
NAMESPACES = ("java/", "javax/", "jdk/", "sun/", "org/w3c/", "org/xml/")
# Rewritten by D8 wherever they occur, whatever the Android version.
DESUGARED_OWNERS = ("java/lang/invoke/",)
REFERENCE = re.compile(r"^\s*#\d+ = (Methodref|InterfaceMethodref|Fieldref|Class)\s+\S+\s+//\s+(.*)$")
BATCH = 200


def run(args):
    done = subprocess.run(args, capture_output=True, text=True)
    return done.stdout


def references(class_files):
    """Every (kind, owner, name, descriptor) the class files point at, with the file that does."""
    found = {}
    for start in range(0, len(class_files), BATCH):
        batch = class_files[start:start + BATCH]
        current = None
        for line in run([JAVAP, "-v", "-p", *batch]).splitlines():
            if line.startswith("Classfile "):
                current = line[len("Classfile "):].strip()
                continue
            match = REFERENCE.match(line)
            if not match:
                continue
            kind, text = match.group(1), match.group(2).strip().strip('"')
            if kind == "Class":
                owner = text
                if owner.startswith("["):
                    owner = owner.lstrip("[")
                    if not owner.startswith("L"):
                        continue
                    owner = owner[1:].rstrip(";")
                key = ("Class", owner, "", "")
            else:
                if "." not in text or ":" not in text:
                    continue
                owner, rest = text.split(".", 1)
                owner = owner.strip('"')
                if owner.startswith("["):
                    continue
                name, descriptor = rest.split(":", 1)
                key = (kind, owner, name.strip('"'), descriptor)
            if key[1].startswith(NAMESPACES):
                found.setdefault(key, [])
                if current not in found[key]:
                    found[key].append(current)
    return found


class Platform:
    """Which Android version each class and member arrived in, from the SDK's own api-versions.xml."""

    def __init__(self, api_versions, min_sdk):
        self.min_sdk = min_sdk
        self.classes = {}
        import xml.etree.ElementTree as ET
        for _, node in ET.iterparse(api_versions, events=("end",)):
            if node.tag != "class":
                continue
            since = self.level(node.get("since"), 1)
            removed = node.get("removed")
            members = {}
            supers = []
            for child in node:
                if child.tag in ("extends", "implements"):
                    if self.level(child.get("since"), since) <= min_sdk:
                        supers.append(child.get("name"))
                elif child.tag in ("method", "field"):
                    members[child.get("name")] = self.level(child.get("since"), since)
            self.classes[node.get("name")] = {"since": since, "removed": removed, "members": members, "supers": supers}
            node.clear()

    @staticmethod
    def level(text, default):
        """"36" and "36.1" both occur; the part before the dot is the version that matters here."""
        if not text:
            return default
        try:
            return int(str(text).split(".")[0])
        except ValueError:
            return default

    def has_class(self, owner):
        entry = self.classes.get(owner)
        return entry is not None and entry["since"] <= self.min_sdk

    def since(self, owner, member, seen=None):
        """The version [member] can first be called on [owner], or None when it never can."""
        seen = seen if seen is not None else set()
        if owner in seen:
            return None
        seen.add(owner)
        entry = self.classes.get(owner)
        if entry is None:
            return None
        if member in entry["members"]:
            return max(entry["members"][member], entry["since"])
        if member.startswith("<init>"):
            return None
        best = None
        for parent in entry["supers"]:
            level = self.since(parent, member, seen)
            if level is not None and (best is None or level < best):
                best = level
        return None if best is None else max(best, entry["since"])


def backported(d8, min_sdk):
    out = run([JAVA, "-cp", d8, "com.android.tools.r8.BackportedMethodList", "--min-api", str(min_sdk)])
    allowed = set()
    for line in out.splitlines():
        line = line.strip()
        if "#" in line:
            owner, member = line.split("#", 1)
            allowed.add((owner, member))
    return allowed


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        return 2
    api_versions, d8, min_sdk, roots = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4:]
    class_files = sorted(str(p) for root in roots for p in Path(root).rglob("*.class"))
    if not class_files:
        print("FAIL no class files under", ", ".join(roots), "(build first)")
        return 1
    found = references(class_files)
    platform = Platform(api_versions, min_sdk)
    if "java/lang/String" not in platform.classes:
        print("FAIL", api_versions, "does not look like an api-versions.xml")
        return 1
    allowed = backported(d8, min_sdk)
    if not allowed:
        print("FAIL could not read the list of backported methods from", d8)
        return 1

    missing = []
    for (kind, owner, name, descriptor), where in sorted(found.items()):
        if owner.startswith(DESUGARED_OWNERS):
            continue
        short = ", ".join(sorted(os.path.basename(w) for w in where if w)) or "?"
        known = platform.classes.get(owner)
        if known is None or known["since"] > min_sdk:
            if kind == "Class":
                arrived = f"arrived in Android API {known['since']}" if known else "is not part of Android"
                missing.append(f"{owner} {arrived}, used in {short}")
            continue
        if kind == "Class":
            continue
        member = name if kind == "Fieldref" else name + descriptor
        if (owner, member) in allowed or (owner, name) in allowed:
            continue
        level = platform.since(owner, member)
        if level is None:
            missing.append(f"{owner}.{member} is not part of Android, used in {short}")
        elif level > min_sdk:
            missing.append(f"{owner}.{member} arrived in Android API {level}, used in {short}")
    if missing:
        print(f"FAIL {len(missing)} Java API references that Android API {min_sdk} does not have:")
        for line in missing:
            print("  " + line)
        return 1
    print(f"ok   {len(found)} Java API references in {len(class_files)} classes all exist on Android API {min_sdk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
