# -*- coding: utf-8 -*-
"""LIVE-F2: 在 TREE_ACCESS 支撑堆叠窗口内插入 SAFETY 威胁,验证 pause/resume 保持同一树事务。

流程: 后台提交 gather -> 0.3s 轮询日志首个 tree_support_placed -> 立即 RCON 召唤僵尸
-> 持续轮询直到 gather 终态,收集 paused/resumed/同 treeId 证据。
"""
import re
import subprocess
import sys
import threading
import time

LOG = r"D:\code\mc-experiment\mc-server-mc1ca\server-graph-a.log"
RCON = [sys.executable, r"D:\code\mc-experiment\rcon.py"]


def rcon(cmd: str) -> str:
    out = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=15)
    return (out.stdout or "") + (out.stderr or "")


def log_text() -> str:
    try:
        with open(LOG, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def main() -> None:
    result: dict = {}

    def submit() -> None:
        out = subprocess.run(
            [sys.executable, r"D:\code\mc-experiment\mc2a0_live.py", "exec",
             "gather", '{"item":"minecraft:oak_log","count":1}', "4"],
            capture_output=True, text=True, timeout=60,
            cwd=r"D:\code\mc-experiment")
        result["submit"] = (out.stdout or "") + (out.stderr or "")

    thread = threading.Thread(target=submit)
    thread.start()

    baseline = log_text().count("tree_support_placed")
    support_at = None
    deadline = time.time() + 90
    while time.time() < deadline:
        time.sleep(0.3)
        text = log_text()
        if text.count("tree_support_placed") > baseline:
            support_at = time.time()
            break
    if support_at is None:
        print("NO_SUPPORT_WINDOW")
        return
    print("SUPPORT_WINDOW_ENTERED")
    print("summon:", rcon("summon minecraft:zombie 544.5 68 127.5 {PersistenceRequired:1b}").strip())

    while time.time() < support_at + 120:
        time.sleep(1.0)
        text = log_text()
        if re.search(r'state.: .completed.|state.: .failed.|state.: .cancelled', result.get("submit", "")):
            break
        if "tree_workset_complete" in text.split("SUPPORT_WINDOW_ENTERED")[-1] or \
                text.count("task_completed") > 0 and "gather" in text.rsplit("task_completed", 1)[1][:80]:
            break
    time.sleep(3)
    print("post-state:", rcon("data get entity Bob Pos").strip())


if __name__ == "__main__":
    main()
