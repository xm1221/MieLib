#!/usr/bin/env python3
"""Minimal Minecraft RCON client (stdlib only).

Password is read from the RCON_PASSWORD environment variable so it is not
placed on the command line. Exit 2 means the port is not accepting connections
yet; any other failure exits 1.
"""

from __future__ import annotations

import argparse
import os
import socket
import struct
import sys


def _recvall(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("rcon connection closed")
        buf += chunk
    return buf


def _send(sock: socket.socket, req_id: int, kind: int, payload: str) -> None:
    body = struct.pack("<ii", req_id, kind) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _recv(sock: socket.socket) -> tuple[int, int, str]:
    (length,) = struct.unpack("<i", _recvall(sock, 4))
    if length < 10 or length > 10_000_000:
        raise RuntimeError(f"rcon packet length {length}")
    data = _recvall(sock, length)
    req_id, kind = struct.unpack("<ii", data[:8])
    payload = data[8:-2].decode("utf-8", "replace")
    return req_id, kind, payload


def main() -> int:
    parser = argparse.ArgumentParser(description="Send one RCON command")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--command", required=True)
    parser.add_argument("--timeout", type=int, default=1200)
    args = parser.parse_args()

    password = os.environ.get("RCON_PASSWORD", "")
    if not password:
        print("RCON_PASSWORD is not set", file=sys.stderr)
        return 1

    try:
        sock = socket.create_connection((args.host, args.port), timeout=10)
    except (ConnectionRefusedError, TimeoutError, OSError) as exc:
        print(f"rcon connect failed: {exc}", file=sys.stderr)
        return 2

    sock.settimeout(args.timeout)
    try:
        _send(sock, 1, 3, password)  # 3 = SERVERDATA_AUTH
        req_id, _kind, _payload = _recv(sock)
        if req_id == -1:
            print("rcon auth failed", file=sys.stderr)
            return 1
        # Some servers send an extra empty auth response.
        sock.settimeout(1)
        try:
            extra_id, _kind, _payload = _recv(sock)
            if extra_id == -1:
                print("rcon auth failed", file=sys.stderr)
                return 1
        except (TimeoutError, socket.timeout):
            pass
        sock.settimeout(args.timeout)

        _send(sock, 2, 2, args.command)  # 2 = SERVERDATA_EXECCOMMAND
        # Sentinel is processed after the real command, so a long /genHexBook
        # still finishes before we stop reading.
        _send(sock, 3, 2, "list")
        parts: list[str] = []
        while True:
            req_id, _kind, payload = _recv(sock)
            if req_id == 3:
                break
            if req_id == 2 and payload:
                parts.append(payload)
    except (TimeoutError, socket.timeout):
        print("rcon timed out waiting for the command", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"rcon error: {exc}", file=sys.stderr)
        return 1
    finally:
        sock.close()

    text = "".join(parts).strip()
    if text:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
