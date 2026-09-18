#!/usr/bin/env python3
"""Verify the final two-parent Graph Core freeze merge without trusting its commit message."""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=repo,
        text=True,
        capture_output=True,
        check=check,
    )


def is_ancestor(repo: Path, ancestor: str, descendant: str) -> bool:
    result = git(repo, "merge-base", "--is-ancestor", ancestor, descendant, check=False)
    return result.returncode == 0


def diff_quiet(repo: Path, left: str, right: str, pathspec: str) -> bool:
    result = git(
        repo,
        "diff",
        "--quiet",
        left,
        right,
        "--",
        pathspec,
        check=False,
    )
    return result.returncode == 0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--acceptance-sha", required=True)
    parser.add_argument(
        "--r11-reviewed",
        default="e300f09fe20dfe943a1ee332b591c7420654f8c7",
    )
    parser.add_argument(
        "--r12-evidence",
        default="cc5517e2812b85f71f1cb12ac70be7caec0bd937",
    )
    parser.add_argument(
        "--production",
        default="f70c9c2cd3eae552af0ee7e5f12b3d0efd728901",
    )
    args = parser.parse_args()

    repo = args.repo.resolve()
    head = git(repo, "rev-parse", "HEAD").stdout.strip()
    parents = git(repo, "show", "-s", "--format=%P", head).stdout.strip().split()
    if len(parents) != 2:
        raise SystemExit(f"freeze HEAD must be a two-parent merge, got {parents}")
    if parents[0] != args.acceptance_sha or parents[1] != args.r11_reviewed:
        raise SystemExit(
            "unexpected merge parents: "
            f"expected [{args.acceptance_sha}, {args.r11_reviewed}], got {parents}"
        )

    checks = {
        "r11_reviewed_is_ancestor": is_ancestor(repo, args.r11_reviewed, head),
        "r12_evidence_is_ancestor": is_ancestor(repo, args.r12_evidence, head),
        "acceptance_is_ancestor": is_ancestor(repo, args.acceptance_sha, head),
        "production_tree_unchanged": diff_quiet(
            repo, args.production, head, "aibot-dsh-m0"
        ),
        "acceptance_changed_no_production_tree": diff_quiet(
            repo, args.r12_evidence, args.acceptance_sha, "aibot-dsh-m0"
        ),
    }
    if not all(checks.values()):
        raise SystemExit(json.dumps(checks, indent=2))

    changed = git(
        repo,
        "diff",
        "--name-only",
        args.r12_evidence,
        args.acceptance_sha,
    ).stdout.splitlines()
    if not changed:
        raise SystemExit("acceptance commit contains no evidence")
    bad = [
        path for path in changed
        if not path.startswith("aibot-mc2a-graph-core-r12a-acceptance/")
    ]
    if bad:
        raise SystemExit(f"acceptance commit changed out-of-scope paths: {bad}")

    result = {
        "freeze_merge_sha": head,
        "parents": parents,
        "production_sha": args.production,
        "r11_reviewed_sha": args.r11_reviewed,
        "r12_evidence_sha": args.r12_evidence,
        "acceptance_sha": args.acceptance_sha,
        "checks": checks,
        "acceptance_changed_paths": changed,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
