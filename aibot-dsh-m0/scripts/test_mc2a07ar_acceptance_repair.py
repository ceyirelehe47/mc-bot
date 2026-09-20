#!/usr/bin/env python3
from __future__ import annotations

import importlib
import json
import pathlib
import sys
import tempfile
import threading
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

# Package-side tests use a tiny local stand-in. In the repository, the real hygiene module exists.
try:
    import evidence_hygiene  # type: ignore
except ModuleNotFoundError:
    class _Hygiene:
        @staticmethod
        def redact_json(value):
            if isinstance(value, dict):
                return {
                    key: (
                        "<REDACTED>"
                        if str(key).lower() == "token"
                        else _Hygiene.redact_json(item)
                    )
                    for key, item in value.items()
                }
            if isinstance(value, list):
                return [_Hygiene.redact_json(item) for item in value]
            return value
    sys.modules["evidence_hygiene"] = _Hygiene()

proxy = importlib.import_module("redacting_wire_proxy")
n2 = importlib.import_module("mc2a07ar_n2_timeline")
reconnect = importlib.import_module("mc2a07ar_toms_reconnect")
final_audit = importlib.import_module("mc2a07ar_external_final_audit")


def commit_message(execution: str) -> dict:
    return {
        "type": "command",
        "operation": "deposit",
        "execution_id": execution,
        "command_seq": 3,
        "arguments_json": json.dumps({
            "phase": "commit",
            "screen_epoch": "screen-a",
        }),
    }


