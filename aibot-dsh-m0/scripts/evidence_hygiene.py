#!/usr/bin/env python3
"""Generate, sanitize, and audit MC-bot evidence without exposing runtime credentials.

This module deliberately never prints secret values. It supports four subcommands:

  generate-token  Create a new URL-safe control token in a local file.
  sanitize-tree   Copy a raw evidence tree into a text-only, recursively redacted tree.
  audit-tree      Fail if a tree contains credentials or prohibited binaries.
  audit-git       Scan every changed blob in a Git range, including intermediate commits.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets as secrets_module
import stat
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

REDACTED = "<REDACTED>"
MAX_TEXT_BYTES = 25 * 1024 * 1024

BINARY_SUFFIXES = {
    ".7z", ".bin", ".class", ".dll", ".dylib", ".exe", ".gif", ".gz",
    ".ico", ".jar", ".jpeg", ".jpg", ".mp3", ".mp4", ".pdb", ".png",
    ".pyc", ".rar", ".so", ".tar", ".webm", ".webp", ".zip",
}
SENSITIVE_EXACT_KEYS = {
    "authorization", "client_secret", "cookie", "credential", "credentials",
    "password", "refresh_token", "secret", "token", "xsts_token",
}
SAFE_TOKENISH_KEYS = {
    "body_session_epoch", "command_seq", "game_session",
    "game_session_seq", "screen_epoch", "session_epoch",
}
ASSIGNMENT_SCAN_SUFFIXES = {
    ".bat", ".cmd", ".env", ".json", ".jsonl", ".log", ".md",
    ".properties", ".ps1", ".toml", ".txt", ".yaml", ".yml",
}

ENV_TOKEN_RE = re.compile(
    r"(?i)(\bAIBOT_REAL_CLIENT_TOKEN\s*[=:]\s*)([A-Za-z0-9_-]{16,})"
)
JSON_TOKEN_RE = re.compile(
    r'''(?ix)
    (
      ["']?
      (?:access_token|refresh_token|client_secret|authorization|password|secret|token)
      ["']?
      \s*[:=]\s*
      ["']
    )
    ([^"'\r\n]+)
    (["'])
    '''
)
BEARER_RE = re.compile(r"(?i)(\bAuthorization\s*:\s*(?:Bearer\s+)?)(\S+)")
QUERY_TOKEN_RE = re.compile(
    r"(?i)([?&](?:access_token|refresh_token|token|secret)=)([^&#\s]+)"
)


@dataclass(frozen=True)
class Finding:
    location: str
    kind: str
    detail: str


def secret_fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def is_sensitive_key(key: str) -> bool:
    normalized = key.strip().lower().replace("-", "_")
    if normalized in SAFE_TOKENISH_KEYS:
        return False
    if normalized in SENSITIVE_EXACT_KEYS:
        return True
    return (
        normalized.endswith("_token")
        or normalized.endswith("_secret")
        or normalized.endswith("_password")
        or normalized.endswith("_credential")
    )


def redact_string(value: str, known_secrets: Iterable[str]) -> str:
    result = value
    for secret in known_secrets:
        if secret:
            result = result.replace(secret, REDACTED)
    result = ENV_TOKEN_RE.sub(lambda match: match.group(1) + REDACTED, result)
    result = JSON_TOKEN_RE.sub(
        lambda match: match.group(1) + REDACTED + match.group(3), result
    )
    result = BEARER_RE.sub(lambda match: match.group(1) + REDACTED, result)
    result = QUERY_TOKEN_RE.sub(lambda match: match.group(1) + REDACTED, result)
    return result


def redact_json(value: Any, known_secrets: Iterable[str] = ()) -> Any:
    if isinstance(value, dict):
        redacted: dict[str, Any] = {}
        for key, item in value.items():
            if is_sensitive_key(str(key)):
                redacted[str(key)] = REDACTED
            else:
                redacted[str(key)] = redact_json(item, known_secrets)
        return redacted
    if isinstance(value, list):
        return [redact_json(item, known_secrets) for item in value]
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith(("{", "[")):
            try:
                nested = json.loads(value)
            except json.JSONDecodeError:
                pass
            else:
                return json.dumps(
                    redact_json(nested, known_secrets),
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
        return redact_string(value, known_secrets)
    return value


def load_known_secrets(
    env_names: Iterable[str], files: Iterable[Path]
) -> list[str]:
    values: list[str] = []
    for name in env_names:
        value = os.environ.get(name, "").strip()
        if value:
            values.append(value)
    for path in files:
        value = path.read_text(encoding="utf-8").strip()
        if value:
            values.append(value)
    return sorted(set(values), key=len, reverse=True)


def generate_token(out: Path, byte_count: int = 48) -> dict[str, Any]:
    if byte_count < 32 or byte_count > 96:
        raise ValueError("byte_count_must_be_32_to_96")
    if out.exists():
        raise FileExistsError(f"refusing_to_overwrite:{out}")
    out.parent.mkdir(parents=True, exist_ok=True)
    token = secrets_module.token_urlsafe(byte_count)
    if not re.fullmatch(r"[A-Za-z0-9_-]{32,256}", token):
        raise AssertionError("generated_token_format_invalid")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(out, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(token + "\n")
    except Exception:
        out.unlink(missing_ok=True)
        raise
    try:
        os.chmod(out, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
    return {
        "path": str(out),
        "length": len(token),
        "sha256_fingerprint": secret_fingerprint(token),
    }


def _is_binary_path(path: Path) -> bool:
    return path.suffix.lower() in BINARY_SUFFIXES


def _sanitize_text_file(
    source: Path, destination: Path, known_secrets: list[str]
) -> tuple[int, str]:
    raw = source.read_bytes()
    if len(raw) > MAX_TEXT_BYTES:
        raise ValueError("text_file_too_large")
    text = raw.decode("utf-8-sig")
    suffix = source.suffix.lower()
    original = text

    if suffix == ".json":
        value = json.loads(text)
        sanitized = json.dumps(
            redact_json(value, known_secrets),
            ensure_ascii=False,
            indent=2,
        ) + "\n"
        mode = "json"
    elif suffix == ".jsonl":
        lines: list[str] = []
        for line in text.splitlines():
            if not line.strip():
                lines.append("")
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                lines.append(redact_string(line, known_secrets))
            else:
                lines.append(json.dumps(
                    redact_json(value, known_secrets),
                    ensure_ascii=False,
                    separators=(",", ":"),
                ))
        sanitized = "\n".join(lines) + ("\n" if text.endswith("\n") else "")
        mode = "jsonl"
    else:
        sanitized = redact_string(text, known_secrets)
        mode = "text"

    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(sanitized, encoding="utf-8", newline="\n")
    count = sanitized.count(REDACTED) if sanitized != original else 0
    return count, mode


def sanitize_tree(
    source: Path,
    destination: Path,
    known_secrets: list[str],
) -> dict[str, Any]:
    source = source.resolve()
    destination = destination.resolve()
    if not source.is_dir():
        raise ValueError("source_must_be_directory")
    if destination.exists():
        raise FileExistsError("destination_must_not_exist")
    try:
        destination.relative_to(source)
    except ValueError:
        pass
    else:
        raise ValueError("destination_must_not_be_inside_source")

    copied: list[dict[str, Any]] = []
    skipped: list[dict[str, str]] = []
    destination.mkdir(parents=True)

    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        if ".git" in relative.parts:
            continue
        if path.is_symlink():
            skipped.append({"path": relative.as_posix(), "reason": "symlink"})
            continue
        if path.is_dir():
            continue
        if _is_binary_path(path):
            skipped.append({
                "path": relative.as_posix(),
                "reason": "prohibited_binary",
            })
            continue
        target = destination / relative
        try:
            redaction_count, mode = _sanitize_text_file(
                path, target, known_secrets
            )
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            skipped.append({
                "path": relative.as_posix(),
                "reason": f"non_text_or_invalid:{type(error).__name__}",
            })
            continue
        copied.append({
            "path": relative.as_posix(),
            "mode": mode,
            "redaction_markers": redaction_count,
        })

    report = {
        "source": str(source),
        "destination": str(destination),
        "copied_files": len(copied),
        "skipped_files": len(skipped),
        "copied": copied,
        "skipped": skipped,
    }
    (destination / "SANITIZATION_REPORT.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return report


def _find_sensitive_json(
    value: Any, location: str, findings: list[Finding]
) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            child = f"{location}.{key}"
            if is_sensitive_key(str(key)):
                if item != REDACTED:
                    findings.append(Finding(
                        child,
                        "unredacted_sensitive_key",
                        "sensitive value is not <REDACTED>",
                    ))
            else:
                _find_sensitive_json(item, child, findings)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _find_sensitive_json(item, f"{location}[{index}]", findings)


def _text_findings(
    text: str,
    location: str,
    known_secrets: list[str],
    scan_assignments: bool = True,
) -> list[Finding]:
    findings: list[Finding] = []
    for secret in known_secrets:
        if secret and secret in text:
            findings.append(Finding(
                location,
                "known_secret",
                f"sha256_fingerprint={secret_fingerprint(secret)}",
            ))
    if scan_assignments:
        for regex, kind in (
            (ENV_TOKEN_RE, "runtime_token_assignment"),
            (JSON_TOKEN_RE, "token_like_assignment"),
            (BEARER_RE, "authorization_header"),
            (QUERY_TOKEN_RE, "token_query_parameter"),
        ):
            for match in regex.finditer(text):
                if REDACTED not in match.group(0):
                    findings.append(Finding(
                        location,
                        kind,
                        f"offset={match.start()}",
                    ))
    return findings


def audit_tree(
    root: Path,
    known_secrets: list[str],
) -> dict[str, Any]:
    root = root.resolve()
    findings: list[Finding] = []
    scanned = 0
    if not root.is_dir():
        raise ValueError("audit_root_must_be_directory")

    for path in sorted(root.rglob("*")):
        if path.is_dir():
            continue
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            findings.append(Finding(relative, "symlink", "symlinks forbidden"))
            continue
        if _is_binary_path(path):
            findings.append(Finding(
                relative,
                "prohibited_binary",
                f"suffix={path.suffix.lower()}",
            ))
            continue
        raw = path.read_bytes()
        if len(raw) > MAX_TEXT_BYTES:
            findings.append(Finding(
                relative,
                "oversized_text",
                f"bytes={len(raw)}",
            ))
            continue
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            findings.append(Finding(
                relative,
                "non_utf8_blob",
                "evidence must be UTF-8 text",
            ))
            continue
        scanned += 1
        suffix = path.suffix.lower()
        findings.extend(_text_findings(
            text, relative, known_secrets,
            scan_assignments=suffix in ASSIGNMENT_SCAN_SUFFIXES,
        ))

        if suffix == ".json":
            try:
                value = json.loads(text)
            except json.JSONDecodeError as error:
                findings.append(Finding(
                    relative,
                    "invalid_json",
                    f"line={error.lineno}",
                ))
            else:
                _find_sensitive_json(value, relative, findings)
        elif suffix == ".jsonl":
            for line_number, line in enumerate(text.splitlines(), 1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError:
                    continue
                _find_sensitive_json(
                    value, f"{relative}:{line_number}", findings
                )

    return {
        "root": str(root),
        "scanned_text_files": scanned,
        "finding_count": len(findings),
        "findings": [finding.__dict__ for finding in findings],
        "pass": not findings,
    }


def _git(repo: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=check,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def _decode_git_blob(data: bytes) -> str | None:
    if len(data) > MAX_TEXT_BYTES:
        return None
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return None


def audit_git_range(
    repo: Path,
    base: str,
    head: str,
    known_secrets: list[str],
    forbidden_ancestors: list[str],
) -> dict[str, Any]:
    repo = repo.resolve()
    findings: list[Finding] = []

    if _git(repo, "merge-base", "--is-ancestor", base, head, check=False).returncode != 0:
        findings.append(Finding(
            f"{base}..{head}",
            "base_not_ancestor",
            "clean branch does not descend from required base",
        ))

    for forbidden in forbidden_ancestors:
        if _git(
            repo, "merge-base", "--is-ancestor", forbidden, head, check=False
        ).returncode == 0:
            findings.append(Finding(
                head,
                "forbidden_ancestor",
                f"contains_compromised_commit={forbidden}",
            ))

    commits_output = _git(
        repo, "rev-list", "--reverse", f"{base}..{head}"
    ).stdout.decode("ascii").splitlines()

    scanned_blobs = 0
    for commit in commits_output:
        paths = _git(
            repo, "diff-tree", "--root", "--no-commit-id",
            "--name-only", "-r", commit
        ).stdout.decode("utf-8", errors="replace").splitlines()
        for path_text in paths:
            path = Path(path_text)
            location = f"{commit}:{path_text}"
            if _is_binary_path(path):
                findings.append(Finding(
                    location,
                    "prohibited_binary_in_history",
                    f"suffix={path.suffix.lower()}",
                ))
                continue
            show = _git(
                repo, "show", f"{commit}:{path_text}", check=False
            )
            if show.returncode != 0:
                continue
            text = _decode_git_blob(show.stdout)
            if text is None:
                findings.append(Finding(
                    location,
                    "non_utf8_or_oversized_blob",
                    "clean evidence history must be bounded UTF-8 text",
                ))
                continue
            scanned_blobs += 1
            suffix = path.suffix.lower()
            findings.extend(_text_findings(
                text, location, known_secrets,
                scan_assignments=suffix in ASSIGNMENT_SCAN_SUFFIXES,
            ))
            if suffix == ".json":
                try:
                    value = json.loads(text)
                except json.JSONDecodeError:
                    pass
                else:
                    _find_sensitive_json(value, location, findings)
            elif suffix == ".jsonl":
                for line_number, line in enumerate(text.splitlines(), 1):
                    if not line.strip():
                        continue
                    try:
                        value = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    _find_sensitive_json(
                        value, f"{location}:{line_number}", findings
                    )

    return {
        "repo": str(repo),
        "base": base,
        "head": head,
        "commit_count": len(commits_output),
        "scanned_text_blobs": scanned_blobs,
        "finding_count": len(findings),
        "findings": [finding.__dict__ for finding in findings],
        "pass": not findings,
    }


def _write_report(report: dict[str, Any], output: Path | None) -> None:
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if output:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


def _secret_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--secret-env",
        action="append",
        default=[],
        help="Environment variable containing a secret to redact/audit.",
    )
    parser.add_argument(
        "--secret-file",
        action="append",
        default=[],
        type=Path,
        help="Local untracked file containing a secret. Its value is never printed.",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    generate = subparsers.add_parser("generate-token")
    generate.add_argument("--out", required=True, type=Path)
    generate.add_argument("--bytes", type=int, default=48)
    generate.add_argument("--report", type=Path)

    sanitize = subparsers.add_parser("sanitize-tree")
    sanitize.add_argument("--source", required=True, type=Path)
    sanitize.add_argument("--destination", required=True, type=Path)
    sanitize.add_argument("--report", type=Path)
    _secret_arguments(sanitize)

    audit = subparsers.add_parser("audit-tree")
    audit.add_argument("--root", required=True, type=Path)
    audit.add_argument("--report", type=Path)
    _secret_arguments(audit)

    git_audit = subparsers.add_parser("audit-git")
    git_audit.add_argument("--repo", required=True, type=Path)
    git_audit.add_argument("--base", required=True)
    git_audit.add_argument("--head", default="HEAD")
    git_audit.add_argument("--forbid-ancestor", action="append", default=[])
    git_audit.add_argument("--report", type=Path)
    _secret_arguments(git_audit)

    args = parser.parse_args()

    if args.command == "generate-token":
        report = generate_token(args.out, args.bytes)
        _write_report(report, args.report)
        return 0

    known = load_known_secrets(
        args.secret_env, args.secret_file
    )
    if args.command == "sanitize-tree":
        report = sanitize_tree(
            args.source, args.destination, known
        )
        _write_report(report, args.report)
        return 0
    if args.command == "audit-tree":
        report = audit_tree(args.root, known)
        _write_report(report, args.report)
        return 0 if report["pass"] else 1
    if args.command == "audit-git":
        report = audit_git_range(
            args.repo, args.base, args.head,
            known, args.forbid_ancestor
        )
        _write_report(report, args.report)
        return 0 if report["pass"] else 1
    raise AssertionError(args.command)


if __name__ == "__main__":
    raise SystemExit(main())
