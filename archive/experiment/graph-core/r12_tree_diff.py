# -*- coding: utf-8 -*-
"""R1.2: dev 树与重放树 src/ 逐文件 sha 对比(零 diff 验证)。"""
import hashlib
import pathlib

def manifest(root):
    out = {}
    for p in sorted(pathlib.Path(root, "src").rglob("*")):
        if p.is_file():
            key = p.relative_to(root).as_posix()
            out[key] = hashlib.sha256(p.read_bytes()).hexdigest()
    return out

a = manifest("aibot")
b = manifest("aibot-replay")
keys = sorted(set(a) | set(b))
diff = [k for k in keys if a.get(k) != b.get(k)]
print("files:", len(a), len(b), "diff:", len(diff))
for k in diff[:10]:
    print(" DIFF", k, "dev" if k in a else "-", "replay" if k in b else "-")