class AcceptanceRepairTest(unittest.TestCase):
    def test_held_commit_does_not_block_later_cancel(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            recorder = proxy.TraceRecorder(base / "trace.jsonl")
            release = base / "release.flag"
            stop = threading.Event()
            written: list[bytes] = []
            gate = proxy.HeldFrameGate(
                release_file=release,
                recorder=recorder,
                writer=written.append,
                stop=stop,
                hold_operation="deposit",
                hold_phase="commit",
                hold_execution_id="exec-a",
                hold_limit=1,
                duplicate_count=1,
                command_seq_delta=0,
                drop_inner_field="",
            )
            self.assertTrue(gate.hold(
                "server_to_client", commit_message("exec-a")
            ))
            cancel = {
                "type": "control",
                "execution_id": "exec-a",
                "action": "cancel",
                "reason": "real_client_deposit_target_changed",
            }
            # This models the still-running reader thread forwarding the later cancel.
            written.append(proxy.encode_frame(cancel))
            self.assertEqual(
                json.loads(written[0].decode())["type"], "control"
            )
            release.write_text("release\n", encoding="utf-8")
            self.assertEqual(gate.release_ready(), 1)
            self.assertEqual(
                json.loads(written[1].decode())["operation"], "deposit"
            )

    def test_held_trace_uses_same_frame_id_on_release(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            recorder = proxy.TraceRecorder(base / "trace.jsonl")
            release = base / "release.flag"
            gate = proxy.HeldFrameGate(
                release_file=release,
                recorder=recorder,
                writer=lambda body: None,
                stop=threading.Event(),
                hold_operation="deposit",
                hold_phase="commit",
                hold_execution_id="",
                hold_limit=1,
                duplicate_count=1,
                command_seq_delta=0,
                drop_inner_field="",
            )
            gate.hold("server_to_client", commit_message("exec-a"))
            release.write_text("yes\n", encoding="utf-8")
            gate.release_ready()
            rows = [
                json.loads(line)
                for line in (base / "trace.jsonl")
                    .read_text(encoding="utf-8").splitlines()
            ]
            self.assertEqual(rows[0]["action"], "held")
            self.assertEqual(rows[1]["action"], "released")
            self.assertEqual(rows[0]["frame_id"], rows[1]["frame_id"])

    def test_proxy_log_redacts_hello_token(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "trace.jsonl"
            recorder = proxy.TraceRecorder(path)
            recorder.record(
                "client_to_server",
                {"type": "hello", "token": "raw-secret"},
                "forwarded",
            )
            row = json.loads(path.read_text(encoding="utf-8"))
            self.assertNotEqual(row["message"]["token"], "raw-secret")

    def test_n2_validator_accepts_exact_order(self):
        execution = "exec-a"
        rows = [
            {
                "epoch_ms": 100,
                "direction": "server_to_client",
                "action": "held",
                "frame_id": "held-1",
                "execution_id": execution,
                "operation": "deposit",
                "phase": "commit",
                "message": commit_message(execution),
            },
            {
                "epoch_ms": 300,
                "direction": "server_to_client",
                "action": "forwarded",
                "frame_id": "forward-2",
                "execution_id": execution,
                "message": {
                    "type": "control",
                    "execution_id": execution,
                    "action": "cancel",
                    "reason": "real_client_deposit_target_changed",
                },
            },
            {
                "epoch_ms": 600,
                "direction": "server_to_client",
                "action": "released",
                "frame_id": "held-1",
                "execution_id": execution,
                "message": commit_message(execution),
            },
        ]
        summary = {
            "execution_id": execution,
            "target_replacement_epoch_ms": 200,
            "screen_closed_epoch_ms": 350,
            "execution_terminal_epoch_ms": 400,
            "terminal_state": "failed",
            "terminal_reason": "real_client_deposit_target_changed",
            "ui_present_after": False,
            "click_ack_count": 0,
            "inventory": {
                "player_before": 9, "player_after": 9,
                "old_target_before": 10, "old_target_after": 10,
                "new_target_before": 0, "new_target_after": 0,
            },
        }
        self.assertTrue(
            n2.validate(rows, summary, execution)["pass"]
        )

    def test_n2_validator_rejects_release_before_cancel(self):
        execution = "exec-a"
        rows = [
            {
                "epoch_ms": 100,
                "direction": "server_to_client",
                "action": "held",
                "frame_id": "held-1",
                "execution_id": execution,
                "operation": "deposit",
                "phase": "commit",
                "message": commit_message(execution),
            },
            {
                "epoch_ms": 250,
                "direction": "server_to_client",
                "action": "released",
                "frame_id": "held-1",
                "execution_id": execution,
                "message": commit_message(execution),
            },
            {
                "epoch_ms": 300,
                "direction": "server_to_client",
                "action": "forwarded",
                "frame_id": "forward-2",
                "execution_id": execution,
                "message": {
                    "type": "control",
                    "execution_id": execution,
                    "action": "cancel",
                    "reason": "real_client_deposit_target_changed",
                },
            },
        ]
        summary = {
            "execution_id": execution,
            "target_replacement_epoch_ms": 200,
            "screen_closed_epoch_ms": 350,
            "execution_terminal_epoch_ms": 400,
            "terminal_state": "failed",
            "terminal_reason": "real_client_deposit_target_changed",
            "ui_present_after": False,
            "click_ack_count": 0,
            "inventory": {
                "player_before": 9, "player_after": 9,
                "old_target_before": 10, "old_target_after": 10,
                "new_target_before": 0, "new_target_after": 0,
            },
        }
        self.assertFalse(
            n2.validate(rows, summary, execution)["pass"]
        )

    @staticmethod
    def selected_tool() -> dict:
        return {
            "slot_before": 0,
            "slot_after": 0,
            "item_before": "minecraft:diamond_pickaxe",
            "item_after": "minecraft:diamond_pickaxe",
            "count_before": 1,
            "count_after": 1,
        }

    def reconnect_evidence(self) -> dict:
        common = {
            "terminal_pos": "10 64 10",
            "link_pos": "11 64 10",
            "world_lineage_id": "world-copy-a",
        }
        return {
            "fixture_id": "toms-cycle-a",
            "terminal_pos": common["terminal_pos"],
            "link_pos": common["link_pos"],
            "original_link_state": "toms_storage:inventory_cable",
            "only_link_changed": True,
            "terminal_replaced": False,
            "baseline_connected": {
                **common,
                "server_start_id": "start-1",
                "link_state": "toms_storage:inventory_cable",
                "state": "completed",
                "reason": (
                    "server_authoritative_owned_screen_"
                    "toms_storage_terminal_transfer_verified:8"
                ),
                "transfer_count": 8,
                "player_delta": -8,
                "network_delta": 8,
                "selected_tool": self.selected_tool(),
            },
            "disconnected": {
                **common,
                "server_start_id": "start-2",
                "link_state": "minecraft:air",
                "observation_ms": 6200,
                "state": "cancelled",
                "player_delta": 0,
                "network_delta": 0,
                "other_storage_delta": 0,
            },
            "reconnected": {
                **common,
                "server_start_id": "start-3",
                "link_state": "toms_storage:inventory_cable",
                "state": "completed",
                "reason": (
                    "server_authoritative_owned_screen_"
                    "toms_storage_terminal_transfer_verified:8"
                ),
                "transfer_count": 8,
                "player_delta": -8,
                "network_delta": 8,
                "selected_tool": self.selected_tool(),
            },
        }

    def test_reconnect_validator_accepts_full_cycle(self):
        self.assertTrue(
            reconnect.validate(self.reconnect_evidence())["pass"]
        )

    def test_reconnect_validator_rejects_barrel_substitute(self):
        data = self.reconnect_evidence()
        data["reconnected"]["reason"] = (
            "server_authoritative_owned_screen_"
            "vanilla_barrel_transfer_verified:8"
        )
        self.assertFalse(reconnect.validate(data)["pass"])

    def test_reconnect_validator_rejects_terminal_replacement(self):
        data = self.reconnect_evidence()
        data["terminal_replaced"] = True
        self.assertFalse(reconnect.validate(data)["pass"])

    def test_reconnect_validator_rejects_missing_selected_tool_snapshot(self):
        data = self.reconnect_evidence()
        del data["reconnected"]["selected_tool"]
        result = reconnect.validate(data)
        self.assertFalse(result["pass"])
        self.assertIn("reconnect_selected_tool_retained", result["failed"])

    def test_reconnect_validator_rejects_changed_selected_tool_item(self):
        data = self.reconnect_evidence()
        data["baseline_connected"]["selected_tool"]["item_after"] = (
            "minecraft:iron_pickaxe"
        )
        result = reconnect.validate(data)
        self.assertFalse(result["pass"])
        self.assertIn("baseline_selected_tool_retained", result["failed"])

    def test_reconnect_validator_rejects_selected_slot_change(self):
        data = self.reconnect_evidence()
        data["reconnected"]["selected_tool"]["slot_after"] = 1
        self.assertFalse(reconnect.validate(data)["pass"])

    def test_external_attestation_must_be_outside_repo(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            self.assertTrue(
                final_audit.inside(repo / "audit.json", repo)
            )
            self.assertFalse(
                final_audit.inside(
                    repo.parent / "audit.json", repo
                )
            )

    def test_external_secret_files_require_exactly_two(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            with self.assertRaisesRegex(
                ValueError, "exactly_two_secret_files_required"
            ):
                final_audit.resolve_secret_files(repo, [])

    def test_external_secret_files_resolve_relative_and_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            secrets_dir = repo / ".secrets"
            secrets_dir.mkdir()
            old = secrets_dir / "old.token"
            new = secrets_dir / "new.token"
            old.write_text("old-secret-value\n", encoding="utf-8")
            new.write_text("new-secret-value\n", encoding="utf-8")
            paths, fingerprints = final_audit.resolve_secret_files(
                repo,
                [pathlib.Path(".secrets/old.token"), pathlib.Path(".secrets/new.token")],
            )
            self.assertEqual(paths, [old.resolve(), new.resolve()])
            self.assertEqual(len(fingerprints), 2)
            self.assertEqual(len(set(fingerprints)), 2)

    def test_external_secret_files_reject_duplicate_values(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            first = repo / "first.token"
            second = repo / "second.token"
            first.write_text("same-value\n", encoding="utf-8")
            second.write_text("same-value\n", encoding="utf-8")
            with self.assertRaisesRegex(
                ValueError, "secret_file_values_must_be_distinct"
            ):
                final_audit.resolve_secret_files(repo, [first, second])

    def test_external_attestation_checks_require_stable_exact_audit(self):
        audit = {
            "pass": True,
            "finding_count": 0,
            "base": "base-a",
            "head": "head-a",
            "commit_count": 4,
        }
        checks = final_audit.attestation_checks(
            audit=audit,
            base="base-a",
            head_before="head-a",
            head_after="head-a",
            remote_before="head-a",
            remote_after="head-a",
            status_before="",
            status_after="",
            secret_file_count=2,
            secret_fingerprints=["fingerprint-a", "fingerprint-b"],
            forbidden_ancestors=["bad-a"],
            expected_commit_count=4,
        )
        self.assertTrue(all(checks.values()))
        checks["remote_head_stable"] = False
        self.assertFalse(all(checks.values()))

    def test_proxy_duplicate_bound_is_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            with self.assertRaisesRegex(
                ValueError, "duplicate_count"
            ):
                proxy.HeldFrameGate(
                    release_file=base / "release",
                    recorder=proxy.TraceRecorder(base / "trace"),
                    writer=lambda body: None,
                    stop=threading.Event(),
                    hold_operation="deposit",
                    hold_phase="commit",
                    hold_execution_id="",
                    hold_limit=1,
                    duplicate_count=9,
                    command_seq_delta=0,
                    drop_inner_field="",
                )


if __name__ == "__main__":
    unittest.main()
