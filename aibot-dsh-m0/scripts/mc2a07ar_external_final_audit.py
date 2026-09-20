#!/usr/bin/env python3
"""Run the final exact-secret Git audit after the final remote HEAD is frozen.

The attestation output must be outside the repository. Running this script never modifies Git.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

REQUIRED_SECRET_FILE_COUNT = 2


def run(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def inside(child: Path, parent: Path) -> bool:
    try:
        child.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def remote_head(repo: Path, branch: str) -> str:
    remote_line = run(
        repo,
        "ls-remote",
        "--heads",
        "origin",
        f"refs/heads/{branch}",
    ).stdout.strip()
    if not remote_line:
        raise ValueError("remote_branch_missing")
    return remote_line.split()[0]


def resolve_secret_files(
    repo: Path,
    secret_files: list[Path],
) -> tuple[list[Path], list[str]]:
    """Resolve and validate the exact old/new token files without exposing values."""
    if len(secret_files) != REQUIRED_SECRET_FILE_COUNT:
        raise ValueError("exactly_two_secret_files_required")

    resolved: list[Path] = []
    values: list[str] = []
    fingerprints: list[str] = []
    for supplied in secret_files:
        path = supplied if supplied.is_absolute() else repo / supplied
        path = path.resolve()
        if not path.is_file():
            raise ValueError(f"secret_file_missing:{supplied}")
        try:
            value = path.read_text(encoding="utf-8").strip()
        except UnicodeDecodeError as error:
            raise ValueError(f"secret_file_not_utf8:{supplied}") from error
        if not value:
            raise ValueError(f"secret_file_empty:{supplied}")
        resolved.append(path)
        values.append(value)
        fingerprints.append(
            hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]
        )

    if len(set(resolved)) != REQUIRED_SECRET_FILE_COUNT:
        raise ValueError("secret_file_paths_must_be_distinct")
    if len(set(values)) != REQUIRED_SECRET_FILE_COUNT:
        raise ValueError("secret_file_values_must_be_distinct")
    return resolved, sorted(fingerprints)


def attestation_checks(
    *,
    audit: dict[str, Any],
    base: str,
    head_before: str,
    head_after: str,
    remote_before: str,
    remote_after: str,
    status_before: str,
    status_after: str,
    secret_file_count: int,
    secret_fingerprints: list[str],
    forbidden_ancestors: list[str],
    expected_commit_count: int,
) -> dict[str, bool]:
    return {
        "worktree_clean_before": not status_before.strip(),
        "worktree_clean_after": not status_after.strip(),
        "local_head_stable": head_before == head_after,
        "remote_head_matches_before": remote_before == head_before,
        "remote_head_matches_after": remote_after == head_before,
        "remote_head_stable": remote_before == remote_after,
        "exact_two_secret_files": (
            secret_file_count == REQUIRED_SECRET_FILE_COUNT
        ),
        "distinct_secret_fingerprints": (
            len(secret_fingerprints) == REQUIRED_SECRET_FILE_COUNT
            and len(set(secret_fingerprints)) == REQUIRED_SECRET_FILE_COUNT
        ),
        "forbidden_ancestor_gate_supplied": bool(forbidden_ancestors),
        "audit_pass": audit.get("pass") is True,
        "audit_zero_findings": audit.get("finding_count") == 0,
        "audit_head_matches": audit.get("head") == head_before,
        "audit_base_matches": audit.get("base") == base,
        "audit_commit_count_matches": (
            audit.get("commit_count") == expected_commit_count
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--base", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--forbid-ancestor", action="append", default=[])
    parser.add_argument("--secret-file", action="append", default=[], type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    repo = args.repo.resolve()
    out = args.out.resolve()
    if inside(out, repo):
        raise SystemExit("attestation_output_must_be_outside_repository")
    if not args.forbid_ancestor:
        raise SystemExit("forbidden_ancestor_required")

    try:
        secret_files, secret_fingerprints = resolve_secret_files(
            repo, args.secret_file
        )
    except ValueError as error:
        raise SystemExit(str(error)) from error

    status_before = run(repo, "status", "--porcelain").stdout
    if status_before.strip():
        raise SystemExit("worktree_must_be_clean")
    branch = run(repo, "branch", "--show-current").stdout.strip()
    if branch != args.branch:
        raise SystemExit(f"unexpected_branch:{branch}")

    head_before = run(repo, "rev-parse", "HEAD").stdout.strip()
    remote_before = remote_head(repo, args.branch)
    if remote_before != head_before:
        raise SystemExit(
            f"remote_head_mismatch:local={head_before}:remote={remote_before}"
        )

    hygiene = repo / "aibot-dsh-m0" / "scripts" / "evidence_hygiene.py"
    if not hygiene.is_file():
        raise SystemExit("evidence_hygiene_script_missing")

    expected_commit_count = int(
        run(repo, "rev-list", "--count", f"{args.base}..{head_before}")
        .stdout.strip()
    )

    with tempfile.TemporaryDirectory() as directory:
        report_path = Path(directory) / "git-audit.json"
        command = [
            sys.executable,
            str(hygiene),
            "audit-git",
            "--repo",
            str(repo),
            "--base",
            args.base,
            "--head",
            head_before,
            "--report",
            str(report_path),
        ]
        for forbidden in args.forbid_ancestor:
            command.extend(["--forbid-ancestor", forbidden])
        for secret_file in secret_files:
            command.extend(["--secret-file", str(secret_file)])
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if completed.returncode != 0:
            raise SystemExit(
                "final_git_audit_failed\n"
                + completed.stdout[-4000:]
                + completed.stderr[-4000:]
            )
        audit = json.loads(report_path.read_text(encoding="utf-8"))

    head_after = run(repo, "rev-parse", "HEAD").stdout.strip()
    remote_after = remote_head(repo, args.branch)
    status_after = run(repo, "status", "--porcelain").stdout

    checks = attestation_checks(
        audit=audit,
        base=args.base,
        head_before=head_before,
        head_after=head_after,
        remote_before=remote_before,
        remote_after=remote_after,
        status_before=status_before,
        status_after=status_after,
        secret_file_count=len(secret_files),
        secret_fingerprints=secret_fingerprints,
        forbidden_ancestors=args.forbid_ancestor,
        expected_commit_count=expected_commit_count,
    )
    result = {
        "attestation_version": 2,
        "audited_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "repo": str(repo),
        "branch": branch,
        "base": args.base,
        "audited_head": head_before,
        "head_after_audit": head_after,
        "remote_head_before": remote_before,
        "remote_head_after": remote_after,
        "expected_commit_count": expected_commit_count,
        "worktree_clean_before": not status_before.strip(),
        "worktree_clean_after": not status_after.strip(),
        "forbidden_ancestors": args.forbid_ancestor,
        "secret_file_count": len(secret_files),
        "secret_fingerprints": secret_fingerprints,
        "audit": audit,
        "checks": checks,
        "failed": [name for name, value in checks.items() if not value],
        "pass": all(checks.values()),
        "freeze_rule": (
            "No commit may be added to this branch after this attestation."
        ),
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
