# -*- coding: utf-8 -*-
"""MC-2A0.3 LIVE 驱动公共模块: 带 AIBOT_EXTERNAL_BODY_ID 的隔离服启停/RCON/journal 解析。"""
from __future__ import annotations

import importlib.util
import json
import os
import struct
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

ROOT = Path(r"D:\code\mc-experiment")
SERVER = Path(os.environ.get("R203_SERVER", str(ROOT / "mc-server-mc1ca-b")))
WORLD_NAME = os.environ.get("R203_WORLD", "world_r2")
JAVA = Path(os.environ.get("R203_JAVA", r"D:\mc-server\jdk-21.0.12.1+1\bin\java.exe"))
RCON = Path(os.environ.get("R203_RCON", str(ROOT / "rcon.py")))
RCON_PORT = int(os.environ.get("R203_RCON_PORT", "25576"))
BRIDGE_PORT = os.environ.get("R203_BRIDGE_PORT", "8766")

WORLD_ROOT = SERVER / WORLD_NAME
AIBOT_ROOT = WORLD_ROOT / "aibot"
JOURNAL = AIBOT_ROOT / "external-body-bob.journal"

READY_MARK = f"external-body bridge bound to loopback port {BRIDGE_PORT}"

OUT = Path(r"D:\code\mc-bot\aibot-mc2a0-3-body-backend-seam-delivery")


def load_http():
    spec = importlib.util.spec_from_file_location("mc2a0_live", str(ROOT / "mc2a0_live.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.BASE = f"http://127.0.0.1:{BRIDGE_PORT}"
    module.TOKEN = (SERVER / "bridge-token.txt").read_text(encoding="utf-8").strip()
    module.LEASE_FILE = str(ROOT / f".mc2a03-lease-{BRIDGE_PORT}.json")
    return module


def rcon(command: str) -> str:
    spec = importlib.util.spec_from_file_location("rcon_tool", str(RCON))
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    return tool.rcon(command, port=RCON_PORT).strip().strip("\x00").strip()


def start_server(log_path: Path) -> subprocess.Popen[bytes]:
    token = (SERVER / "bridge-token.txt").read_text(encoding="utf-8").strip()
    env = dict(
        os.environ,
        AIBOT_EXTERNAL_BOT="Bob",
        AIBOT_EXTERNAL_BODY_ID="bob",
        AIBOT_BRIDGE_TOKEN=token,
        AIBOT_BRIDGE_PORT=BRIDGE_PORT,
    )
    log_handle = open(log_path, "wb")
    process = subprocess.Popen(
        [str(JAVA), "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
        cwd=SERVER, env=env, stdout=log_handle, stderr=subprocess.STDOUT,
    )
    deadline = time.time() + 180
    while time.time() < deadline:
        if process.poll() is not None:
            log_handle.close()
            raise RuntimeError(f"server exited early rc={process.returncode}; see {log_path}")
        try:
            if READY_MARK in log_path.read_bytes().decode("utf-8", "replace"):
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


def _read_utf8(body: bytes, offset: int) -> tuple[str, int]:
    length = struct.unpack_from(">I", body, offset)[0]
    offset += 4
    value = body[offset:offset + length].decode("utf-8")
    return value, offset + length


def read_journal(path: Path) -> list[dict[str, Any]]:
    data = path.read_bytes()
    if data[:8] != b"AIBODY01":
        raise ValueError("bad journal header")
    offset = 44
    frames: list[dict[str, Any]] = []
    while offset + 8 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        body = data[offset:offset + length]
        offset += length + 4  # skip crc32
        seq, _ts, count = struct.unpack_from(">qqI", body, 0)
        cursor, fields = 20, {"_seq": seq}
        for _ in range(count):
            key, cursor = _read_utf8(body, cursor)
            value, cursor = _read_utf8(body, cursor)
            fields[key] = value
        frames.append(fields)
    return frames


def sha256(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=1, sort_keys=True), encoding="utf-8")


def append_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(text)
