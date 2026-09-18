# -*- coding: utf-8
"""R1.2: dev/replay 两树 src/ 清单(路径+sha256)。"""
import hashlib
import pathlib
import sys

for root, out in [("aibot", sys.argv[1]), ("aibot-replay", sys.argv[2])]:
    lines = []
    for p in sorted(pathlib.Path(root, "src").rglob("*")):
        if p.is_file():
            lines.append(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.relative_to(root).as_posix())
    open(out, "w", newline="\n").write("\n".join(lines) + "\n")
    print(out, len(lines))
