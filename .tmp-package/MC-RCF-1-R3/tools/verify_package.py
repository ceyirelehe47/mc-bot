#!/usr/bin/env python3
"""Read-only SHA-256 verification of this task package; not gameplay acceptance."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys


def verify(root: Path) -> dict:
    root = root.resolve()
    errors: list[str] = []
    expected: dict[str, str] = {}
    folded: set[str] = set()
    try:
        lines = (root / 'SHA256SUMS').read_text(encoding='utf-8').splitlines()
    except (OSError, UnicodeError) as exc:
        return {'ok': False, 'checked_files': 0, 'errors': ['manifest unreadable: ' + str(exc)]}
    for n, line in enumerate(lines, 1):
        if not line.strip(): continue
        m = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
        if not m:
            errors.append(f'malformed manifest line {n}'); continue
        digest, rel = m.groups()
        p = PurePosixPath(rel)
        if ('\\' in rel or ':' in rel or p.is_absolute() or
                any(x in ('', '.', '..') for x in rel.split('/')) or rel == 'SHA256SUMS'):
            errors.append(f'unsafe path at line {n}'); continue
        if rel.casefold() in folded:
            errors.append(f'duplicate/case-colliding path: {rel}'); continue
        folded.add(rel.casefold()); expected[rel] = digest
    if not expected: errors.append('manifest has no files')
    checked = 0
    for rel, digest in expected.items():
        path = root / rel
        try:
            if not path.resolve().is_relative_to(root):
                errors.append(f'path outside package: {rel}'); continue
            cur = root
            for part in PurePosixPath(rel).parts:
                cur = cur / part
                if cur.is_symlink(): raise ValueError('symlink is not allowed')
            if not path.is_file():
                errors.append(f'missing file: {rel}'); continue
            actual = hashlib.sha256(path.read_bytes()).hexdigest(); checked += 1
            if actual != digest: errors.append(f'hash mismatch: {rel}')
        except (OSError, ValueError) as exc:
            errors.append(f'unreadable/unsafe {rel}: {exc}')
    actual_files = {p.relative_to(root).as_posix() for p in root.rglob('*')
                    if p.is_file() or p.is_symlink()}
    extras = sorted(actual_files - set(expected) - {'SHA256SUMS'})
    if extras: errors.append('unlisted files: ' + ', '.join(extras))
    return {'ok': not errors, 'checked_files': checked,
            'expected_files': len(expected), 'errors': errors,
            'scope': 'PACKAGE_INTEGRITY_ONLY_NOT_MINECRAFT_ACCEPTANCE'}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    result = verify(ap.parse_args().root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['ok'] else 1

if __name__ == '__main__': sys.exit(main())
