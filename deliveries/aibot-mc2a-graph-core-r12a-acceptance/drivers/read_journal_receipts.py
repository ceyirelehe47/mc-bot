#!/usr/bin/env python3
"""Print opportunity lifecycle receipts from an offline BridgeJournal."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import r12a_common as rc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("journal", type=Path)
    parser.add_argument("--opportunity-id", default="")
    args = parser.parse_args()

    frames = rc.read_journal(args.journal)
    for frame in frames:
        fields = frame["fields"]
        if fields.get("kind") not in {
            "resource_opportunity_birth",
            "resource_opportunity_consumed",
            "resource_opportunity_stale",
        }:
            continue
        if args.opportunity_id and fields.get("opportunity_id") != args.opportunity_id:
            continue
        print(json.dumps(frame, ensure_ascii=False))


if __name__ == "__main__":
    main()
