# -*- coding: utf-8 -*-
"""R1.2A exact-original-Graph LIVE helpers."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path
from typing import Any

ROOT = Path(os.environ.get("R12A_ROOT", r"D:\code\mc-experiment"))
SERVER = Path(os.environ.get("R12A_SERVER", str(ROOT / "mc-server-mc1ca")))
WORLD_NAME = os.environ.get("R12A_WORLD", "world_play")
JAVA = Path(os.environ.get(
    "R12A_JAVA",
    r"D:\mc-server\jdk-21.0.12.1+1\bin\java",
))
RCON = Path(os.environ.get("R12A_RCON", str(ROOT / "rcon.py")))
GRAPH_LIVE = Path(os.environ.get("R12A_GRAPH_LIVE", str(ROOT / "graph_live.py")))
OUT = Path(os.environ.get(
    "R12A_OUT",
    str(ROOT / "r12a-acceptance"),
))

WORLD_ROOT = SERVER / WORLD_NAME
AIBOT_ROOT = WORLD_ROOT / "aibot"
SEM = AIBOT_ROOT / "external-semantics-bob.json"
JOURNAL = AIBOT_ROOT / "external-body-bob.journal"
GRAPH_STORE = AIBOT_ROOT / "task-graphs-bob.bin"
WORLD_ID_FILE = AIBOT_ROOT / "world-id"
STATE = OUT / "state.json"

READY_MARK = "external-body bridge bound to loopback port 8765"


def ensure_dirs() -> None:
    OUT.mkdir(parents=True, exist_ok=True)


def load_graph_live():
    spec = importlib.util.spec_from_file_location("graph_live", GRAPH_LIVE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load graph helper: {GRAPH_LIVE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def rcon(command: str) -> str:
    completed = subprocess.run(
        [sys.executable, str(RCON), command],
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"rcon failed rc={completed.returncode}: {command}\n"
            f"stdout={completed.stdout}\nstderr={completed.stderr}"
        )
    return completed.stdout.strip()


def start_server(log_path: Path) -> subprocess.Popen[bytes]:
    ensure_dirs()
    token = (SERVER / "bridge-token.txt").read_text(encoding="utf-8").strip()
    env = dict(
        os.environ,
        AIBOT_EXTERNAL_BOT="Bob",
        AIBOT_BRIDGE_TOKEN=token,
        AIBOT_BRIDGE_PORT="8765",
    )
    log_handle = open(log_path, "wb")
    process = subprocess.Popen(
        [str(JAVA), "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
        cwd=SERVER,
        env=env,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
    )
    deadline = time.time() + 180
    while time.time() < deadline:
        if process.poll() is not None:
            log_handle.close()
            raise RuntimeError(
                f"server exited early rc={process.returncode}; see {log_path}"
            )
        try:
            text = log_path.read_bytes().decode("utf-8", "replace")
            if READY_MARK in text:
                log_handle.close()
                return process
        except OSError:
            pass
        time.sleep(2)
    process.kill()
    process.wait()
    log_handle.close()
    raise RuntimeError("server not ready in 180 seconds")


def stop_server(process: subprocess.Popen[bytes], *, graceful: bool = True) -> None:
    if process.poll() is not None:
        return
    if graceful:
        try:
            rcon("stop")
        except Exception:
            pass
    deadline = time.time() + 60
    while time.time() < deadline and process.poll() is None:
        time.sleep(1)
    if process.poll() is None:
        process.kill()
        process.wait()
    time.sleep(3)


def wait_quiet(path: Path, *, tries: int = 12, delay: float = 1.5) -> None:
    last = None
    stable = 0
    for _ in range(tries):
        stat = path.stat()
        signature = (stat.st_mtime_ns, stat.st_size)
        if signature == last:
            stable += 1
            if stable >= 2:
                return
        else:
            stable = 0
        last = signature
        time.sleep(delay)
    raise RuntimeError(f"file did not become quiet: {path}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def save_state(**updates: Any) -> dict[str, Any]:
    ensure_dirs()
    current: dict[str, Any] = {}
    if STATE.exists():
        current = read_json(STATE)
    current.update(updates)
    write_json(STATE, current)
    return current


def load_state() -> dict[str, Any]:
    return read_json(STATE)


def opportunities_at(snapshot: dict[str, Any], cell: tuple[int, int, int]) -> list[dict[str, Any]]:
    x, y, z = cell
    return [
        item
        for item in snapshot.get("resource_opportunities", [])
        if item.get("x") == x and item.get("y") == y and item.get("z") == z
    ]


def graph_subject_ids(graph: dict[str, Any]) -> list[str]:
    result = []
    for node in graph.get("nodes", []):
        ref = node.get("subject_ref") or {}
        result.append(str(ref.get("object_id", "")))
    return result


def _read_utf8(body: bytes, offset: int) -> tuple[str, int]:
    if offset + 4 > len(body):
        raise ValueError("truncated string length")
    length = struct.unpack_from(">i", body, offset)[0]
    offset += 4
    if length < 0 or offset + length > len(body):
        raise ValueError("invalid string length")
    return body[offset:offset + length].decode("utf-8"), offset + length


def read_journal(path: Path) -> list[dict[str, Any]]:
    data = path.read_bytes()
    if data[:8] != b"AIBODY01" or len(data) < 44:
        raise ValueError("invalid journal header")
    offset = 44
    frames: list[dict[str, Any]] = []
    while offset < len(data):
        if offset + 4 > len(data):
            raise ValueError("truncated frame length")
        size = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        if size < 16 or offset + size + 4 > len(data):
            raise ValueError("invalid/truncated frame")
        body = data[offset:offset + size]
        offset += size
        expected_crc = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        if (zlib.crc32(body) & 0xFFFFFFFF) != expected_crc:
            raise ValueError("journal crc mismatch")
        sequence, observed_at, count = struct.unpack_from(">qqi", body, 0)
        cursor = 20
        fields: dict[str, str] = {}
        for _ in range(count):
            key, cursor = _read_utf8(body, cursor)
            value, cursor = _read_utf8(body, cursor)
            if key in fields:
                raise ValueError("duplicate journal key")
            fields[key] = value
        if cursor != len(body):
            raise ValueError("journal trailing bytes")
        frames.append({
            "sequence": sequence,
            "observed_at_ms": observed_at,
            "fields": fields,
        })
    return frames


def lifecycle_receipts(opportunity_id: str) -> list[dict[str, Any]]:
    return [
        frame
        for frame in read_journal(JOURNAL)
        if frame["fields"].get("opportunity_id") == opportunity_id
        and frame["fields"].get("kind") in {
            "resource_opportunity_birth",
            "resource_opportunity_consumed",
            "resource_opportunity_stale",
        }
    ]


def append_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
        if not text.endswith("\n"):
            handle.write("\n")
