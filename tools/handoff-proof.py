#!/usr/bin/env python3
"""Proves the handoff on a device, with the real engine: what a phone sends arrives on the screen.

  python3 tools/handoff-proof.py <serial>

Run it inside the lock of the device when the device is shared. It installs the debug build of
this worktree, opens "Send from a phone" with the keys of a remote, reads the address and the code
from the screen, checks that the QR code on the screen holds the same, makes a way from this
machine to the device, sends one link and one export file, and looks for both on the screen.
Nothing is added to the list of apps. The way is closed again and the device is left as it was
found.

The handoff listens on the device's own address on its network and not on its loopback address,
so `adb forward`, which ends at the loopback address, does not reach it. An emulator is reached
through a redirection of its own network (`adb emu redir`), which ends at the address the handoff
listens on. Any other device is reached at the address it shows, from the same network.

Needs adb, zbarimg, and a device that is on a network, which an emulator is.
"""
import base64
import hashlib
import hmac
import http.client
import os
import re
import socket
import struct
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADB = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "io.github.munzzyy.tern.debug"
ACTIVITY = PACKAGE + "/io.github.munzzyy.tern.MainActivity"
STAND_IN = "files/use-stand-in"
PREFS = "shared_prefs/ui.xml"
TABS = ["Apps", "Add", "Activity", "Settings"]
LINK = "https://codeberg.org/example/handoff-proof"
FILE_NAME = "handoff-proof-export.json"
FORGED = "https://codeberg.org/example/forged"
OTHER_CODE = "k4mzq7wdx2nph5tcr3vb"
EXPORT = (
    '{"format":"tern-export","schema":1,"exportedAt":0,"appVersion":"proof","apps":['
    '{"schema":1,"id":"proof","source":{"type":"forgejo","url":"https://codeberg.org/example/handoff-proof","options":{}},"name":"Handoff proof"}'
    "]}"
)

# The marks that keep an address left to right inside a sentence of any language, and the space of no width.
DIRECTION_MARKS = (0x2066, 0x2067, 0x2068, 0x2069, 0x200B)

results = []


# The wire: a thing is sealed with keys made from the code, as the page does it in the browser,
# and posted in the one field of a form. This is a third implementation of the seal besides the
# Kotlin one and the page's own script, so the device is held to the text of the design.

LINKS, FILE = 1, 2


def rotate(v, n):
    return ((v << n) & 0xFFFFFFFF) | (v >> (32 - n))


