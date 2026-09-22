# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 checker 自测(离线合成事实;同一入口 judge_ia/g4/g5)。

合成文档严格遵循 rcf1r3 schema —— 只验证 CHECKER 的判定逻辑
(正例通过/单因素变异拒绝),不冒充 LIVE 证据。
"""
import copy
import json
import sys

sys.path.insert(0, "tools")
import rcf1_checker as C  # noqa: E402

RESULTS = []


def run(tid, ok, detail=None):
    RESULTS.append((tid, bool(ok)))
    print(json.dumps({"id": tid, "pass": bool(ok),
                      "detail": detail or ""}, ensure_ascii=False))


JAR = "a" * 64
SRV = "b" * 64


def snap(inv=None, cursor=0):
    return {"client_mod_jar_sha256": JAR,
            "inventory": dict(inv or {}),
            "screen": {"present": bool(cursor), "cursor_count": cursor,
                       "cursor_item": "minecraft:air"}}


def ia_doc():
    def case(cid, actions, pre_inv=None, post_inv=None):
        return {"id": cid, "attempt": 1, "actions": actions,
                "pre_snapshot": snap(pre_inv),
                "post_snapshot": snap(post_inv),
                "oracle_blocks": {}}

    eat_ok = {"op": "eat", "args": {},
              "terminal": {"state": "completed",
                           "reason": "server_authoritative_food_consumed:"
                                     "minecraft:bread:2->1:claimed=1"
                                     ":witness=1:hunger:10->11"}}
    craft_ok = {"op": "craft",
                "args": {"item": "minecraft:oak_planks", "count": 4},
                "terminal": {"state": "completed",
                             "reason": "server_authoritative_native_craft:"
                                       "minecraft:oak_planks:0->4:delta=4"
                                       ":batches=1:output_per_batch=4"
                                       ":materials=oak_log:1"}}
    move_ok = {"op": "move_items",
               "args": {"item": "minecraft:dirt", "count": 2, "hotbar": 1},
               "terminal": {"state": "completed",
                            "reason": "server_authoritative_items_moved:"
                                      "minecraft:dirt:hotbar=1:baseline=0"
                                      ":after=2:gained=2"}}
    mine_ok = {"op": "mine_opportunity", "args": {"id": "opp1"},
               "terminal": {"state": "completed",
                            "reason": "server_authoritative_block_and_"
                                      "inventory_gain_verified:0->1"}}
    place_ok = {"op": "place",
                "args": {"item": "minecraft:oak_planks",
                         "x": 1, "y": 2, "z": 3},
                "terminal": {"state": "completed",
                             "reason": "server_authoritative_block_placed:"
                                       "minecraft:oak_planks"
                                       ":interaction_witnessed=true"}}
    fail_receipt = {"state": "failed", "reason": "client_no_food"}
    pos_cases = [
        case("I01", [move_ok], {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
        case("I02", [move_ok], {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
        case("I03", [move_ok], {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
        case("I04", [{"op": "deposit", "args": {},
                      "terminal": {"state": "completed",
                                   "reason": "server_authoritative_"
                                             "container_transfer:"
                                             "minecraft:dirt:deposit"
                                             ":player:4->2:container:0->2"}}],
             {"minecraft:dirt": 4}, {"minecraft:dirt": 2}),
        case("I08", [{"op": "deposit", "args": {},
                      "terminal": {"state": "completed",
                                   "reason": "server_authoritative_"
                                             "container_transfer:"
                                             "minecraft:dirt:deposit"
                                             ":player:4->2:container:0->2"}}],
             {"minecraft:dirt": 4}, {"minecraft:dirt": 2}),
        case("A01", [craft_ok], {"minecraft:oak_log": 1},
             {"minecraft:oak_log": 0, "minecraft:oak_planks": 4}),
        case("A02", [craft_ok], {"minecraft:oak_log": 1},
             {"minecraft:oak_log": 0, "minecraft:oak_planks": 4}),
        case("A04", [craft_ok], {"minecraft:oak_planks": 4},
             {"minecraft:oak_planks": 8}),
        case("A06", [place_ok], {"minecraft:oak_planks": 4},
             {"minecraft:oak_planks": 3}),
        case("A08", [eat_ok,
                     {"op": "eat", "args": {},
                      "terminal": {"state": "failed",
                                   "reason": "eat_no_food_in_inventory"}}],
             {"minecraft:bread": 2}, {"minecraft:bread": 1}),
    ]
    neg_cases = [
        case("I05", [{"op": "craft",
                      "args": {"item": "minecraft:oak_planks",
                               "count": 4}, "terminal": fail_receipt}],
             {}, {}),
        case("I06", [{"op": "craft",
                      "args": {"item": "minecraft:oak_planks",
                               "count": 4}, "terminal": fail_receipt}],
             {}, {}),
        case("I07", [{"op": "craft",
                      "args": {"item": "minecraft:oak_planks",
                               "count": 4}, "terminal": fail_receipt}],
             {}, {}),
        case("A03", [{"op": "craft",
                      "args": {"item": "minecraft:wooden_pickaxe",
                               "count": 1},
                      "terminal": {"state": "failed",
                                   "reason": "no_table_in_range"}}], {}, {}),
        case("A05", [{"op": "craft",
                      "args": {"item": "minecraft:stick", "count": 4},
                      "terminal": fail_receipt}], {}, {}),
        case("A07", [{"op": "place",
                      "args": {"item": "minecraft:oak_planks",
                               "x": 1, "y": 2, "z": 3},
                      "terminal": fail_receipt}], {"minecraft:oak_planks": 4},
             {"minecraft:oak_planks": 4}),
        case("A09", [{"op": "mine_opportunity", "args": {"id": "x"},
                      "terminal": fail_receipt}], {}, {}),
        case("A10", [{"op": "mine_opportunity", "args": {"id": "x"},
                      "terminal": fail_receipt}], {}, {}),
        case("A11", [{"op": "craft",
                      "args": {"item": "minecraft:stick", "count": 4},
                      "terminal": {"state": "cancelled",
                                   "reason": "client_cancelled"}}], {}, {}),
        case("A12", [{"op": "place", "args": {"item": "minecraft:dirt",
                                              "x": 1, "y": 2, "z": 3},
                      "terminal": fail_receipt}], {}, {}),
    ]
    return {"schema": C.IA_SCHEMA,
            "identity": {"first_observe": snap(),
                         "bridge_status_start": {
                             "server_mod_jar_sha256": SRV},
                         "bridge_status_end": {
                             "server_mod_jar_sha256": SRV},
                         "final_observe": snap()},
            "cases": pos_cases + neg_cases}


def g4_doc():
    def receipt(op, args, item=None, count=None):
        return {"op": op, "args": args,
                "terminal": {"state": "completed", "reason": "ok"}}

    receipts = (
        [{"op": "mine_opportunity", "args": {"id": "l%d" % i},
          "terminal": {"state": "completed",
                       "reason": "server_authoritative_block_and_"
                                 "inventory_gain_verified:0->1"}}
         for i in range(5)]
        + [{"op": "craft", "args": {"item": "minecraft:oak_planks",
                                    "count": 20},
            "terminal": {"state": "completed", "reason": "ok"}}]
        + [{"op": "craft", "args": {"item": "minecraft:stick", "count": 8},
           "terminal": {"state": "completed", "reason": "ok"}}]
        + [{"op": "craft", "args": {"item": "minecraft:crafting_table",
                                    "count": 1},
           "terminal": {"state": "completed", "reason": "ok"}}]
        + [{"op": "place", "args": {"item": "minecraft:crafting_table",
                                    "x": 8, "y": 107, "z": 2},
           "terminal": {"state": "completed",
                        "reason": "server_authoritative_block_placed:"
                                  ":interaction_witnessed=true"}}]
        + [{"op": "craft", "args": {"item": "minecraft:wooden_pickaxe",
                                    "count": 1},
           "terminal": {"state": "completed", "reason": "ok"}}]
        + [{"op": "mine_opportunity", "args": {"id": "s%d" % i},
            "terminal": {"state": "completed",
                         "reason": "server_authoritative_block_and_"
                                   "inventory_gain_verified:3->4"}}
         for i in range(3)]
        + [{"op": "craft", "args": {"item": "minecraft:stone_pickaxe",
                                    "count": 1},
           "terminal": {"state": "completed", "reason": "ok"}}]
        + [{"op": "craft", "args": {"item": "minecraft:wooden_pickaxe",
                                    "count": 1},
           "terminal": {"state": "completed", "reason": "ok"}}])
    runs = []
    for i in range(5):
        runs.append({
            "schema": C.G4_SCHEMA, "run_id": "b0%d" % (i + 1),
            "identity": {
                "candidate_commit": "c" * 40,
                "first_observe": snap(),
                "bridge_status_start": {"server_mod_jar_sha256": SRV},
                "run_started_wall": 1000.0 + i * 400},
            "events": [{"t": 5.0, "kind": "armed-timer-start",
                        "limit_s": 720}],
            "receipts": receipts,
            "snapshots": [
                {"tag": "pre", "facts": snap({})},
                {"tag": "after-logs", "facts": snap(
                    {"minecraft:oak_log": 5})},
                {"tag": "final", "facts": snap(
                    {"minecraft:wooden_pickaxe": 1,
                     "minecraft:stone_pickaxe": 1})}],
            "oracle_blocks": {"table@8,107,2": {"result": "passed"}},
            "fail_at": None,
            "ended_wall": 1000.0 + i * 400 + 120,
            "status_end": {"active_execution": None},
        })
    return {"schema": "mc.rcf1r3.runs.v1",
            "expect": {"logs_need": 5, "stone_need": 3,
                       "wood_item": "minecraft:oak_log",
                       "crafts": {"minecraft:stick": 8,
                                  "minecraft:crafting_table": 1,
                                  "minecraft:wooden_pickaxe": 2,
                                  "minecraft:stone_pickaxe": 1}},
            "runs": runs}


def g5_rows():
    def act(op, state="completed", t=0):
        return {"t_wall": t, "kind": "act",
                "data": {"model_channel": "test-channel",
                         "decision_note": "n", "op": op, "args": {},
                         "receipt": {"state": state,
                                     "reason": "server_authoritative_"
                                               "block_and_inventory_gain_"
                                               "verified:0->1"}}}
    rows = [
        {"t_wall": 1, "kind": "prep",
         "data": {"scene": "s01", "scene_decl": {}, "cmds": []}},
        {"t_wall": 2, "kind": "armed", "data": {"scene": "s01"}},
        act("mine_opportunity", t=10),
        act("craft", t=20),
        {"t_wall": 30, "kind": "dusk-baseline",
         "data": {"inv": {"minecraft:wooden_pickaxe": 1}}},
        act("place", t=40),
        {"t_wall": 50, "kind": "shelter-facts",
         "data": {"verdict": True}},
    ]
    t = 60
    for phase in ("dusk", "night", "night", "night", "night", "night",
                  "night", "night", "morning", "day"):
        rows.append({"t_wall": t, "kind": "night",
                     "data": {"phase": phase, "health": 20}})
        t += 20
    rows.append({"t_wall": t, "kind": "night-end", "data": {"dawn": True}})
    rows.append({"t_wall": t + 5, "kind": "verify-morning",
                 "data": {"tools_ok": True, "inv": {
                     "minecraft:wooden_pickaxe": 1,
                     "minecraft:stone_pickaxe": 1}}})
    rows.append(act("mine_opportunity", t=t + 30))
    return rows


def historical_counterexamples():
    """R2 历史 7 组误接受输入(旧格式)→ 现在必须明确拒绝。"""
    legacy = {"cases": [{"id": "A01", "results": [
        {"state": "completed", "reason": "server_authoritative"}]}]}
    ok, why = C.judge_ia(legacy)
    run("legacy-format-rejected", ok is False and "schema" in why, why)
    reason_only = {"cases": [{"id": "A01", "results": [
        {"state": "completed",
         "reason": "server_authoritative_native_craft:"
                   "minecraft:oak_planks:0->8:delta=32"}]}]}
    ok, why = C.judge_ia(reason_only)
    run("reason-arithmetic-alone-rejected", ok is False, why)


def semantic_mutations():
    base = ia_doc()
    ok, why = C.judge_ia(base)
    run("synthetic-valid-ia-accepted", ok is True, why)
    m = copy.deepcopy(base)
    for c in m["cases"]:
        for a in c["actions"]:
            if a["op"] == "eat":
                a["terminal"]["reason"] = a["terminal"][
                    "reason"].replace("witness=1", "witness=0")
    ok, why = C.judge_ia(m)
    run("mutation-eat-witness-zero-rejected", ok is False, why)
    m = copy.deepcopy(base)
    m["cases"].append(copy.deepcopy(m["cases"][0]))
    ok, why = C.judge_ia(m)
    run("mutation-duplicate-case-rejected", ok is False, why)
    m = copy.deepcopy(base)
    m["identity"]["first_observe"] = dict(m["identity"]["first_observe"])
    m["identity"]["first_observe"]["client_mod_jar_sha256"] = None
    m["identity"]["final_observe"] = dict(m["identity"]["final_observe"])
    m["identity"]["final_observe"]["client_mod_jar_sha256"] = None
    for k in ("bridge_status_start", "bridge_status_end"):
        m["identity"][k] = dict(m["identity"][k])
        m["identity"][k]["server_mod_jar_sha256"] = None
    ok, why = C.judge_ia(m)
    run("mutation-identity-stripped-rejected", ok is False, why)
    m = copy.deepcopy(base)
    for c in m["cases"]:
        if c["id"] == "A05":
            c["post_snapshot"]["inventory"] = {"minecraft:stick": 1}
    ok, why = C.judge_ia(m)
    run("mutation-negative-side-effect-rejected", ok is False, why)
    g4 = g4_doc()
    ok, why = C.judge_g4(g4)
    run("synthetic-valid-g4-accepted", ok is True, why)
    m = copy.deepcopy(g4)
    m["runs"] = [dict(m["runs"][0], run_id="x%d" % i) for i in range(5)]
    ok, why = C.judge_g4(m)
    run("mutation-g4-five-from-one-rejected", ok is False, why)
    m = copy.deepcopy(g4)
    for s in m["runs"][0]["snapshots"]:
        if s["tag"] == "pre":
            s["facts"]["inventory"] = {"minecraft:dirt": 1}
    ok, why = C.judge_g4(m)
    run("mutation-g4-initial-nonempty-rejected", ok is False, why)
    m = copy.deepcopy(g4)
    for s in m["runs"][0]["snapshots"]:
        if s["tag"] == "final":
            s["facts"]["screen"] = {"present": True, "cursor_count": 3}
    ok, why = C.judge_g4(m)
    run("mutation-g4-cursor-occupied-rejected", ok is False, why)
    rows = g5_rows()
    ok, why = C.judge_g5(rows, "s01")
    run("synthetic-valid-g5-accepted", ok is True, why)
    m = copy.deepcopy(rows)
    m.insert(12, {"t_wall": 999, "kind": "night-death",
                  "data": {"health": 0}})
    ok, why = C.judge_g5(m, "s01")
    run("mutation-g5-death-rejected", ok is False, why)
    m = copy.deepcopy(rows)
    for r in m:
        if r["kind"] == "shelter-facts":
            r["data"]["verdict"] = False
    ok, why = C.judge_g5(m, "s01")
    run("mutation-g5-unsheltered-rejected", ok is False, why)
    m = copy.deepcopy(rows)
    for r in m:
        if r["kind"] == "verify-morning":
            r["data"]["tools_ok"] = False
    ok, why = C.judge_g5(m, "s01")
    run("mutation-g5-tools-lost-rejected", ok is False, why)
    m = copy.deepcopy(rows)
    for r in m:
        if r["kind"] == "act":
            r["data"].pop("model_channel", None)
    ok, why = C.judge_g5(m, "s01")
    run("mutation-g5-script-without-llm-rejected", ok is False, why)


def main():
    historical_counterexamples()
    semantic_mutations()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("SELFTEST %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
