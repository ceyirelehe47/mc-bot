#!/usr/bin/env python3
"""Probe Real Client control-token acceptance without printing the token."""
from __future__ import annotations

import argparse
import hashlib
import json
import socket
import struct
import uuid
from pathlib import Path

PROTOCOL = 5
MAX_FRAME = 64 * 1024


def write_frame(sock: socket.socket, message: dict) -> None:
    body = json.dumps(
        message, separators=(",", ":")
    ).encode("utf-8")
    sock.sendall(struct.pack(">I", len(body)) + body)


def read_exact(sock: socket.socket, length: int) -> bytes:
    chunks: list[bytes] = []
    while length:
        chunk = sock.recv(length)
        if not chunk:
            raise EOFError
        chunks.append(chunk)
        length -= len(chunk)
    return b"".join(chunks)


def read_frame(sock: socket.socket) -> dict:
    length = struct.unpack(">I", read_exact(sock, 4))[0]
    if length < 2 or length > MAX_FRAME:
        raise ValueError("invalid_frame_length")
    return json.loads(read_exact(sock, length).decode("utf-8"))


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--token-file", required=True, type=Path)
    parser.add_argument("--expect", choices=("accepted", "rejected"), required=True)
    parser.add_argument("--body-id", default="bob")
    parser.add_argument("--player-name", default="Bob")
    parser.add_argument("--timeout", type=float, default=3.0)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    token = args.token_file.read_text(encoding="utf-8").strip()
    result = {
        "token_sha256_fingerprint": fingerprint(token),
        "expected": args.expect,
        "accepted": False,
        "reason": "",
        "pass": False,
    }

    session = str(uuid.uuid4())
    try:
        with socket.create_connection(
            (args.host, args.port), timeout=args.timeout
        ) as sock:
            sock.settimeout(args.timeout)
            write_frame(sock, {
                "type": "hello",
                "protocol": PROTOCOL,
                "token": token,
                "body_id": args.body_id,
                "player_name": args.player_name,
                "session_epoch": session,
                "window_mode": "background",
            })
            response = read_frame(sock)
            result["accepted"] = (
                response.get("type") == "welcome"
                and response.get("protocol") == PROTOCOL
                and response.get("session_epoch") == session
            )
            result["reason"] = (
                "welcome" if result["accepted"] else "unexpected_response"
            )
    except (OSError, EOFError, ValueError, json.JSONDecodeError) as failure:
        result["reason"] = type(failure).__name__

    result["pass"] = (
        result["accepted"] if args.expect == "accepted"
        else not result["accepted"]
    )
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