def chacha20(key, nonce, counter, data):
    """RFC 8439 section 2.4."""
    out = bytearray()
    constants = (0x61707865, 0x3320646E, 0x79622D32, 0x6B206574)
    k = struct.unpack("<8L", key)
    n = struct.unpack("<3L", nonce)
    for block in range(0, len(data), 64):
        state = [*constants, *k, (counter + block // 64) & 0xFFFFFFFF, *n]
        x = state[:]
        for _ in range(10):
            for a, b, c, d in ((0, 4, 8, 12), (1, 5, 9, 13), (2, 6, 10, 14), (3, 7, 11, 15),
                               (0, 5, 10, 15), (1, 6, 11, 12), (2, 7, 8, 13), (3, 4, 9, 14)):
                x[a] = (x[a] + x[b]) & 0xFFFFFFFF; x[d] = rotate(x[d] ^ x[a], 16)
                x[c] = (x[c] + x[d]) & 0xFFFFFFFF; x[b] = rotate(x[b] ^ x[c], 12)
                x[a] = (x[a] + x[b]) & 0xFFFFFFFF; x[d] = rotate(x[d] ^ x[a], 8)
                x[c] = (x[c] + x[d]) & 0xFFFFFFFF; x[b] = rotate(x[b] ^ x[c], 7)
        stream = struct.pack("<16L", *((x[i] + state[i]) & 0xFFFFFFFF for i in range(16)))
        out += bytes(p ^ q for p, q in zip(data[block:block + 64], stream))
    return bytes(out)


def seal(code, kind, plain, nonce=None):
    master = hmac.new(code.encode("ascii"), b"tern handoff v1", hashlib.sha256).digest()
    enc = hmac.new(master, b"enc", hashlib.sha256).digest()
    mac = hmac.new(master, b"mac", hashlib.sha256).digest()
    nonce = nonce or os.urandom(12)
    signed = bytes([kind]) + nonce + chacha20(enc, nonce, 1, plain)
    return signed + hmac.new(mac, signed, hashlib.sha256).digest()


def form(sealed):
    return b"sealed=" + base64.urlsafe_b64encode(sealed).rstrip(b"=")


def send(wire, sealed):
    return post(wire, "/send", "application/x-www-form-urlencoded", form(sealed))


def send_links(wire, links):
    return send(wire, seal(wire["code"], LINKS, "\n".join(links).encode()))


def send_file(wire, name, content):
    named = name.encode()
    return send(wire, seal(wire["code"], FILE, bytes([len(named)]) + named + content))


def wire_of(address, code, qr, reach):
    """Where the device listens, where this machine reaches it, and the code as the QR code holds it."""
    return {"device": address.removeprefix("http://"), "reach": reach, "code": qr.split("#", 1)[1] if "#" in qr else code.lower()}


def quote(text):
    return "".join(c if c.isalnum() or c in "-._~" else "".join("%%%02X" % b for b in c.encode()) for c in text)


def post(wire, path, kind, body, host=None):
    return request(wire, "POST", path, {"Content-Type": kind, "Content-Length": str(len(body))}, body, host)


def request(wire, method, path, headers, body=None, host=None):
    connection = http.client.HTTPConnection(wire["reach"][0], wire["reach"][1], timeout=20)
    try:
        connection.putrequest(method, path or "/", skip_host=True, skip_accept_encoding=True)
        connection.putheader("Host", host or wire["device"])
        for name, value in headers.items():
            connection.putheader(name, value)
        connection.endheaders(body)
        answer = connection.getresponse()
        answer.read()
        return answer.status
    finally:
        connection.close()


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def way_to(serial, address):
    """Where this machine reaches the device, and how to close that way again."""
    host, port = address.removeprefix("http://").rsplit(":", 1)
    if not serial.startswith("emulator-"):
        return (host, int(port)), lambda: None
    here = free_port()
    answer = adb(serial, "emu", "redir", "add", f"tcp:{here}:{port}")
    if "OK" not in answer:
        raise SystemExit("the emulator did not redirect the port: " + answer.strip())
    return ("127.0.0.1", here), lambda: adb(serial, "emu", "redir", "del", f"tcp:{here}")


def adb(serial, *args, text=True, check=False, stdin=None):
    done = subprocess.run([ADB, "-s", serial, *args], capture_output=True, input=stdin)
    if check and done.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {done.stderr.decode(errors='replace').strip()}")
    return done.stdout.decode(errors="replace") if text else done.stdout


def run_as(serial, *args, stdin=None):
    return subprocess.run([ADB, "-s", serial, "shell", "run-as", PACKAGE, *args], capture_output=True, input=stdin)


def nodes(serial):
    for _ in range(5):
        adb(serial, "shell", "uiautomator", "dump", "/sdcard/handoff-proof.xml")
        text = adb(serial, "shell", "cat", "/sdcard/handoff-proof.xml")
        if text.strip().startswith("<?xml"):
            return list(ET.fromstring(text).iter("node"))
        time.sleep(1)
    raise SystemExit("the screen could not be read")


def words(node):
    own = [node.get("text") or "", node.get("content-desc") or ""]
    inner = [value for child in node.iter("node") for value in (child.get("text") or "", child.get("content-desc") or "")]
    return [plain(value) for value in own + inner if value.strip()]


def plain(text):
    return "".join(c for c in text if ord(c) not in DIRECTION_MARKS)


def on_screen(serial):
    return [plain(value) for node in nodes(serial) for value in (node.get("text") or "", node.get("content-desc") or "") if value.strip()]


def focused(serial):
    for node in nodes(serial):
        if node.get("focused") == "true":
            return " / ".join(words(node))
    return ""


def key(serial, name):
    adb(serial, "shell", "input", "keyevent", name)
    time.sleep(0.6)


def move_to(serial, wanted, name, most=30):
    for _ in range(most):
        if wanted.lower() in focused(serial).lower():
            return True
        key(serial, name)
    return wanted.lower() in focused(serial).lower()


def open_tab(serial, wanted):
    wide = True
    for _ in range(8):
        if focused(serial) in TABS:
            break
        key(serial, "DPAD_LEFT")
    if focused(serial) not in TABS:
        wide = False
        for _ in range(40):
            if focused(serial) in TABS:
                break
            key(serial, "DPAD_DOWN")
    now = focused(serial)
    if now not in TABS:
        raise SystemExit(f"no tab took focus, focus is on: {now}")
    forward = TABS.index(wanted) > TABS.index(now)
    step = ("DPAD_DOWN" if forward else "DPAD_UP") if wide else ("DPAD_RIGHT" if forward else "DPAD_LEFT")
    if not move_to(serial, wanted, step, 6):
        raise SystemExit(f"the tab {wanted} was not reached, focus is on: {focused(serial)}")
    key(serial, "DPAD_CENTER")
    time.sleep(1.5)


def wait_for(serial, wanted, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        if any(wanted in value for value in on_screen(serial)):
            return True
        time.sleep(1)
    return False


def read_qr(serial):
    png = adb(serial, "exec-out", "screencap", "-p", text=False)
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as picture:
        picture.write(png)
    try:
        done = subprocess.run(["zbarimg", "--quiet", "--raw", picture.name], capture_output=True)
        return done.stdout.decode(errors="replace").strip()
    finally:
        os.unlink(picture.name)


def say(ok, what):
    results.append(ok)
    print(("ok   " if ok else "FAIL ") + what)


def prove(serial):
    adb(serial, "install", "-r", "-t", str(APK), check=True)
    had_stand_in = run_as(serial, "ls", STAND_IN).returncode == 0
    prefs = run_as(serial, "cat", PREFS)
    close = None
    try:
        run_as(serial, "rm", "-f", STAND_IN)
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        adb(serial, "shell", "am", "start", "-n", ACTIVITY, "--es", "scenario", "default")
        time.sleep(4)
        key(serial, "DPAD_DOWN")
        open_tab(serial, "Add")
        if not move_to(serial, "Send from a phone", "DPAD_DOWN", 6):
            raise SystemExit(f"the way to the handoff was not reached, focus is on: {focused(serial)}")
        key(serial, "DPAD_CENTER")
        if not wait_for(serial, "1. Put the phone on the same network"):
            raise SystemExit("the handoff did not open. The screen says: " + " | ".join(on_screen(serial)))
        time.sleep(1)

        qr = read_qr(serial)
        say(qr.startswith("http://"), f"the QR code on the screen can be read: {qr or 'nothing was read'}")

        move_to(serial, "Or open", "DPAD_DOWN", 6)
        shown = on_screen(serial)
        sentence = next((value for value in shown if "Or open" in value), "")
        found = re.search(r"http://[0-9.]+:[0-9]+", sentence)
        address = found.group(0) if found else ""
        spoken = next((value for value in shown if "one character at a time" in value), "")
        code = re.sub(r"[ ,]", "", spoken.split(":", 1)[1]) if ":" in spoken else ""
        say(bool(address), f"the screen shows the address: {address or 'none'}")
        say(bool(code), f"the screen shows the code: {code or 'none'}")
        say(bool(address) and qr.startswith(address + "/"), "the QR code holds the address the screen shows")
        say("#" in qr and qr.split("#", 1)[1] == code.lower(), "the QR code holds the code the screen shows, after the # that a browser keeps to itself")
        host = re.match(r"http://([0-9.]+):", address)
        private = bool(host) and re.match(r"(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)", host.group(1)) is not None
        say(private, "the address is a private one")

        reach, close = way_to(serial, address)
        wire = wire_of(address, code, qr, reach)

        say(request(wire, "GET", "/", {}, host=f"{reach[0]}:{reach[1] + 1}") == 404, "a request that names another host is answered with 404")
        say(request(wire, "GET", "/", {}) == 200, "the page answers under the device's own address")
        say(send(wire, seal(OTHER_CODE, LINKS, FORGED.encode())) == 403, "a thing sealed with another code is refused")
        changed = bytearray(seal(wire["code"], LINKS, FORGED.encode()))
        changed[20] ^= 1
        say(send(wire, bytes(changed)) == 403, "a thing changed by one bit on the way is refused")
        taken = seal(wire["code"], LINKS, LINK.encode())
        say(send(wire, taken) == 200, "the link was taken")
        say(send(wire, taken) == 403, "the same sealed link sent a second time is refused")
        say(send_file(wire, FILE_NAME, EXPORT.encode()) == 200, "the export file was taken")

        say(wait_for(serial, "Arrived from the phone"), "the screen says that something arrived")
        say(wait_for(serial, LINK), "the link is on the screen")
        say(wait_for(serial, FILE_NAME), "the export file is on the screen")
        say(not any("forged" in value for value in on_screen(serial)), "nothing that was refused is on the screen")
        say(sum(LINK in value for value in on_screen(serial)) == 1, "the link sent twice is on the screen once")

        key(serial, "BACK")
        time.sleep(1)
        open_tab(serial, "Apps")
        listed = on_screen(serial)
        say(not any("handoff-proof" in value.lower() or "handoff proof" in value.lower() for value in listed), "nothing that arrived was added to the list of apps")
    finally:
        if close:
            close()
        key(serial, "BACK")
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        if had_stand_in:
            run_as(serial, "touch", STAND_IN)
        if prefs.returncode == 0:
            run_as(serial, "sh", "-c", "cat > " + PREFS, stdin=prefs.stdout)
        else:
            run_as(serial, "rm", "-f", PREFS)
        adb(serial, "shell", "rm", "-f", "/sdcard/handoff-proof.xml")
    failed = results.count(False)
    print(f"{results.count(True)} passed, {failed} failed")
    return 1 if failed or not results else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: tools/handoff-proof.py <serial>")
    if not APK.exists():
        raise SystemExit("build first: ./gradlew :app:assembleDebug")
    sys.exit(prove(sys.argv[1]))
