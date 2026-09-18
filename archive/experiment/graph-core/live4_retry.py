# -*- coding: utf-8 -*-
"""LIVE-4 retry: reachable 4-log tree, damage mid-harvest, verify same-workset survival."""
import subprocess
import threading
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]


def rcon(cmd, timeout=25):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "").strip().replace("\x00", "")


def main():
    rcon("tp Bob 524.5 68.5 121.5")
    rcon("effect give Bob minecraft:instant_health 1 5 true")
    rcon("clear Bob")
    rcon("kill @e[type=minecraft:item]")
    rcon("give Bob minecraft:dirt 8")
    # rebuild the 4-log scene tree (fully reachable, no support needed)
    rcon("setblock 530 67 121 dirt")
    for y in (68, 69, 70, 71):
        rcon(f"setblock 530 {y} 121 oak_log")
    for dx, dz in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        rcon(f"setblock {530 + dx} 71 {121 + dz} oak_leaves")

    gather_out = {}

    def run_gather():
        r = subprocess.run(
            ["python", "D:/code/mc-experiment/mc2a0_live.py", "exec", "gather",
             '{"item":"minecraft:oak_log","count":1}', "280"],
            capture_output=True, text=True, timeout=300, cwd="D:/code/mc-experiment")
        gather_out["text"] = (r.stdout or "") + (r.stderr or "")

    t = threading.Thread(target=run_gather)
    t.start()
    log_path = "D:/code/mc-experiment/mc-server-mc1ca/server-2a02.log"
    base = open(log_path, "rb").seek(0, 2)  # current end
    acquired_at = None
    deadline = time.time() + 40
    while time.time() < deadline:
        time.sleep(1.2)
        tail = subprocess.run(["tail", "-c", "30000", log_path], capture_output=True, text=True).stdout
        if "tree_workset_acquired" in tail:
            acquired_at = time.time()
            break
    print("acquired =", acquired_at is not None)
    if not acquired_at:
        t.join(timeout=290)
        print("gather:", gather_out.get("text", "")[-250:])
        return 1
    time.sleep(2.5)  # mid-HARVEST
    print("damage:", rcon("damage Bob 7 minecraft:generic")[:60])
    t.join(timeout=290)
    print("gather result:", gather_out.get("text", "")[-250:])
    return 0


if __name__ == "__main__":
    main()
