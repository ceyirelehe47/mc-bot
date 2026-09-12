# -*- coding: utf-8 -*-
"""
LIVE-R12A-1 phase 1.

Creates opportunity A, writes its birth receipt, plans Graph(A), verifies READY, and stops.
This file deliberately contains no `/run-next` call.
"""
from __future__ import annotations

import json
import os
import shutil
import time
import urllib.parse
from pathlib import Path

import r12a_common as rc

ORE = tuple(map(int, os.environ.get("R12A_ORE", "566,68,127").split(",")))
STAND = (ORE[0] - 1, ORE[1], ORE[2])
FAR = (ORE[0] - 30, ORE[1], ORE[2])
PLAN_KEY = os.environ.get("R12A_PLAN_KEY", f"r12a-original-{ORE[0]}-{ORE[2]}")


def main() -> None:
    rc.ensure_dirs()
    log_path = rc.OUT / "phase1-server.log"
    before_path = rc.OUT / "semantic-before-A.json"
    with_a_path = rc.OUT / "semantic-with-A.json"
    graph_path = rc.OUT / "graph-before-rollback.json"

    process = rc.start_server(log_path)
    try:
        gl = rc.load_graph_live()

        print(rc.rcon(f"tp Bob {FAR[0]} {FAR[1]} {FAR[2]}"))
        print(rc.rcon(f"setblock {STAND[0]} {STAND[1]-1} {STAND[2]} stone"))
        print(rc.rcon(f"setblock {STAND[0]} {STAND[1]} {STAND[2]} air"))
        print(rc.rcon(f"setblock {STAND[0]} {STAND[1]+1} {STAND[2]} air"))
        print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
        print(rc.rcon("give Bob minecraft:iron_pickaxe"))
        time.sleep(3)

        rc.wait_quiet(rc.SEM)
        shutil.copyfile(rc.SEM, before_path)
        before = rc.read_json(before_path)
        assert not rc.opportunities_at(before, ORE), (
            "before-A snapshot already contains an opportunity at the fixture cell"
        )

        print(rc.rcon(f"tp Bob {STAND[0]} {STAND[1]} {STAND[2]}"))
        lease = gl.get_lease()
        gl.call("GET", "/v1/observe", lease)
        time.sleep(3)
        gl.call("GET", "/v1/observe", lease)
        time.sleep(3)

        rc.wait_quiet(rc.SEM)
        shutil.copyfile(rc.SEM, with_a_path)
        current = rc.read_json(with_a_path)
        matches = rc.opportunities_at(current, ORE)
        assert len(matches) == 1, f"expected exactly one opportunity, got {matches}"
        opportunity_id = matches[0]["id"]

        world_id = rc.WORLD_ID_FILE.read_text(encoding="utf-8").strip()
        ref = (
            f"mc://{world_id}/minecraft%3Aoverworld/"
            f"opportunity/{opportunity_id}"
        )
        encoded_ref = urllib.parse.quote(ref, safe="")
        planned = gl.call(
            "POST",
            f"/v1/graphs/opportunity?plan_key={PLAN_KEY}&ref={encoded_ref}",
            lease,
        )["data"]
        graph_id = planned["graph_id"]
        inspected = gl.call("GET", f"/v1/graphs/{graph_id}", lease)["data"]

        assert inspected["graph_id"] == graph_id
        assert inspected["state"] == "READY", inspected
        assert rc.graph_subject_ids(inspected) == [opportunity_id], inspected
        for node in inspected.get("nodes", []):
            assert not node.get("execution_id"), node
            assert node.get("state") == "READY", node

        rc.write_json(graph_path, inspected)
        rc.save_state(
            opportunity_id=opportunity_id,
            graph_id=graph_id,
            plan_key=PLAN_KEY,
            world_id=world_id,
            ore=list(ORE),
            stand=list(STAND),
            far=list(FAR),
            before_snapshot=str(before_path),
            with_a_snapshot=str(with_a_path),
            phase1_graph=str(graph_path),
        )
        print(json.dumps({
            "opportunity_id": opportunity_id,
            "graph_id": graph_id,
            "graph_state": inspected["state"],
            "subject_ids": rc.graph_subject_ids(inspected),
        }, ensure_ascii=False, indent=2))
    finally:
        rc.stop_server(process, graceful=True)

    state = rc.load_state()
    opportunity_id = state["opportunity_id"]
    receipts = rc.lifecycle_receipts(opportunity_id)
    births = [
        frame for frame in receipts
        if frame["fields"].get("kind") == "resource_opportunity_birth"
    ]
    terminals = [
        frame for frame in receipts
        if frame["fields"].get("kind") in {
            "resource_opportunity_consumed",
            "resource_opportunity_stale",
        }
    ]
    assert len(births) == 1, births
    assert not terminals, terminals

    rc.write_json(rc.OUT / "phase1-lifecycle-receipts.json", receipts)
    state = rc.save_state(
        birth_sequence=births[0]["sequence"],
        journal_sha_before_rollback=rc.sha256(rc.JOURNAL),
        graph_store_sha_before_rollback=rc.sha256(rc.GRAPH_STORE),
        semantic_before_sha=rc.sha256(Path(state["before_snapshot"])),
        semantic_with_a_sha=rc.sha256(Path(state["with_a_snapshot"])),
    )

    # Exact birth-side crash simulation: retain durable journal + Graph(A), but restore a semantic
    # snapshot from before A existed.
    shutil.copyfile(Path(state["before_snapshot"]), rc.SEM)
    rolled_back = rc.read_json(rc.SEM)
    assert not rc.opportunities_at(rolled_back, tuple(state["ore"]))
    rc.save_state(semantic_rollback_sha=rc.sha256(rc.SEM))

    print(json.dumps({
        "phase": 1,
        "result": "READY_GRAPH_PLANNED_ONLY_AND_SEMANTIC_ROLLED_BACK",
        "opportunity_id": opportunity_id,
        "graph_id": state["graph_id"],
        "birth_sequence": births[0]["sequence"],
        "graph_store_sha": state["graph_store_sha_before_rollback"],
        "semantic_rollback_sha": rc.sha256(rc.SEM),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
