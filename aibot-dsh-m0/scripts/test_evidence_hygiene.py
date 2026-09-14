#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import sys
import shutil
import subprocess
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import evidence_hygiene as MODULE


class EvidenceHygieneTest(unittest.TestCase):
    def test_nested_token_is_redacted(self):
        value = {
            "hello": {
                "token": "secret-value-1234567890",
                "session_epoch": "keep-me",
            }
        }
        result = MODULE.redact_json(value)
        self.assertEqual(
            result["hello"]["token"], MODULE.REDACTED
        )
        self.assertEqual(
            result["hello"]["session_epoch"], "keep-me"
        )

    def test_nested_arguments_json_is_redacted(self):
        value = {
            "arguments_json": json.dumps({
                "access_token": "abc-1234567890"
            })
        }
        result = MODULE.redact_json(value)
        nested = json.loads(result["arguments_json"])
        self.assertEqual(
            nested["access_token"], MODULE.REDACTED
        )

    def test_text_runtime_token_is_redacted(self):
        text = (
            "AIBOT_REAL_CLIENT_TOKEN="
            "abcdefghijklmnopqrstuvwxyz012345"
        )
        result = MODULE.redact_string(text, [])
        self.assertNotIn(
            "abcdefghijklmnopqrstuvwxyz012345", result
        )
        self.assertIn(MODULE.REDACTED, result)

    def test_sanitize_tree_skips_jar_and_redacts_jsonl(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            source = base / "raw"
            destination = base / "clean"
            source.mkdir()
            (source / "third-party.jar").write_bytes(b"PK\x03\x04")
            (source / "wire.jsonl").write_text(
                json.dumps({
                    "message": {
                        "token": "abc12345678901234567890",
                        "session_epoch": "epoch-a",
                    }
                }) + "\n",
                encoding="utf-8",
            )
            report = MODULE.sanitize_tree(
                source, destination, []
            )
            self.assertFalse(
                (destination / "third-party.jar").exists()
            )
            line = json.loads(
                (destination / "wire.jsonl")
                .read_text(encoding="utf-8")
            )
            self.assertEqual(
                line["message"]["token"], MODULE.REDACTED
            )
            self.assertEqual(
                line["message"]["session_epoch"], "epoch-a"
            )
            self.assertEqual(report["skipped_files"], 1)

    def test_audit_tree_rejects_unredacted_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "bad.json").write_text(
                json.dumps({"token": "not-redacted"}),
                encoding="utf-8",
            )
            report = MODULE.audit_tree(root, [])
            self.assertFalse(report["pass"])
            self.assertTrue(any(
                finding["kind"] == "unredacted_sensitive_key"
                for finding in report["findings"]
            ))

    def test_audit_tree_accepts_redacted_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "good.json").write_text(
                json.dumps({"token": MODULE.REDACTED}),
                encoding="utf-8",
            )
            report = MODULE.audit_tree(root, [])
            self.assertTrue(report["pass"], report)

    def test_known_secret_fingerprint_only(self):
        secret = "very-secret-value-1234567890"
        findings = MODULE._text_findings(
            secret, "file.txt", [secret]
        )
        self.assertEqual(len(findings), 1)
        self.assertNotIn(secret, findings[0].detail)
        self.assertIn(
            MODULE.secret_fingerprint(secret),
            findings[0].detail,
        )

    def test_generate_token_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "token.secret"
            report = MODULE.generate_token(path, 32)
            self.assertTrue(path.exists())
            self.assertGreaterEqual(report["length"], 32)
            with self.assertRaises(FileExistsError):
                MODULE.generate_token(path, 32)

    @unittest.skipUnless(
        shutil.which("git"), "git is required"
    )
    def test_git_audit_finds_binary_in_intermediate_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            subprocess.run(
                ["git", "init"], cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repo, check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"],
                cwd=repo, check=True,
            )
            (repo / "base.txt").write_text("base\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(
                ["git", "commit", "-m", "base"],
                cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            base = subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                cwd=repo, text=True,
            ).strip()
            (repo / "bad.jar").write_bytes(b"PK\x03\x04")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(
                ["git", "commit", "-m", "bad"],
                cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            (repo / "bad.jar").unlink()
            subprocess.run(["git", "add", "-u"], cwd=repo, check=True)
            subprocess.run(
                ["git", "commit", "-m", "remove"],
                cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            report = MODULE.audit_git_range(
                repo, base, "HEAD", [], []
            )
            self.assertFalse(report["pass"])
            self.assertTrue(any(
                finding["kind"] == "prohibited_binary_in_history"
                for finding in report["findings"]
            ))

    @unittest.skipUnless(
        shutil.which("git"), "git is required"
    )
    def test_git_audit_rejects_forbidden_ancestor(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            subprocess.run(
                ["git", "init"], cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repo, check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"],
                cwd=repo, check=True,
            )
            (repo / "base.txt").write_text("base\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(
                ["git", "commit", "-m", "base"],
                cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            base = subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                cwd=repo, text=True,
            ).strip()
            (repo / "next.txt").write_text("next\n")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(
                ["git", "commit", "-m", "next"],
                cwd=repo, check=True,
                stdout=subprocess.DEVNULL,
            )
            forbidden = subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                cwd=repo, text=True,
            ).strip()
            report = MODULE.audit_git_range(
                repo, base, "HEAD", [], [forbidden]
            )
            self.assertFalse(report["pass"])
            self.assertTrue(any(
                finding["kind"] == "forbidden_ancestor"
                for finding in report["findings"]
            ))


if __name__ == "__main__":
    unittest.main()
