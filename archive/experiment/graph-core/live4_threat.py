# -*- coding: utf-8 -*-
"""LIVE-4 final: real Safety preemption via hostile threat during tree harvest."""
import subprocess
import threading
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]
LOG = "D:/code/mc-experiment/mc-server-mc1ca/server-2a02.log"


def rcon(cmd, timeout=25):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "").strip().replace("\x00", "")


def tail_log(bytes_=60000):
    return subprocess.run(["tail", "-c", str(bytes_), LOG], capture_output=True, text=True).stdout


def main():
    rcon("tp Bob 524.5 68.5 121.5")
    rcon("effect give Bob minecraft:instant_health 1 5 true")
    rcon("clear Bob")
    rcon("kill @e[type=minecraft:item]")
    rcon("kill @e[type=minecraft:zombie]")
    rcon("give Bob minecraft:dirt 8")
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
    deadline = time.time() + 40
    acquired = False
    while time.time() < deadline:
        time.sleep(1.2)
        if "tree_workset_acquired" in tail_log(30000):
            acquired = True
            break
    print("acquired =", acquired)
    if not acquired:
        t.join(timeout=290)
        print("gather:", gather_out.get("text", "")[-250:])
        return 1
    time.sleep(2.5)  # mid-HARVEST
    # real hostile threat next to Bob -> DangerWatcher SAFETY preemption
    print("summon:", rcon("summon minecraft:zombie 526.5 68.0 120.5 {NoAI:0b}"))
    time.sleep(25)   # let SAFETY combat run; then clear threat
    print("cleanup zombie:", rcon("kill @e[type=minecraft:zombie]")[:50])
    t.join(timeout=280)
    print("gather result:", gather_out.get("text", "")[-250:])
    return 0


if __name__ == "__main__":
    main()
