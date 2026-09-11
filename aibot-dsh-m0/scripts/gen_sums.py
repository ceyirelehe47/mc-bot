#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Regenerate aibot-dsh-m0/SHA256SUMS: every file under the handoff dir except
SHA256SUMS itself and __pycache__ trees. LF newlines, sorted by path."""
import hashlib
import pathlib

root = pathlib.Path(r'D:\code\mc-bot\aibot-dsh-m0')
entries = []
for path in sorted(root.rglob('*')):
    if not path.is_file():
        continue
    rel = path.relative_to(root).as_posix()
    if rel == 'SHA256SUMS':
        continue
    if '__pycache__' in path.parts or '.build' in path.parts:
        continue
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    entries.append(f'{digest}  {rel}')

out = root / 'SHA256SUMS'
out.write_text('\n'.join(entries) + '\n', encoding='utf-8', newline='\n')
print(f'wrote {len(entries)} entries')
