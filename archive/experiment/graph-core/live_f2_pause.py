# -*- coding: utf-8 -*-
"""LIVE-F2(外部 pause/resume 无位移变体): 支撑存活时暂停->恢复->续接完成。"""
import re
import importlib.util
import sys
import threading
import time

spec = importlib.util.spec_from_file_location(
    "mc2a0_live", r"D:\code\mc-experiment\mc2a0_live.py")
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)

LOG = r"D:\code\mc-experiment\mc-server-mc1ca\server-graph-a.log"


def log_text():
    with open(LOG, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def main():
    result = {}

    def submit():
        out = subprocess_run(["gather", '{"item":"minecraft:oak_log","count":1}', "3"])
        result["submit"] = out

    def subprocess_run(args):
        import subprocess
        out = subprocess.run(
            [sys.executable, r"D:\code\mc-experiment\mc2a0_live.py", "exec"] + args,
            capture_output=True, text=True, timeout=90)
        return (out.stdout or "") + (out.stderr or "")

    threading.Thread(target=submit).start()

    baseline = log_text().count("tree_support_placed")
    deadline = time.time() + 90
    while time.time() < deadline:
        time.sleep(0.3)
        if log_text().count("tree_support_placed") > baseline:
            break
    else:
        print("NO_WINDOW")
        return
    print("WINDOW")

    text = log_text()
    exec_ids = re.findall(r'external_dsh:([0-9a-f-]{36})', text)
    exec_id = exec_ids[-1]
    print("exec:", exec_id)

    lease = live.get_lease()
    seq = [0]

    def control(action):
        seq[0] += 1
        rid = f"livef2-control-{seq[0]}"
        out = live.call("POST", f"/v1/executions/{exec_id}/{action}", lease,
                        body="{}", headers={"X-Request-Id": rid})
        return out

    print("pause:", str(control("pause"))[:160])
    time.sleep(3)
    print("resume:", str(control("resume"))[:160])

    end = time.time() + 150
    while time.time() < end:
        time.sleep(2)
        if "tree_workset_complete" in log_text()[-30000:] or \
                re.search(r'state": "(completed|failed|cancelled)"', result.get("submit", "")):
            break
    time.sleep(2)
    print("submit-out:", result.get("submit", "")[-300:])


if __name__ == "__main__":
    main()
