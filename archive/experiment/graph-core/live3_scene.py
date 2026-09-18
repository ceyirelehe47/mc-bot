# -*- coding: utf-8 -*-
"""LIVE-3 final scene: wipe all trees/saplings/leaves/drops around, rebuild one scene tree."""
import subprocess
import time

RCON = ["python", "D:/code/mc-experiment/rcon.py"]


def rcon(cmd):
    r = subprocess.run(RCON + [cmd], capture_output=True, text=True, timeout=25)
    out = (r.stdout or "").strip().replace("\x00", "")
    print(f"  {cmd[:70]:70s} -> {out[:40]}")
    return out


# 分块 fill 清扫（每块 < 32768 blocks）
BLOCKS = [
    ("490 66 95",  "524 86 125"),
    ("490 66 125", "524 86 155"),
    ("524 66 95",  "560 86 125"),
    ("524 66 125", "560 86 155"),
]
TARGETS = [
    "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:oak_sapling",
    "minecraft:birch_log", "minecraft:birch_leaves", "minecraft:birch_sapling",
    "minecraft:spruce_log", "minecraft:spruce_leaves", "minecraft:spruce_sapling",
]

print("== wipe trees/leaves/saplings ==")
for lo, hi in BLOCKS:
    for t in TARGETS:
        rcon(f"fill {lo} {hi} air replace {t}")
        time.sleep(0.05)

print("== drop cleanup ==")
rcon("kill @e[type=minecraft:item]")
rcon("clear Bob")
rcon("tp Bob 524.5 68.5 121.5")

print("== rebuild scene tree (530,68..71,121) ==")
rcon("setblock 530 67 121 dirt")
for y in (68, 69, 70, 71):
    rcon(f"setblock 530 {y} 121 oak_log")
for dx, dz in ((0, -1), (0, 1), (-1, 0), (1, 0)):
    rcon(f"setblock {530 + dx} 71 {121 + dz} oak_leaves")

print("== foreign blocks ==")
rcon("setblock 528 68 121 cobblestone")
rcon("setblock 529 68 120 dirt")
rcon("setblock 532 68 121 dirt")

print("== verify scene ==")
for y in (68, 69, 70, 71):
    out = rcon(f"execute if block 530 {y} 121 minecraft:oak_log run seed")
    assert "Seed" in out, f"scene tree y={y} missing"
print("SCENE READY")
