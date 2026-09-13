#!/usr/bin/env python3
"""Launch and supervise Bob's independent offline Fabric client without using a Microsoft account."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Any


def offline_uuid(username: str) -> uuid.UUID:
    """Java UUID.nameUUIDFromBytes("OfflinePlayer:" + name), including v3/variant bits."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + username).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return uuid.UUID(bytes=bytes(digest))


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def load_config(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("config_root_must_be_object")
    username = value.get("username", "Bob")
    if not isinstance(username, str) or not username or len(username) > 16:
        raise ValueError("invalid_username")
    command = value.get("command")
    if not isinstance(command, list) or not command or not all(isinstance(x, str) and x for x in command):
        raise ValueError("command_must_be_nonempty_string_array")
    joined = " ".join(command)
    if "{username}" not in joined or "{offline_uuid}" not in joined:
        raise ValueError("offline_identity_placeholders_required")
    lowered = joined.lower()
    if any(marker in lowered for marker in (
            "{microsoft", "--usertype msa", "--xuid", "--clientid", "--userproperties")):
        raise ValueError("microsoft_credentials_not_supported")
    access = re.search(r"--accesstoken(?:=|\s+)([^\s]+)", joined, re.IGNORECASE)
    if access and access.group(1) not in {"0", "offline", "-"}:
        raise ValueError("non_offline_access_token_not_supported")
    environment = value.get("environment", {})
    if not isinstance(environment, dict):
        raise ValueError("environment_must_be_string_map")
    sensitive = ("MICROSOFT", "MSA", "OAUTH", "REFRESH_TOKEN", "XBOX", "PASSWORD")
    for key, entry in environment.items():
        if not isinstance(key, str) or not isinstance(entry, str):
            raise ValueError("environment_must_be_string_map")
        if any(marker in key.upper() for marker in sensitive):
            raise ValueError("microsoft_credentials_not_supported")
    host = str(value.get("control_host", "127.0.0.1")).lower()
    if host not in {"127.0.0.1", "::1", "localhost"}:
        raise ValueError("control_host_must_be_loopback")
    window_mode = str(value.get("window_mode", "background")).lower()
    if window_mode not in {"background", "minimized", "interactive"}:
        raise ValueError("window_mode_must_be_background_minimized_or_interactive")
    return value


def expand(value: str, variables: dict[str, str]) -> str:
    for key, replacement in variables.items():
        value = value.replace("{" + key + "}", replacement)
    return value


def terminate_tree(child: subprocess.Popen[bytes], timeout: float = 10.0) -> None:
    """Terminate the wrapper and its spawned Minecraft client process tree."""
    if child.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ["taskkill", "/PID", str(child.pid), "/T", "/F"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    else:
        try:
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
    try:
        child.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(child.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
        else:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        child.wait(timeout=timeout)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--once", action="store_true", help="Do not restart after the first exit")
    parser.add_argument("--print-launch", action="store_true", help="Validate and print the expanded launch only")
    args = parser.parse_args()

    config = load_config(args.config.resolve())
    username = config.get("username", "Bob")
    player_uuid = str(offline_uuid(username))
    body_id = str(config.get("body_id", username.lower()))
    control_host = str(config.get("control_host", "127.0.0.1"))
    control_port = str(int(config.get("control_port", 8766)))
    minecraft_server = str(config.get("minecraft_server", "127.0.0.1:25565"))
    window_mode = str(config.get("window_mode", "background")).lower()
    variables = {
        "username": username,
        "offline_uuid": player_uuid,
        "body_id": body_id,
        "control_host": control_host,
        "control_port": control_port,
        "minecraft_server": minecraft_server,
    }
    command = [expand(part, variables) for part in config["command"]]
    cwd = Path(config.get("cwd", args.config.parent)).resolve()
    state_path = Path(config.get("state_file", args.config.parent / "real-client-supervisor-state.json")).resolve()
    log_path = Path(config.get("log_file", args.config.parent / "real-client.log")).resolve()
    environment = dict(os.environ)
    for key, value in config.get("environment", {}).items():
        if not isinstance(key, str) or not isinstance(value, str):
            raise ValueError("environment_must_be_string_map")
        environment[key] = expand(value, variables)
    environment.update({
        "AIBOT_REAL_CLIENT": "1",
        "AIBOT_REAL_CLIENT_BODY_ID": body_id,
        "AIBOT_REAL_CLIENT_BOT_NAME": username,
        "AIBOT_REAL_CLIENT_HOST": control_host,
        "AIBOT_REAL_CLIENT_PORT": control_port,
        "AIBOT_REAL_CLIENT_WINDOW_MODE": window_mode,
    })
    if not environment.get("AIBOT_REAL_CLIENT_TOKEN"):
        raise ValueError("AIBOT_REAL_CLIENT_TOKEN_must_be_supplied_in_environment")

    launch = {
        "command": command,
        "cwd": str(cwd),
        "username": username,
        "offline_uuid": player_uuid,
        "body_id": body_id,
        "minecraft_server": minecraft_server,
        "window_mode": window_mode,
    }
    if args.print_launch:
        print(json.dumps(launch, ensure_ascii=False, indent=2))
        return 0

    stop = False
    child: subprocess.Popen[bytes] | None = None

    def request_stop(_signum: int, _frame: object) -> None:
        nonlocal stop
        stop = True
        if child is not None and child.poll() is None:
            terminate_tree(child)

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    restart_count = 0
    minimum_runtime = float(config.get("stable_runtime_seconds", 30))
    maximum_backoff = float(config.get("maximum_backoff_seconds", 30))
    log_path.parent.mkdir(parents=True, exist_ok=True)
    while not stop:
        started = time.time()
        with log_path.open("ab", buffering=0) as log:
            log.write(("\n=== real-client launch " + time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()) + " ===\n").encode())
            launch_options: dict[str, Any] = {
                "cwd": cwd,
                "env": environment,
                "stdin": subprocess.DEVNULL,
                "stdout": log,
                "stderr": subprocess.STDOUT,
                "shell": False,
            }
            if os.name == "nt":
                launch_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
                if window_mode != "interactive":
                    startup = subprocess.STARTUPINFO()
                    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
                    startup.wShowWindow = getattr(subprocess, "SW_SHOWMINNOACTIVE", 7)
                    launch_options["startupinfo"] = startup
            else:
                launch_options["start_new_session"] = True
            child = subprocess.Popen(command, **launch_options)
            atomic_json(state_path, {
                **launch,
                "pid": child.pid,
                "state": "running",
                "restart_count": restart_count,
                "started_at_unix": started,
            })
            return_code = child.wait()
        runtime = time.time() - started
        atomic_json(state_path, {
            **launch,
            "pid": None,
            "state": "stopped" if stop else "exited",
            "return_code": return_code,
            "runtime_seconds": runtime,
            "restart_count": restart_count,
        })
        child = None
        if stop or args.once:
            break
        if runtime >= minimum_runtime:
            restart_count = 0
        else:
            restart_count += 1
        delay = min(maximum_backoff, 0.5 * (2 ** min(restart_count, 6)))
        time.sleep(delay)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
