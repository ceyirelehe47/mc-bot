#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""MC-2A0.1F replay 0-diff raw evidence: byte-compare every file of the patched worktree
against the freshly-replayed tree (frozen upstream a029fa6 + apply_to_aibot.py).
Usage: python replay_diff.py <worktree> <replay> <upstream_sha> <production_sha> <out>"""
import hashlib
import os
import sys

wt, rp, upstream, production, out = sys.argv[1:6]
SKIP_DIRS = {'.git', '.gradle', 'build', 'run', '.build', '__pycache__', 'logs'}


def scan(root):
    files = {}
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in names:
            path = os.path.join(base, name)
            rel = os.path.relpath(path, root).replace(os.sep, '/')
            with open(path, 'rb') as h:
                files[rel] = hashlib.sha256(h.read()).hexdigest()
    return files


a, b = scan(wt), scan(rp)
diff = sorted(k for k in a.keys() & b.keys() if a[k] != b[k])
only_wt = sorted(a.keys() - b.keys())
only_rp = sorted(b.keys() - a.keys())
lines = [
    'upstream_base=' + upstream,
    'production_sha=' + production,
    'compared_files=' + str(len(a.keys() | b.keys())),
    'DIFF=' + str(diff),
    'ONLY_IN_WORKTREE=' + str(only_wt),
    'ONLY_IN_REPLAY=' + str(only_rp),
    'exit=' + str(0 if not (diff or only_wt or only_rp) else 1),
]
with open(out, 'w', encoding='utf-8', newline='\n') as h:
    h.write('\n'.join(lines) + '\n')
print('\n'.join(lines))
sys.exit(0 if not (diff or only_wt or only_rp) else 1)
