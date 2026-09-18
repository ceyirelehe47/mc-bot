# -*- coding: utf-8 -*-
"""读取 BridgeJournal 原始文件, 提取 kind=resource_opportunity_* 帧。"""
import struct, sys, json, zlib

path = sys.argv[1]
data = open(path, "rb").read()
# 头: MAGIC(4) + id(36)
MAGIC = b"AIBJ"  # 以实际文件头为准, 先探测
assert data[:4] in (b"AIBJ", b"AIBO", b"AIJ1"), data[:4]
journal_id = data[4:40].decode("ascii", "replace")
pos = 40
frames = []
while pos < len(data):
    if pos + 4 > len(data): break
    (body_len,) = struct.unpack_from(">i", data, pos)
    body = data[pos + 4: pos + 4 + body_len]
    (crc,) = struct.unpack_from(">i", data, pos + 4 + body_len)
    if zlib.crc32(body) & 0xFFFFFFFF != crc & 0xFFFFFFFF:
        print("CRC mismatch at", pos, file=sys.stderr); break
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
print(f"journal_id={journal_id} total_frames={len(frames)} resolution_frames={len(out)}")
for f in out:
    print(json.dumps(f, ensure_ascii=False))
