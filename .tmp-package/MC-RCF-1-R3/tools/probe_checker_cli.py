#!/usr/bin/env python3
"""Offline probes of a selected final checker CLI. Never starts Minecraft itself.

CLI contract used by this probe only:
  python checker.py <entry> <file> -> one JSON {entry, accept: bool, reason}
  accept=true/exit0 or accept=false/exit1; all other outcomes are ERROR.
A restructured application may provide a call adapter, not a replacement judge.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LEGACY = ROOT / 'reference/review_ff29078/synthetic_inputs'


def no_duplicate_keys(pairs):
    out = {}
    for k, v in pairs:
        if k in out: raise ValueError('duplicate JSON key: ' + k)
        out[k] = v
    return out


def invoke(checker: Path, entry: str, evidence: Path, timeout: float = 30) -> dict:
    row = {'entry': entry, 'evidence': str(evidence), 'status': 'ERROR'}
    if not checker.is_file() or not evidence.is_file():
        return dict(row, reason='checker or input file missing')
    try:
        process = subprocess.run(
            [sys.executable, str(checker.resolve()), entry, str(evidence.resolve())],
            cwd=str(checker.resolve().parent), capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=timeout,
            env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8'))
        row['exit_code'] = process.returncode
        response = json.loads(process.stdout, object_pairs_hook=no_duplicate_keys)
        if (not isinstance(response, dict) or type(response.get('accept')) is not bool
                or response.get('entry') != entry or not isinstance(response.get('reason'), str)
                or not response['reason'].strip()):
            return dict(row, reason='invalid checker response contract')
        accepted = response['accept']
        wanted_code = 0 if accepted else 1
        if process.returncode != wanted_code:
            return dict(row, reason='exit-code/decision contradiction', response=response)
        return dict(row, status='ACCEPT' if accepted else 'REJECT', response=response)
    except subprocess.TimeoutExpired:
        return dict(row, reason='checker timeout; not a valid rejection')
    except (OSError, ValueError, TypeError) as exc:
        return dict(row, reason='checker execution/parse error: ' + str(exc))


def evaluate(checker: Path, entry: str, evidence: Path, expected: bool, timeout: float) -> dict:
    row = invoke(checker, entry, evidence, timeout)
    row['expected_accept'] = expected
    row['ok'] = row['status'] == ('ACCEPT' if expected else 'REJECT')
    return row


def legacy_cases() -> list[tuple[str, str, Path]]:
    out = []
    for p in sorted(LEGACY.glob('*.json')):
        name = p.stem
        entry = ('judge-g4' if name.startswith('G4') or name.startswith('CTRL02')
                 else 'judge-ia')
        out.append((name, entry, p))
    if len(out) != 10:
        raise ValueError('expected the 7 frozen invalid probes and 3 rejection controls')
    return out


def load_pairs(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=no_duplicate_keys)
    if not isinstance(data, list) or not data: raise ValueError('pairs must be a nonempty list')
    ids = set(); out = []
    for pair in data:
        if not isinstance(pair, dict): raise ValueError('pair is not an object')
        for key in ('id', 'entry', 'positive', 'negative', 'purpose'):
            if not isinstance(pair.get(key), str) or not pair[key].strip():
                raise ValueError('pair needs nonblank ' + key)
        if pair['id'] in ids: raise ValueError('duplicate pair id')
        ids.add(pair['id'])
        row = dict(pair)
        for kind in ('positive', 'negative'):
            p = (path.parent / pair[kind]).resolve()
            if not p.is_file(): raise ValueError('missing ' + kind + ' input')
            row[kind] = p
        if row['positive'] == row['negative']: raise ValueError('pair uses the same input file twice')
        out.append(row)
    return out


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile('w', dir=path.parent, encoding='utf-8', delete=False) as fh:
        tmp = Path(fh.name)
        json.dump(report, fh, ensure_ascii=False, indent=2); fh.write('\n')
    try: os.replace(tmp, path)
    finally: tmp.unlink(missing_ok=True)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--checker', required=True, type=Path)
    ap.add_argument('--pairs', type=Path, help='optional positive/semantic-mutation pair manifest')
    ap.add_argument('--pairs-only', action='store_true')
    ap.add_argument('--timeout', type=float, default=30)
    ap.add_argument('--report', type=Path)
    args = ap.parse_args(argv)
    if (not args.checker.is_file() or not math.isfinite(args.timeout) or args.timeout <= 0
            or (args.pairs_only and args.pairs is None)):
        ap.error('valid checker, positive timeout and a nonempty suite are required')
    rows = []
    try:
        if not args.pairs_only:
            for tid, entry, evidence in legacy_cases():
                rows.append(dict(evaluate(args.checker, entry, evidence, False, args.timeout),
                                 id=tid, mode='FROZEN_SYNTHETIC_INVALID_LEGACY'))
        pairs = load_pairs(args.pairs) if args.pairs else []
        for pair in pairs:
            for kind, expected in (('positive', True), ('negative', False)):
                rows.append(dict(evaluate(args.checker, pair['entry'], pair[kind], expected, args.timeout),
                                 id=pair['id'] + '/' + kind, mode='PAIRED_CLI_CHECK', purpose=pair['purpose']))
    except (OSError, ValueError, TypeError) as exc:
        print(json.dumps({'ok': False, 'error': str(exc)}, ensure_ascii=False))
        return 2
    report = {
        'scope': 'OFFLINE_CHECKER_PROBE_NOT_LIVE_OR_FULL_ACCEPTANCE',
        'checker_sha256': hashlib.sha256(args.checker.read_bytes()).hexdigest(),
        'legacy_rejection_only': not bool(pairs),
        'pair_count': len(pairs), 'case_count': len(rows),
        'passed': sum(r['ok'] for r in rows),
        'errors': sum(r['status'] == 'ERROR' for r in rows),
        'ok': bool(rows) and all(r['ok'] for r in rows), 'results': rows}
    if args.report: write_report(args.report.resolve(), report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['errors']: return 2
    return 0 if report['ok'] else 1

if __name__ == '__main__': sys.exit(main())
