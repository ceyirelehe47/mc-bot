#!/usr/bin/env python3
"""Length-prefixed JSON proxy that never writes credentials to its evidence log."""
from __future__ import annotations

import argparse
import copy
import json
import socket
import struct
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from evidence_hygiene import redact_json

MAX_FRAME = 64 * 1024


def recv_exact(sock: socket.socket, length: int) -> bytes:
    chunks: list[bytes] = []
    remaining = length
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_frame(sock: socket.socket) -> bytes:
    header = recv_exact(sock, 4)
    length = struct.unpack(">I", header)[0]
    if length < 2 or length > MAX_FRAME:
        raise ValueError(f"invalid_frame_length:{length}")
    return recv_exact(sock, length)


def write_frame(sock: socket.socket, body: bytes) -> None:
    sock.sendall(struct.pack(">I", len(body)) + body)


def message_phase(message: dict) -> str:
    try:
        return json.loads(message.get("arguments_json", "{}")).get("phase", "")
    except (TypeError, json.JSONDecodeError):
        return ""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", required=True, type=int)
    parser.add_argument("--backend-port", required=True, type=int)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--release-file", required=True, type=Path)
    parser.add_argument("--hold-operation", default="deposit")
    parser.add_argument("--hold-phase", default="commit")
    parser.add_argument("--duplicate-count", type=int, default=1)
    parser.add_argument("--command-seq-delta", type=int, default=0)
    parser.add_argument("--drop-inner-field", default="")
    args = parser.parse_args()

    if args.duplicate_count < 1 or args.duplicate_count > 8:
        raise SystemExit("duplicate-count must be 1..8")
    args.log.parent.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()

    def record(direction: str, message: dict, action: str) -> None:
        sanitized = redact_json(copy.deepcopy(message))
        row = {
            "epoch_ms": int(time.time() * 1000),
            "direction": direction,
            "action": action,
            "message": sanitized,
        }
        with lock, args.log.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(
                row, ensure_ascii=False, separators=(",", ":")
            ) + "\n")

    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", args.listen_port))
    listener.listen(1)
    client, _ = listener.accept()
    backend = socket.create_connection(
        ("127.0.0.1", args.backend_port), timeout=10
    )
    client.settimeout(None)
    backend.settimeout(None)
    stop = threading.Event()

    def pipe(
        source: socket.socket,
        target: socket.socket,
        direction: str,
    ) -> None:
        try:
            while not stop.is_set():
                body = read_frame(source)
                message = json.loads(body.decode("utf-8"))
                should_hold = (
                    direction == "server_to_client"
                    and message.get("type") == "command"
                    and message.get("operation") == args.hold_operation
                    and message_phase(message) == args.hold_phase
                )
                if should_hold and not args.release_file.exists():
                    record(direction, message, "held")
                    while (
                        not stop.is_set()
                        and not args.release_file.exists()
                    ):
                        time.sleep(0.02)
                    if stop.is_set():
                        return
                    record(direction, message, "released")
                else:
                    record(direction, message, "forwarded")

                outgoing = copy.deepcopy(message)
                if should_hold:
                    if args.command_seq_delta:
                        outgoing["command_seq"] = int(
                            outgoing.get("command_seq", 0)
                        ) + args.command_seq_delta
                    if args.drop_inner_field:
                        try:
                            inner = json.loads(
                                outgoing.get("arguments_json", "{}")
                            )
                            inner.pop(args.drop_inner_field, None)
                            outgoing["arguments_json"] = json.dumps(
                                inner, separators=(",", ":")
                            )
                        except (TypeError, json.JSONDecodeError):
                            pass

                encoded = json.dumps(
                    outgoing, separators=(",", ":"), ensure_ascii=False
                ).encode("utf-8")
                copies = args.duplicate_count if should_hold else 1
                for index in range(copies):
                    if copies > 1:
                        record(
                            direction,
                            outgoing,
                            f"replayed_{index + 1}_of_{copies}",
                        )
                    write_frame(target, encoded)
        except (
            EOFError, OSError, ValueError, json.JSONDecodeError
        ) as failure:
            record(
                direction,
                {"error": type(failure).__name__},
                "closed",
            )
        finally:
            stop.set()
            for sock in (source, target):
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    threads = [
        threading.Thread(
            target=pipe,
            args=(client, backend, "client_to_server"),
            daemon=True,
        ),
        threading.Thread(
            target=pipe,
            args=(backend, client, "server_to_client"),
            daemon=True,
        ),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    client.close()
    backend.close()
    listener.close()


if __name__ == "__main__":
    main()
