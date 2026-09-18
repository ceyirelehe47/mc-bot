# -*- coding: utf-8 -*-
"""R11 重放对账: 对应用树(排除 .git/build/run)做全文件 sha256 清单。"""
import hashlib, pathlib, sys

root = pathlib.Path(r'D:\code\mc-experiment\aibot')
out = {}
for p in sorted(root.rglob('*')):
    if not p.is_file():
        continue
    rel = p.relative_to(root).as_posix()
    if rel.split('/')[0] in ('.git', 'build', 'run', '.gradle'):
        continue
    out[rel] = hashlib.sha256(p.read_bytes()).hexdigest()

dest = pathlib.Path(sys.argv[1])
dest.write_text('\n'.join(f'{h}  {r}' for r, h in sorted(out.items())) + '\n', encoding='utf-8', newline='\n')
print(f'{len(out)} files -> {dest}')
