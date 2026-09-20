#!/usr/bin/env python3
"""Credential-redacting loopback proxy with non-blocking held-frame fault injection.

Holding one server->client command does not stop the reader thread. Later control frames can
overtake the held command, which is required to prove that a target-change cancel reaches the
client before an old commit is released.
"""
from __future__ import annotations

import argparse
import copy
import json
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

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


def encode_frame(message: dict) -> bytes:
    return json.dumps(
        message, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")


def write_frame(sock: socket.socket, body: bytes) -> None:
    sock.sendall(struct.pack(">I", len(body)) + body)


def message_phase(message: dict) -> str:
    try:
        return json.loads(message.get("arguments_json", "{}")).get("phase", "")
    except (TypeError, json.JSONDecodeError):
        return ""


def execution_id(message: dict) -> str:
    value = message.get("execution_id", "")
    return value if isinstance(value, str) else ""


class TraceRecorder:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._event_seq = 0
        self._frame_seq = 0

    def new_frame_id(self, prefix: str) -> str:
        with self._lock:
            self._frame_seq += 1
            return f"{prefix}-{self._frame_seq:06d}"

    def record(
        self,
        direction: str,
        message: dict,
        action: str,
        *,
        frame_id: str = "",
        note: str = "",
    ) -> dict:
        sanitized = redact_json(copy.deepcopy(message))
        with self._lock:
            self._event_seq += 1
            row = {
                "event_seq": self._event_seq,
                "epoch_ms": int(time.time() * 1000),
                "monotonic_ns": time.monotonic_ns(),
                "direction": direction,
                "action": action,
                "frame_id": frame_id,
                "execution_id": execution_id(message),
                "message_type": message.get("type", ""),
                "operation": message.get("operation", ""),
                "phase": message_phase(message),
                "note": note,
                "message": sanitized,
            }
            with self.path.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(
                    row, ensure_ascii=False, separators=(",", ":")
                ) + "\n")
            return row


class SafeFrameWriter:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self._lock = threading.Lock()

    def write(self, body: bytes) -> None:
        with self._lock:
            write_frame(self.sock, body)


@dataclass(frozen=True)
class HeldFrame:
    frame_id: str
    direction: str
    message: dict
    bodies: tuple[bytes, ...]


