#!/usr/bin/env python3
"""Validate connected -> disconnected -> reconnected Tom's Storage lifecycle evidence."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def integer(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError("boolean_is_not_integer")
    return int(value)


def selected_tool_retained(phase: dict[str, Any]) -> bool:
    """Require an explicit before/after snapshot of the selected hotbar tool.

    A prose or boolean claim is deliberately insufficient.  The evidence must
    show that the same non-air item, count, and selected slot survived the
    deposit unchanged.
    """
    snapshot = phase.get("selected_tool")
    if not isinstance(snapshot, dict):
        return False
    try:
        slot_before = integer(snapshot["slot_before"])
        slot_after = integer(snapshot["slot_after"])
        count_before = integer(snapshot["count_before"])
        count_after = integer(snapshot["count_after"])
    except (KeyError, TypeError, ValueError):
        return False

    item_before = str(snapshot.get("item_before", "")).strip()
    item_after = str(snapshot.get("item_after", "")).strip()
    return (
        0 <= slot_before <= 8
        and slot_before == slot_after
        and item_before not in {"", "minecraft:air"}
        and item_before == item_after
        and count_before > 0
        and count_before == count_after
    )


def validate(data: dict[str, Any]) -> dict[str, Any]:
    baseline = data["baseline_connected"]
    disconnected = data["disconnected"]
    reconnected = data["reconnected"]

    fixture_id = str(data["fixture_id"])
    terminal = str(data["terminal_pos"])
    link = str(data["link_pos"])
    original_link_state = str(data["original_link_state"])

    base_n = integer(baseline["transfer_count"])
    reconnect_n = integer(reconnected["transfer_count"])

    checks = {
        "fixture_id_nonempty": bool(fixture_id),
        "terminal_stable_all_phases": (
            baseline["terminal_pos"] == terminal
            and disconnected["terminal_pos"] == terminal
            and reconnected["terminal_pos"] == terminal
        ),
        "link_stable_all_phases": (
            baseline["link_pos"] == link
            and disconnected["link_pos"] == link
            and reconnected["link_pos"] == link
        ),
        "only_link_changed": data.get("only_link_changed") is True,
        "terminal_never_replaced": data.get("terminal_replaced") is False,
        "baseline_link_connected": (
            baseline["link_state"] == original_link_state
        ),
        "baseline_completed": baseline["state"] == "completed",
        "baseline_toms_reason": (
            "toms_storage_terminal_transfer_verified:"
            in str(baseline["reason"])
        ),
        "baseline_positive_transfer": (
            base_n > 0
            and integer(baseline["player_delta"]) == -base_n
            and integer(baseline["network_delta"]) == base_n
        ),
        "baseline_selected_tool_retained": selected_tool_retained(baseline),
        "disconnect_after_new_server_start": (
            baseline["server_start_id"]
            != disconnected["server_start_id"]
        ),
        "disconnect_link_air": disconnected["link_state"] == "minecraft:air",
        "disconnect_observed_5s": (
            integer(disconnected["observation_ms"]) >= 5000
        ),
        "disconnect_not_completed": disconnected["state"] != "completed",
        "disconnect_player_zero": integer(
            disconnected["player_delta"]
        ) == 0,
        "disconnect_network_zero": integer(
            disconnected["network_delta"]
        ) == 0,
        "disconnect_other_storage_zero": integer(
            disconnected["other_storage_delta"]
        ) == 0,
        "reconnect_after_another_server_start": (
            disconnected["server_start_id"]
            != reconnected["server_start_id"]
        ),
        "reconnect_link_restored": (
            reconnected["link_state"] == original_link_state
        ),
        "reconnect_completed": reconnected["state"] == "completed",
        "reconnect_toms_reason": (
            "toms_storage_terminal_transfer_verified:"
            in str(reconnected["reason"])
        ),
        "reconnect_positive_transfer": (
            reconnect_n > 0
            and integer(reconnected["player_delta"]) == -reconnect_n
            and integer(reconnected["network_delta"]) == reconnect_n
        ),
        "reconnect_selected_tool_retained": selected_tool_retained(reconnected),
        "same_world_lineage": (
            baseline["world_lineage_id"]
            == disconnected["world_lineage_id"]
            == reconnected["world_lineage_id"]
        ),
    }

    return {
        "fixture_id": fixture_id,
        "terminal_pos": terminal,
        "link_pos": link,
        "checks": checks,
        "failed": [name for name, value in checks.items() if not value],
        "pass": all(checks.values()),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    data = json.loads(args.evidence.read_text(encoding="utf-8"))
    result = validate(data)
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")
    if not result["pass"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
