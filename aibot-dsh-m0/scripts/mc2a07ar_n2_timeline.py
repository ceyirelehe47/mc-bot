#!/usr/bin/env python3
"""Validate the exact N2 target-replacement/cancel/release timeline."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

MUTATION_REASONS = {
    "client_owned_screen_quick_move",
    "client_owned_screen_settling",
    "client_owned_screen_quick_move_finished",
}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), 1
    ):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(
                f"invalid_jsonl_line:{line_number}:{error.msg}"
            ) from error
        if not isinstance(row, dict):
            raise ValueError(f"jsonl_row_not_object:{line_number}")
        rows.append(row)
    return rows


def inner_phase(row: dict[str, Any]) -> str:
    if row.get("phase"):
        return str(row["phase"])
    message = row.get("message") or {}
    try:
        return json.loads(
            message.get("arguments_json", "{}")
        ).get("phase", "")
    except (TypeError, json.JSONDecodeError):
        return ""


def first_row(
    rows: list[dict[str, Any]],
    predicate,
    description: str,
) -> dict[str, Any]:
    for row in rows:
        if predicate(row):
            return row
    raise ValueError(f"missing_proxy_event:{description}")


def delta(data: dict[str, Any], before: str, after: str) -> int:
    return int(data[after]) - int(data[before])


def validate(
    rows: list[dict[str, Any]],
    summary: dict[str, Any],
    execution: str,
) -> dict[str, Any]:
    held = first_row(
        rows,
        lambda row: (
            row.get("direction") == "server_to_client"
            and row.get("action") == "held"
            and row.get("execution_id") == execution
            and row.get("operation") == "deposit"
            and inner_phase(row) == "commit"
        ),
        "held_commit",
    )
    frame_id = held.get("frame_id", "")
    if not frame_id:
        raise ValueError("held_commit_missing_frame_id")

    cancel = first_row(
        rows,
        lambda row: (
            row.get("direction") == "server_to_client"
            and row.get("action") == "forwarded"
            and row.get("execution_id") == execution
            and (row.get("message") or {}).get("type") == "control"
            and (row.get("message") or {}).get("action") == "cancel"
            and (row.get("message") or {}).get("reason")
                == "real_client_deposit_target_changed"
        ),
        "target_changed_cancel",
    )
    released = first_row(
        rows,
        lambda row: (
            row.get("direction") == "server_to_client"
            and str(row.get("action", "")).startswith("released")
            and row.get("frame_id") == frame_id
        ),
        "released_original_commit",
    )

    replacement_ms = int(summary["target_replacement_epoch_ms"])
    screen_closed_ms = int(summary["screen_closed_epoch_ms"])
    terminal_ms = int(summary["execution_terminal_epoch_ms"])
    held_ms = int(held["epoch_ms"])
    cancel_ms = int(cancel["epoch_ms"])
    release_ms = int(released["epoch_ms"])

    mutation_acks = [
        row for row in rows
        if row.get("direction") == "client_to_server"
        and row.get("execution_id") == execution
        and (row.get("message") or {}).get("type") == "execution"
        and (row.get("message") or {}).get("reason") in MUTATION_REASONS
        and replacement_ms <= int(row.get("epoch_ms", 0)) <= terminal_ms
    ]

    inventory = summary["inventory"]
    checks = {
        "same_execution": summary.get("execution_id") == execution,
        "held_before_replacement": held_ms < replacement_ms,
        "replacement_before_cancel": replacement_ms < cancel_ms,
        "replacement_before_terminal": replacement_ms < terminal_ms,
        "cancel_before_or_at_screen_close": cancel_ms <= screen_closed_ms,
        "screen_closed_before_release": screen_closed_ms < release_ms,
        "terminal_before_release": terminal_ms < release_ms,
        "terminal_state_failed": summary.get("terminal_state") == "failed",
        "terminal_reason_target_changed": (
            summary.get("terminal_reason")
            == "real_client_deposit_target_changed"
        ),
        "ui_absent": summary.get("ui_present_after") is False,
        "player_zero_delta": delta(
            inventory, "player_before", "player_after"
        ) == 0,
        "old_target_zero_delta": delta(
            inventory, "old_target_before", "old_target_after"
        ) == 0,
        "new_target_zero_delta": delta(
            inventory, "new_target_before", "new_target_after"
        ) == 0,
        "summary_click_ack_zero": int(
            summary.get("click_ack_count", -1)
        ) == 0,
        "proxy_mutation_ack_zero": len(mutation_acks) == 0,
        "cancel_was_not_blocked_by_held_commit": cancel_ms < release_ms,
    }

    return {
        "execution_id": execution,
        "frame_id": frame_id,
        "timeline": {
            "commit_held_epoch_ms": held_ms,
            "target_replacement_epoch_ms": replacement_ms,
            "cancel_control_epoch_ms": cancel_ms,
            "screen_closed_epoch_ms": screen_closed_ms,
            "execution_terminal_epoch_ms": terminal_ms,
            "old_commit_release_epoch_ms": release_ms,
        },
        "mutation_ack_rows": len(mutation_acks),
        "checks": checks,
        "failed": [name for name, value in checks.items() if not value],
        "pass": all(checks.values()),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--proxy-log", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--execution-id", required=True)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    result = validate(
        load_jsonl(args.proxy_log),
        json.loads(args.summary.read_text(encoding="utf-8")),
        args.execution_id,
    )
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
