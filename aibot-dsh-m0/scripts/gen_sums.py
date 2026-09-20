#!/usr/bin/env python3
"""Regenerate SHA256SUMS for this handoff tree, excluding the manifest and build caches."""
from __future__ import annotations

import hashlib
import os
import pathlib

root = pathlib.Path(__file__).resolve().parents[1]
entries: list[str] = []
for path in sorted(root.rglob("*")):
    if not path.is_file():
        continue
    relative = path.relative_to(root).as_posix()
    if relative == "SHA256SUMS":
        continue
    if "__pycache__" in path.parts or ".build" in path.parts:
        continue
    entries.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}")

temporary = root / "SHA256SUMS.tmp"
temporary.write_text("\n".join(entries) + "\n", encoding="utf-8", newline="\n")
os.replace(temporary, root / "SHA256SUMS")
print(f"wrote {len(entries)} entries; SHA256SUMS self-entry excluded")
