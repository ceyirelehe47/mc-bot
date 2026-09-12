# -*- coding: utf-8 -*-
"""截掉 journal 坏尾帧, 以正确 seq=644 重追加 legacy 收据。"""
import struct
import zlib
import json

path = r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-body-bob.journal"
opp = "ore_legacyr1c5fixture0001"
world = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"

data = open(path, "rb").read()
trimmed = data[:273898]  # 坏帧起点之前

fields = {
    "kind": "resource_opportunity_consumed",
    "execution_id": "legacy-execution-r1c5",
    "opportunity_id": opp,
    "world_id": world,
    "dimension": "minecraft:overworld",
    "payload": json.dumps({
        "opportunity_id": opp,
        "resolution": "inventory_gain_proven",
        "dimension": "minecraft:overworld",
        "world_id": world,
    }),
}
body = bytearray()
body += struct.pack(">q", 644)
body += struct.pack(">q", 1789182000000)
body += struct.pack(">i", len(fields))
for k, v in fields.items():
    kb = k.encode()
    vb = v.encode()
    body += struct.pack(">i", len(kb)) + kb
    body += struct.pack(">i", len(vb)) + vb
crc = zlib.crc32(bytes(body)) & 0xFFFFFFFF
frame = struct.pack(">i", len(body)) + bytes(body) + struct.pack(">I", crc)
open(path, "wb").write(trimmed + frame)
print("seq=644 追加完成, 帧长", len(frame))
