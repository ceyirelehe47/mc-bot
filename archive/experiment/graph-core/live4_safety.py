# -*- coding: utf-8 -*-
"""LIVE-4: gather in progress -> real damage (urgent Safety) -> same workset survives."""
import subprocess
import sys
import threading
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]


def rcon(cmd, timeout=25):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "").strip().replace("\x00", "")


def main():
    # heal Bob first, clear logs and ground drops
    rcon("effect give Bob minecraft:instant_health 1 5 true")
    rcon("clear Bob minecraft:oak_log")
    rcon("clear Bob minecraft:birch_log")
    rcon("kill @e[type=minecraft:item]")

    gather_out = {}

    def run_gather():
        r = subprocess.run(
            ["python", "D:/code/mc-experiment/mc2a0_live.py", "exec", "gather",
             '{"item":"minecraft:oak_log","count":1}', "280"],
            capture_output=True, text=True, timeout=300, cwd="D:/code/mc-experiment")
        gather_out["text"] = (r.stdout or "") + (r.stderr or "")

    t = threading.Thread(target=run_gather)
    t.start()
    # wait for the gather to actually acquire the tree (poll server log marker)
    log_path = "D:/code/mc-experiment/mc-server-mc1ca/server-2a02.log"
    deadline = time.time() + 40
    acquired = False
    while time.time() < deadline:
        time.sleep(1.5)
        tail = subprocess.run(["tail", "-c", "20000", log_path], capture_output=True, text=True).stdout
        if "tree_workset_acquired" in tail.split("LIVE4-MARK")[-1]:
            acquired = True
            break
    print("acquired_before_damage =", acquired)
    if not acquired:
        t.join(timeout=290)
        print("gather finished early:", gather_out.get("text", "")[-300:])
        return 1
    time.sleep(2)  # let HARVEST actually begin
    d1 = rcon("damage Bob 7 minecraft:generic")
    print("damage#1:", d1[:60])
    time.sleep(4)
    d2 = rcon("damage Bob 7 minecraft:generic")
    print("damage#2:", d2[:60])
    t.join(timeout=290)
    print("gather result:", gather_out.get("text", "")[-260:])
    return 0


if __name__ == "__main__":
    sys.exit(main())
