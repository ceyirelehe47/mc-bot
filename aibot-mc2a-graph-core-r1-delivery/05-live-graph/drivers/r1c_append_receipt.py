# -*- coding: utf-8 -*-
"""向 BridgeJournal 追加一帧(模拟后到的有效 durable consumed 收据)。"""
import struct, sys, zlib, json

path, opp, world = sys.argv[1], sys.argv[2], sys.argv[3]

data = open(path, "rb").read()
assert data[:4] in (b"AIBJ", b"AIBO"), data[:4]
pos = 40
last_seq = 0
while pos < len(data):
    (body_len,) = struct.unpack_from(">i", data, pos)
    body = data[pos + 4: pos + 4 + body_len]
    seq = struct.unpack_from(">q", body, 0)[0]
    last_seq = max(last_seq, seq)
    pos += 8 + body_len

fields = {
    "kind": "resource_opportunity_consumed",
    "execution_id": "legacy-execution-r1c5",
    "opportunity_id": opp,
    "world_id": world,
    "dimension": "minecraft:overworld",
    "payload": json.dumps({"opportunity_id": opp, "resolution": "inventory_gain_proven",
                           "dimension": "minecraft:overworld", "world_id": world}),
}
body = bytearray()
body += struct.pack(">qq", last_seq + 1, 1789182000000)
body += struct.pack(">i", len(fields))
for k, v in fields.items():
    kb, vb = k.encode(), v.encode()
    body += struct.pack(">i", len(kb)) + kb + struct.pack(">i", len(vb)) + vb
crc = zlib.crc32(bytes(body)) & 0xFFFFFFFF
frame = struct.pack(">i", len(body)) + bytes(body) + struct.pack(">i", crc)
with open(path, "ab") as fh:
    fh.write(frame)
print(f"appended seq={last_seq+1} kind=resource_opportunity_consumed opp={opp}")
