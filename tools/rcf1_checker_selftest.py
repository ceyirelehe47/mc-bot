# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 checker 反测试:同一最终入口既过真实正例,又拒绝语义变异。

对应审查 R01 四组历史误接受(reference/review_8626013/
checker_counterexamples.json)+ 03_ACCEPTance §H 的防自证清单。
全部离线;判定全部经 tools/rcf1_checker.py 的 judge_ia/judge_g4 主入口。
"""
import copy
import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcf1_checker as CK  # noqa: E402

RESULTS = []


def run(tid, ok, detail=""):
    RESULTS.append((tid, bool(ok)))
    print(json.dumps({"id": tid, "pass": bool(ok),
                      "detail": str(detail)[:200]}, ensure_ascii=False))


def pos(cid, reason):
    return {"id": cid, "results": [
        {"state": "completed", "reason": reason,
         "run_id": "run-%s-1" % cid}]}


def neg(cid, reason):
    return {"id": cid, "results": [
        {"state": "failed", "reason": reason,
         "precondition_established": True, "zero_extra_effect": True,
         "run_id": "run-%s-2" % cid}]}


def good_ia():
    cases = [
        pos("I01", "server_authoritative_items_moved:x:hotbar=2:0->1:gained=1"),
        pos("I02", "server_authoritative_items_moved:x:hotbar=3:baseline=0:after=7:gained=7"),
        pos("I03", "server_authoritative_items_moved:x:hotbar=1:baseline=0:after=1:gained=1"),
        pos("I04", "server_authoritative_container_transfer:x:withdraw:player:0->4:container:4->0"),
        neg("I05", "container_dest_full"),
        neg("I06", "client_screen_lost"),
        neg("I07", "ghost_slot_not_writable"),
        pos("I08", "server_authoritative_owned_screen_toms_transfer_verified:5"),
        pos("A01", "server_authoritative_native_craft:minecraft:oak_planks:0->8:delta=8"),
        pos("A02", "server_authoritative_native_craft:minecraft:wooden_pickaxe:0->1:delta=1"),
        neg("A03", "craft_no_crafting_table_nearby"),
        pos("A04", "server_authoritative_native_craft:minecraft:oak_planks:4->8:delta=4"),
        neg("A05", "craft_missing:minecraft:oak_planks"),
        pos("A06", "server_authoritative_block_placed:minecraft:crafting_table:1->0:at=8,107,2:consumed=true:interaction_witnessed=true"),
        neg("A07", "place_target_taken_by_other_block"),
        pos("A08", "server_authoritative_food_consumed:minecraft:bread:3->2:claimed=1"),
        neg("A08x", "eat_no_food_in_inventory"),
        neg("A09", "real_client_resource_opportunity_stale"),
        neg("A10", "block_gone_without_inventory_gain"),
        neg("A11", "client_action_superseded"),
        neg("A12", "no_wall_through_target"),
    ]
    # A08 需要同一 id 下的正负两态
    cases = [c for c in cases if c["id"] != "A08x"]
    for c in cases:
        if c["id"] == "A08":
            c["results"].append(
                {"state": "failed", "reason": "eat_no_food_in_inventory",
                 "precondition_established": True,
                 "zero_extra_effect": True, "run_id": "run-A08-3"})
    registry = {}
    for c in cases:
        for r in c["results"]:
            registry[r["run_id"]] = {"candidate": "a" * 40,
                                     "world": "w1", "session": "s1"}
    return {"cases": cases, "runs": registry}


def good_g4():
    runs = []
    for i in range(1, 6):
        runs.append({
            "run": "b01-%d" % i,
            "session_id": "sess-%d" % i,
            "candidate": "a" * 40,
            "result": "PASS",
            "duration_s": 300 + i,
            "final_inventory": {"minecraft:wooden_pickaxe": 1,
                                "minecraft:stone_pickaxe": 1},
            "cursor_empty": True,
            "chain": {k: True for k in CK.G4_CHAIN_REQUIRED},
            "events": [{"t": j * i, "e": "step-%d-%d" % (i, j)}
                       for j in range(6)],
        })
    return runs


# ---------- 历史四反例:必须被主入口拒绝 ----------

def historical_counterexamples():
    cx1 = {"cases": [{"id": "A01", "results": [
        {"state": "completed", "reason": "server_authoritative"}]}]}
    ok, why = CK.judge_ia(cx1)
    run("H-A-missing-required-set-rejected", not ok, why)

    cx2 = {"cases": [{"id": "V01", "results": [
        {"state": "completed",
         "reason": "server_authoritative_native_craft:x:0->8:delta=32"}]}]}
    ok, why = CK.judge_ia(cx2)
    run("H-B-contradictory-delta-rejected", not ok, why)

    good = good_ia()
    a01 = [c for c in good["cases"] if c["id"] == "A01"][0]
    a01["results"][0] = {"state": "failed", "reason": "action_did_not_run"}
    ok, why = CK.judge_ia(good)
    run("H-C-positive-only-failed-rejected", not ok, why)

    cx4 = []
    for _ in range(5):
        cx4.append({
            "run": 1, "session_id": "sess-1", "candidate": "C1",
            "result": "PASS",
            "final_inventory": {"minecraft:wooden_pickaxe": 1,
                                "minecraft:stone_pickaxe": 1},
            "cursor_empty": True,
            "chain": {k: True for k in CK.G4_CHAIN_REQUIRED},
            "duration_s": 100,
            "events": [{"e": "same"}],
        })
    ok, why = CK.judge_g4(cx4)
    run("H-D-five-copies-one-run-rejected", not ok, why)


# ---------- 语义变异:同一入口拒绝 ----------

def semantic_mutations():
    ok, why = CK.judge_ia(good_ia())
    run("S0-good-ia-passes", ok, why)
    ok, why = CK.judge_g4(good_g4())
    run("S0-good-g4-passes", ok, why)

    bad = good_ia()
    del bad["cases"][0]["id"]
    ok, why = CK.judge_ia(bad)
    run("S1-missing-id-rejected", not ok, why)

    bad = good_ia()
    bad["cases"] = bad["cases"][:5]
    ok, why = CK.judge_ia(bad)
    run("S2-missing-most-required-rejected", not ok, why)

    bad = good_ia()
    for c in bad["cases"]:
        for r in c["results"]:
            r["state"] = "failed"
            r["precondition_established"] = True
            r["zero_extra_effect"] = True
    ok, why = CK.judge_ia(bad)
    run("S3-all-reject-no-positive-rejected", not ok, why)

    # 篡改摘要但保留事实:数量矛盾不被文案带走
    bad = good_ia()
    a01 = [c for c in bad["cases"] if c["id"] == "A01"][0]
    a01["results"][0]["reason"] = ("server_authoritative_native_craft:"
                                   "minecraft:oak_planks:0->8:delta=32")
    ok, why = CK.judge_ia(bad)
    run("S4-tampered-summary-vs-facts-rejected", not ok, why)

    # 负例缺前置建立证明(只failed不算)
    bad = good_ia()
    a05 = [c for c in bad["cases"] if c["id"] == "A05"][0]
    a05["results"][0]["precondition_established"] = False
    ok, why = CK.judge_ia(bad)
    run("S5-negative-without-precondition-rejected", not ok, why)

    # NOT_RUN 混入计分
    bad = good_ia()
    bad["cases"][0]["results"][0]["state"] = "NOT_RUN"
    ok, why = CK.judge_ia(bad)
    run("S6-notrun-not-scoreable-rejected", not ok, why)

    # 混入另一 run/世界(负例 zero_effect 来自别的事件集)
    bad = good_ia()
    i05 = [c for c in bad["cases"] if c["id"] == "I05"][0]
    i05["results"][0]["run_id"] = "run-OTHER-world"
    i05["results"][0]["cross_run_contamination"] = True
    ok, why = CK.judge_ia(bad)
    run("S7-cross-run-mixture-rejected", not ok, why)

    # G4:候选非完整40位sha
    bad = good_g4()
    for r in bad:
        r["candidate"] = "C1"
    ok, why = CK.judge_g4(bad)
    run("S8-candidate-not-full-sha-rejected", not ok, why)

    # G4:缺一个链阶段
    bad = copy.deepcopy(good_g4())
    del bad[2]["chain"]["mined_stone_with_pickup"]
    ok, why = CK.judge_g4(bad)
    run("S9-missing-chain-stage-rejected", not ok, why)

    # G4:超时
    bad = copy.deepcopy(good_g4())
    bad[1]["duration_s"] = 800
    ok, why = CK.judge_g4(bad)
    run("S10-over-time-limit-rejected", not ok, why)

    # G4:事件链复制(改run名与摘要)
    bad = copy.deepcopy(good_g4())
    same = bad[0]["events"]
    for r in bad[1:]:
        r["events"] = copy.deepcopy(same)
    ok, why = CK.judge_g4(bad)
    run("S11-duplicated-process-events-rejected", not ok, why)

    # G4:缺过程事件
    bad = copy.deepcopy(good_g4())
    bad[0]["events"] = []
    ok, why = CK.judge_g4(bad)
    run("S12-no-process-events-rejected", not ok, why)


def main():
    historical_counterexamples()
    semantic_mutations()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("SUMMARY %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
