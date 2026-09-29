#!/usr/bin/env python3
"""A SOCKS5 endpoint that only records what it is asked to connect to, then refuses.

Run on the development machine; the emulator reaches it as 10.0.2.2. Each request prints one line:
address type (1 = IPv4, 3 = host name, 4 = IPv6), the address as sent, and the port.
"""
import socket
import struct
import sys
import threading

ATYP_NAMES = {1: "IPv4", 3: "host name", 4: "IPv6"}


def read_exact(conn, n):
    data = b""
    while len(data) < n:
        chunk = conn.recv(n - len(data))
        if not chunk:
            raise ConnectionError("closed early")
        data += chunk
    return data


def handle(conn, peer):
    with conn:
        try:
            version, count = read_exact(conn, 2)
            if version != 5:
                print(f"{peer}: SOCKS version {version}, not 5", flush=True)
                return
            read_exact(conn, count)
            conn.sendall(b"\x05\x00")
            version, command, _, atyp = read_exact(conn, 4)
            if atyp == 1:
                address = socket.inet_ntoa(read_exact(conn, 4))
            elif atyp == 3:
                length = read_exact(conn, 1)[0]
                address = read_exact(conn, length).decode("ascii", "replace")
            elif atyp == 4:
                address = socket.inet_ntop(socket.AF_INET6, read_exact(conn, 16))
            else:
                address = "?"
            port = struct.unpack(">H", read_exact(conn, 2))[0]
            print(f"atyp={atyp} ({ATYP_NAMES.get(atyp, 'unknown')}) address={address} port={port} command={command}", flush=True)
            conn.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
        except (ConnectionError, OSError, ValueError) as e:
            print(f"{peer}: {e}", flush=True)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 19050
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", port))
    server.listen(16)
    print(f"listening on 127.0.0.1:{port}", flush=True)
    while True:
        conn, peer = server.accept()
        threading.Thread(target=handle, args=(conn, peer), daemon=True).start()


if __name__ == "__main__":
    main()
