# -*- coding: utf-8 -*-
"""Probe ground height near LIVE test area via RCON (one call per second batch)."""
import subprocess
import sys
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]


def rcon(cmd):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=20)
    return (r.stdout or "").strip()


def ground_height(x, z, y_from=90, y_to=60):
    # find topmost non-air from y_from going down
    for y in range(y_from, y_to, -1):
        out = rcon(f"execute if block {x} {y} {z} #minecraft:air run seed")
        # if block IS air -> command runs -> prints seed; else no output
        if "Seed" not in out:
            return y
    return None


if __name__ == "__main__":
    rcon("setblock 0 109 0 air")  # clean stray probe from earlier
    for x, z in [(520, 121), (524, 121), (528, 121), (524, 115), (524, 127)]:
        print((x, z), "-> ground y =", ground_height(x, z))
        time.sleep(0.3)
