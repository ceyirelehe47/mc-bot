#!/usr/bin/env python3
"""Run the final exact-secret Git audit after the final remote HEAD is frozen.

The attestation output must be outside the repository. Running this script never modifies Git.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import subprocess
import sys
import tempfile
from pathlib import Path


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

    if run(repo, "status", "--porcelain").stdout.strip():
        raise SystemExit("worktree_must_be_clean")
    branch = run(repo, "branch", "--show-current").stdout.strip()
    if branch != args.branch:
        raise SystemExit(f"unexpected_branch:{branch}")

    head = run(repo, "rev-parse", "HEAD").stdout.strip()
    remote_line = run(
        repo, "ls-remote", "--heads", "origin",
        f"refs/heads/{args.branch}",
    ).stdout.strip()
    if not remote_line:
        raise SystemExit("remote_branch_missing")
    remote_head = remote_line.split()[0]
    if remote_head != head:
        raise SystemExit(
            f"remote_head_mismatch:local={head}:remote={remote_head}"
        )

    hygiene = repo / "aibot-dsh-m0" / "scripts" / "evidence_hygiene.py"
    if not hygiene.is_file():
        raise SystemExit("evidence_hygiene_script_missing")

    with tempfile.TemporaryDirectory() as directory:
        report_path = Path(directory) / "git-audit.json"
        command = [
            sys.executable, str(hygiene), "audit-git",
            "--repo", str(repo),
            "--base", args.base,
            "--head", head,
            "--report", str(report_path),
        ]
        for forbidden in args.forbid_ancestor:
            command.extend(["--forbid-ancestor", forbidden])
        for secret_file in args.secret_file:
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

    result = {
        "attestation_version": 1,
        "audited_at_utc": dt.datetime.now(
            dt.timezone.utc
        ).isoformat(),
        "repo": str(repo),
        "branch": branch,
        "base": args.base,
        "audited_head": head,
        "remote_head": remote_head,
        "worktree_clean": True,
        "forbidden_ancestors": args.forbid_ancestor,
        "secret_file_count": len(args.secret_file),
        "audit": audit,
        "pass": (
            audit.get("pass") is True
            and audit.get("finding_count") == 0
            and audit.get("head") == head
        ),
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
