# -*- coding: utf-8 -*-
"""LIVE-5: replace an own placed support with a foreign block mid-transaction;
verify explicit cleanup debt (no delete, no false success, auditable reason)."""
import re
import subprocess
import threading
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]
LOG = "D:/code/mc-experiment/mc-server-mc1ca/server-2a02.log"


def rcon(cmd, timeout=25):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "").strip().replace("\x00", "")


def tail_log(n=40000):
    return subprocess.run(["tail", "-c", str(n), LOG], capture_output=True, text=True).stdout


def main():
    # high acquisition scene: stand Bob on a 3-block pedestal beside an 8-log tree
    rcon("tp Bob 526.5 71 111.5")
    rcon("setblock 526 68 111 cobblestone")
    rcon("setblock 526 69 111 cobblestone")
    rcon("setblock 526 70 111 cobblestone")
    rcon("effect give Bob minecraft:instant_health 1 5 true")
    rcon("clear Bob")
    rcon("kill @e[type=minecraft:item]")
    rcon("give Bob minecraft:dirt 32")
    rcon("setblock 530 67 111 dirt")
    for y in range(68, 76):
        rcon(f"setblock 530 {y} 111 oak_log")
    for dx, dz in ((0, -1), (0, 1), (-1, 0), (1, 0)):
        rcon(f"setblock {530 + dx} 75 {111 + dz} oak_leaves")

    gather_out = {}

    def run_gather():
        r = subprocess.run(
            ["python", "D:/code/mc-experiment/mc2a0_live.py", "exec", "gather",
             '{"item":"minecraft:oak_log","count":1}', "260"],
            capture_output=True, text=True, timeout=280, cwd="D:/code/mc-experiment")
        gather_out["text"] = (r.stdout or "") + (r.stderr or "")

    t = threading.Thread(target=run_gather)
    t.start()

    # fast poll: first tree_support_placed -> immediately replace that cell with gold_block
    replaced = None
    pat = re.compile(r"event=tree_support_placed.*?pos=(-?\d+), (-?\d+), (-?\d+)")
    deadline = time.time() + 180
    seen = 0
    while time.time() < deadline and replaced is None:
        time.sleep(0.15)
        events = pat.findall(tail_log(30000))
        if len(events) > seen and events:
            x, y, z = events[-1]
            out = rcon(f"setblock {x} {y} {z} gold_block")
            replaced = (x, y, z, out)
            break
    print("replaced:", replaced)
    t.join(timeout=280)
    print("gather result:", gather_out.get("text", "")[-260:])
    if replaced:
        x, y, z = replaced[:3]
        still = rcon(f"execute if block {x} {y} {z} minecraft:gold_block run seed")
        print("gold_block survives:", "Seed" in still, (x, y, z))
    # any own dirt support left anywhere on the trunk column?
    for y in range(67, 77):
        out = rcon(f"execute if block 530 {y} 111 minecraft:dirt run seed")
        if "Seed" in out:
            print("own dirt remains at 530,", y, ",111")
    return 0


if __name__ == "__main__":
    main()
