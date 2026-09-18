# -*- coding: utf-8 -*-
"""R1.1 LIVE: 读取 BridgeJournal(AIBODY01 头 44 字节), 提取 resource_opportunity_* 收据帧。

用法: python r11_journal_read.py [journal路径] [只匹配 opportunity_id]
"""
import struct, sys, json, zlib

path = sys.argv[1] if len(sys.argv) > 1 else \
    r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-body-bob.journal"
only = sys.argv[2] if len(sys.argv) > 2 else None

data = open(path, "rb").read()
assert data[:8] == b"AIBODY01", data[:8]
journal_id = data[8:44].decode("ascii", "replace")
pos = 44
frames = []
while pos < len(data):
    if pos + 4 > len(data):
        break
    (body_len,) = struct.unpack_from(">i", data, pos)
    body = data[pos + 4: pos + 4 + body_len]
    (crc,) = struct.unpack_from(">I", data, pos + 4 + body_len)
    if zlib.crc32(body) & 0xFFFFFFFF != crc:
        print("CRC mismatch at", pos, file=sys.stderr)
        break
    seq, ts, count = struct.unpack_from(">qqi", body, 0)
    p = 20
    fields = {}
    for _ in range(count):
        (klen,) = struct.unpack_from(">i", body, p); p += 4
        k = body[p:p + klen].decode("utf-8"); p += klen
        (vlen,) = struct.unpack_from(">i", body, p); p += 4
        v = body[p:p + vlen].decode("utf-8"); p += vlen
        fields[k] = v
    frames.append({"seq": seq, "ts": ts, "fields": fields})
    pos += 8 + body_len

out = [f for f in frames if f["fields"].get("kind", "").startswith("resource_opportunity")]
if only:
    out = [f for f in out if f["fields"].get("opportunity_id") == only]
print(f"journal_id={journal_id} total_frames={len(frames)} resolution_frames={len(out)}")
for f in out:
    print(json.dumps(f, ensure_ascii=False))
