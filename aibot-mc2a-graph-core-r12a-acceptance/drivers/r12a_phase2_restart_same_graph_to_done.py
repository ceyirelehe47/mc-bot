# -*- coding: utf-8 -*-
"""
LIVE-R12A-1 phase 2.

Restarts after the semantic rollback, verifies exact A and the original READY graph id, then calls
run-next on that same graph and requires DONE. This file deliberately contains no Graph plan call.
"""
from __future__ import annotations

import json
import time

import r12a_common as rc


TERMINAL = {"DONE", "FAILED", "STALE", "CANCELLED"}


def main() -> None:
    rc.ensure_dirs()
    state = rc.load_state()
    opportunity_id = state["opportunity_id"]
    graph_id = state["graph_id"]
    ore = tuple(state["ore"])
    stand = tuple(state["stand"])

    process = rc.start_server(rc.OUT / "phase2-server.log")
    final_graph = None
    try:
        # Strong ordering proof: inspect the persisted semantic file after the ready marker but
        # before the first HTTP call or test RCON mutation.
        rc.wait_quiet(rc.SEM)
        restored = rc.read_json(rc.SEM)
        matches = rc.opportunities_at(restored, ore)
        assert len(matches) == 1, matches
        assert matches[0]["id"] == opportunity_id, matches
        rc.write_json(rc.OUT / "semantic-restored-before-first-http.json", restored)
        rc.save_state(
            semantic_restored_sha=rc.sha256(rc.SEM),
            restored_id_before_first_http=matches[0]["id"],
        )

        gl = rc.load_graph_live()
        lease = gl.get_lease()
        inspected = gl.call("GET", f"/v1/graphs/{graph_id}", lease)["data"]
        assert inspected["graph_id"] == graph_id
        assert inspected["state"] == "READY", inspected
        assert rc.graph_subject_ids(inspected) == [opportunity_id], inspected
        for node in inspected.get("nodes", []):
            assert not node.get("execution_id"), node
            assert node.get("state") == "READY", node
        rc.write_json(rc.OUT / "original-graph-after-restart.json", inspected)

        print(rc.rcon(f"tp Bob {stand[0]} {stand[1]} {stand[2]}"))
        print(rc.rcon("give Bob minecraft:iron_pickaxe"))
        time.sleep(6)
        gl.call("GET", "/v1/observe", lease)
        time.sleep(3)
        rc.wait_quiet(rc.SEM)
        after_observe = rc.read_json(rc.SEM)
        observed_matches = rc.opportunities_at(after_observe, ore)
        assert len(observed_matches) == 1, observed_matches
        assert observed_matches[0]["id"] == opportunity_id, observed_matches
        rc.write_json(rc.OUT / "semantic-after-reobserve.json", after_observe)

        request_id = f"r12a-same-graph-run-{int(time.time() * 1000)}"
        dispatched = gl.call(
            "POST",
            f"/v1/graphs/{graph_id}/run-next",
            lease,
            None,
            {"X-Request-Id": request_id},
        )["data"]
        assert dispatched["graph"]["graph_id"] == graph_id
        rc.write_json(rc.OUT / "same-graph-dispatch.json", dispatched)

        deadline = time.time() + 240
        while time.time() < deadline:
            final_graph = gl.call("GET", f"/v1/graphs/{graph_id}", lease)["data"]
            if final_graph.get("state") in TERMINAL:
                break
            time.sleep(1)
        assert final_graph is not None
        assert final_graph["graph_id"] == graph_id
        assert final_graph["state"] == "DONE", final_graph
        assert rc.graph_subject_ids(final_graph) == [opportunity_id], final_graph
        assert any(
            "durable_inventory_gain_receipt" in str(node.get("reason", ""))
            for node in final_graph.get("nodes", [])
        ), final_graph
        rc.write_json(rc.OUT / "original-graph-final.json", final_graph)
    finally:
        rc.stop_server(process, graceful=True)

    receipts = rc.lifecycle_receipts(opportunity_id)
    births = [
        frame for frame in receipts
        if frame["fields"].get("kind") == "resource_opportunity_birth"
    ]
    consumed = [
        frame for frame in receipts
        if frame["fields"].get("kind") == "resource_opportunity_consumed"
    ]
    stale = [
        frame for frame in receipts
        if frame["fields"].get("kind") == "resource_opportunity_stale"
    ]
    assert len(births) == 1, births
    assert consumed, consumed
    assert not stale, stale
    assert births[0]["sequence"] < consumed[-1]["sequence"], receipts
    rc.write_json(rc.OUT / "final-lifecycle-receipts.json", receipts)

    result = {
        "result": "PASS",
        "opportunity_id": opportunity_id,
        "original_graph_id": graph_id,
        "restored_before_first_http": True,
        "graph_ready_after_restart": True,
        "reobserve_kept_same_id": True,
        "same_original_graph_done": True,
        "birth_sequence": births[0]["sequence"],
        "consumed_sequence": consumed[-1]["sequence"],
        "final_graph_state": final_graph["state"] if final_graph else None,
        "semantic_restored_sha": rc.sha256(
            rc.OUT / "semantic-restored-before-first-http.json"
        ),
    }
    rc.write_json(rc.OUT / "LIVE-R12A-1-result.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