class HeldFrameGate:
    """Stores matching frames while allowing subsequent frames to keep flowing."""

    def __init__(
        self,
        *,
        release_file: Path,
        recorder: TraceRecorder,
        writer: Callable[[bytes], None],
        stop: threading.Event,
        hold_operation: str,
        hold_phase: str,
        hold_execution_id: str,
        hold_limit: int,
        duplicate_count: int,
        command_seq_delta: int,
        drop_inner_field: str,
    ):
        if hold_limit < 1 or hold_limit > 16:
            raise ValueError("hold_limit_must_be_1_to_16")
        if duplicate_count < 1 or duplicate_count > 8:
            raise ValueError("duplicate_count_must_be_1_to_8")
        self.release_file = release_file
        self.recorder = recorder
        self.writer = writer
        self.stop = stop
        self.hold_operation = hold_operation
        self.hold_phase = hold_phase
        self.hold_execution_id = hold_execution_id
        self.hold_limit = hold_limit
        self.duplicate_count = duplicate_count
        self.command_seq_delta = command_seq_delta
        self.drop_inner_field = drop_inner_field
        self._lock = threading.Lock()
        self._held: list[HeldFrame] = []
        self._held_count = 0

    def matches(self, direction: str, message: dict) -> bool:
        if direction != "server_to_client":
            return False
        if message.get("type") != "command":
            return False
        if message.get("operation") != self.hold_operation:
            return False
        if message_phase(message) != self.hold_phase:
            return False
        if self.hold_execution_id and execution_id(message) != self.hold_execution_id:
            return False
        with self._lock:
            return self._held_count < self.hold_limit

    def _mutated_bodies(self, message: dict) -> tuple[bytes, ...]:
        outgoing = copy.deepcopy(message)
        if self.command_seq_delta:
            outgoing["command_seq"] = int(
                outgoing.get("command_seq", 0)
            ) + self.command_seq_delta
        if self.drop_inner_field:
            try:
                inner = json.loads(outgoing.get("arguments_json", "{}"))
            except (TypeError, json.JSONDecodeError):
                inner = None
            if isinstance(inner, dict):
                inner.pop(self.drop_inner_field, None)
                outgoing["arguments_json"] = json.dumps(
                    inner, separators=(",", ":")
                )
        encoded = encode_frame(outgoing)
        return tuple(encoded for _ in range(self.duplicate_count))

    def hold(self, direction: str, message: dict) -> bool:
        if not self.matches(direction, message):
            return False
        if self.release_file.exists():
            return False
        frame_id = self.recorder.new_frame_id("held")
        held = HeldFrame(
            frame_id=frame_id,
            direction=direction,
            message=copy.deepcopy(message),
            bodies=self._mutated_bodies(message),
        )
        with self._lock:
            if self._held_count >= self.hold_limit:
                return False
            self._held_count += 1
            self._held.append(held)
        self.recorder.record(
            direction, message, "held", frame_id=frame_id,
            note="reader_continues; later control frames may overtake",
        )
        return True

    def release_ready(self) -> int:
        if not self.release_file.exists():
            return 0
        with self._lock:
            frames = list(self._held)
            self._held.clear()
        released = 0
        for held in frames:
            for index, body in enumerate(held.bodies, 1):
                self.writer(body)
                action = "released" if len(held.bodies) == 1 else (
                    f"released_{index}_of_{len(held.bodies)}"
                )
                self.recorder.record(
                    held.direction,
                    held.message,
                    action,
                    frame_id=held.frame_id,
                )
                released += 1
        return released

    def drop_remaining(self) -> int:
        with self._lock:
            frames = list(self._held)
            self._held.clear()
        for held in frames:
            self.recorder.record(
                held.direction,
                held.message,
                "dropped_on_shutdown",
                frame_id=held.frame_id,
            )
        return len(frames)

    def release_loop(self) -> None:
        while not self.stop.is_set():
            self.release_ready()
            time.sleep(0.01)
        self.release_ready()
        self.drop_remaining()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", required=True, type=int)
    parser.add_argument("--backend-port", required=True, type=int)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--release-file", required=True, type=Path)
    parser.add_argument("--hold-operation", default="deposit")
    parser.add_argument("--hold-phase", default="commit")
    parser.add_argument("--hold-execution-id", default="")
    parser.add_argument("--hold-limit", type=int, default=1)
    parser.add_argument("--duplicate-count", type=int, default=1)
    parser.add_argument("--command-seq-delta", type=int, default=0)
    parser.add_argument("--drop-inner-field", default="")
    args = parser.parse_args()

    recorder = TraceRecorder(args.log)
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
    client_writer = SafeFrameWriter(client)
    backend_writer = SafeFrameWriter(backend)
    gate = HeldFrameGate(
        release_file=args.release_file,
        recorder=recorder,
        writer=client_writer.write,
        stop=stop,
        hold_operation=args.hold_operation,
        hold_phase=args.hold_phase,
        hold_execution_id=args.hold_execution_id,
        hold_limit=args.hold_limit,
        duplicate_count=args.duplicate_count,
        command_seq_delta=args.command_seq_delta,
        drop_inner_field=args.drop_inner_field,
    )

    release_thread = threading.Thread(
        target=gate.release_loop,
        name="aibot-held-frame-release",
        daemon=True,
    )
    release_thread.start()

    def pipe(
        source: socket.socket,
        target_writer: SafeFrameWriter,
        direction: str,
    ) -> None:
        try:
            while not stop.is_set():
                body = read_frame(source)
                message = json.loads(body.decode("utf-8"))
                if gate.hold(direction, message):
                    continue
                frame_id = recorder.new_frame_id("forward")
                recorder.record(
                    direction, message, "forwarded", frame_id=frame_id
                )
                target_writer.write(body)
        except (
            EOFError, OSError, ValueError, json.JSONDecodeError
        ) as failure:
            recorder.record(
                direction,
                {"error": type(failure).__name__},
                "closed",
                note=str(failure)[:160],
            )
        finally:
            stop.set()
            for sock in (source, client, backend):
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    threads = [
        threading.Thread(
            target=pipe,
            args=(client, backend_writer, "client_to_server"),
            daemon=True,
        ),
        threading.Thread(
            target=pipe,
            args=(backend, client_writer, "server_to_client"),
            daemon=True,
        ),
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    stop.set()
    release_thread.join(timeout=2)
    client.close()
    backend.close()
    listener.close()


if __name__ == "__main__":
    main()
