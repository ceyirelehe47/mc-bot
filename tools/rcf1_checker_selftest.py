# -*- coding: utf-8 -*-
"""MC-RCF-1-R3C checker 自测(离线合成事实;同一入口 judge_ia/g4/g5/all)。

合成文档严格遵循 rcf1r3c v2 schema —— 只验证 CHECKER 的判定逻辑
(正例通过/单因素变异拒绝/重排免疫/malformed 不崩溃),不冒充 LIVE
证据,也不作为正式反例(正式反例基于真实最终证据单因素变异,
见 rcf1_mutations.py 与 ACCEPTANCE §5-§6)。
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
GS = "gs-1111"


def snap(inv=None, cursor=0, err=False):
    s = {"client_mod_jar_sha256": JAR,
         "game_session": GS,
         "inventory": dict(inv or {}),
         "screen": {"present": bool(cursor), "cursor_count": cursor,
                    "cursor_item": "minecraft:air"},
         "world_time": 1000}
    if err:
        s["error"] = "observe-failed"
    return s


def action(op, args, terminal, pre_inv, post_inv, ex="ex-%03d"):
    global _EXN
    try:
        _EXN += 1
        ident = ex % _EXN
    except TypeError:
        ident = ex
    return {"op": op, "args": args, "execution_id": ident,
            "submit": {"execution_id": ident},
            "terminal": terminal,
            "pre_action": snap(pre_inv), "post_action": snap(post_inv)}


_EXN = 0


def term_ok(reason):
    return {"state": "completed", "reason": reason}


def term_fail(reason):
    return {"state": "failed", "reason": reason}


EAT_OK = ("server_authoritative_food_consumed:minecraft:bread:2->1"
          ":claimed=1:witness=1:hunger:10->11")
CRAFT_OK = ("server_authoritative_native_craft:minecraft:oak_planks"
            ":0->4:delta=4:batches=1")
MOVE_OK = ("server_authoritative_items_moved:minecraft:dirt"
           ":hotbar=1:baseline=0:after=2:gained=2")
GAIN_OK = "server_authoritative_block_and_inventory_gain_verified:0->1"
PLACE_OK = ("server_authoritative_block_placed:minecraft:oak_planks"
            ":interaction_witnessed=true")
DEP_OK = ("server_authoritative_container_transfer:minecraft:dirt"
          ":deposit:player:4->2:container:0->2")
GOTO_OK = "server_authoritative_arrival:bounded_goal"


def ia_case(cid, actions, pre_inv, post_inv, attempt=1):
    return {"id": cid, "attempt": attempt, "actions": actions,
            "pre_snapshot": snap(pre_inv), "post_snapshot": snap(post_inv),
            "oracle_blocks": {}}


def ia_doc():
    global _EXN
    _EXN = 0
    pos = [
        ia_case("I01", [action("move_items",
                               {"item": "minecraft:dirt", "count": 2,
                                "hotbar": 1}, term_ok(MOVE_OK),
                               {"minecraft:dirt": 4},
                               {"minecraft:dirt": 4})],
                {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
        *[ia_case(cid, [action("move_items",
                               {"item": "minecraft:dirt", "count": 2,
                                "hotbar": 1}, term_ok(MOVE_OK),
                               {"minecraft:dirt": 4},
                               {"minecraft:dirt": 4})],
                  {"minecraft:dirt": 4}, {"minecraft:dirt": 4})
          for cid in ("I02a", "I02b", "I02c", "I02d", "I03")],
        ia_case("I04", [action("deposit", {}, term_ok(DEP_OK),
                               {"minecraft:dirt": 4},
                               {"minecraft:dirt": 2})],
                {"minecraft:dirt": 4}, {"minecraft:dirt": 2}),
        ia_case("I08", [action("deposit", {}, term_ok(DEP_OK),
                               {"minecraft:dirt": 4},
                               {"minecraft:dirt": 2})],
                {"minecraft:dirt": 4}, {"minecraft:dirt": 2}),
        ia_case("A01", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 4}, term_ok(CRAFT_OK),
                               {"minecraft:oak_log": 1},
                               {"minecraft:oak_log": 0,
                                "minecraft:oak_planks": 4})],
                {"minecraft:oak_log": 1},
                {"minecraft:oak_log": 0, "minecraft:oak_planks": 4}),
        ia_case("A02", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 4}, term_ok(CRAFT_OK),
                               {"minecraft:oak_log": 1},
                               {"minecraft:oak_log": 0,
                                "minecraft:oak_planks": 4})],
                {"minecraft:oak_log": 1},
                {"minecraft:oak_log": 0, "minecraft:oak_planks": 4}),
        ia_case("A04", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 4}, term_ok(CRAFT_OK),
                               {"minecraft:oak_planks": 4},
                               {"minecraft:oak_planks": 8})],
                {"minecraft:oak_planks": 4},
                {"minecraft:oak_planks": 8}),
        ia_case("A06", [action("place",
                               {"item": "minecraft:oak_planks",
                                "x": 1, "y": 2, "z": 3},
                               term_ok(PLACE_OK),
                               {"minecraft:oak_planks": 4},
                               {"minecraft:oak_planks": 3})],
                {"minecraft:oak_planks": 4},
                {"minecraft:oak_planks": 3}),
        ia_case("A08", [
            action("eat", {}, term_ok(EAT_OK), {"minecraft:bread": 2},
                   {"minecraft:bread": 1}),
            action("eat", {}, term_fail("eat_not_hungry"),
                   {"minecraft:bread": 1}, {"minecraft:bread": 1})],
            {"minecraft:bread": 2}, {"minecraft:bread": 1}),
        ia_case("V02", [
            action("move_items", {"item": "minecraft:dirt", "count": 2,
                                  "hotbar": 1}, term_ok(MOVE_OK),
                   {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
            action("move_items", {"item": "minecraft:dirt", "count": 0,
                                  "hotbar": 1},
                   term_fail("client_move_count_zero"),
                   {"minecraft:dirt": 4}, {"minecraft:dirt": 4})],
            {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
    ]
    negs = [
        ia_case("I05", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 4}, term_fail("inv-full"),
                               {}, {})], {}, {}),
        ia_case("I06", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 4},
                               {"state": "cancelled",
                                "reason": "client_cancelled"},
                               {"minecraft:oak_log": 8},
                               {"minecraft:oak_log": 7,
                                "minecraft:oak_planks": 4})],
                {"minecraft:oak_log": 8},
                {"minecraft:oak_log": 7, "minecraft:oak_planks": 4}),
        ia_case("I07", [action("container_transfer",
                               {"item": "minecraft:dirt", "count": 1,
                                "direction": "deposit"},
                               term_fail("not-a-container"),
                               {"minecraft:dirt": 1},
                               {"minecraft:dirt": 1})],
                {"minecraft:dirt": 1}, {"minecraft:dirt": 1}),
        ia_case("A03", [action("craft",
                               {"item": "minecraft:stone_pickaxe",
                                "count": 1}, term_fail("no_table"),
                               {}, {})], {}, {}),
        ia_case("A05", [action("craft",
                               {"item": "minecraft:crafting_table",
                                "count": 1},
                               term_fail("insufficient_materials"),
                               {}, {})], {}, {}),
        ia_case("A07", [action("place",
                               {"item": "minecraft:oak_planks",
                                "x": 1, "y": 2, "z": 3},
                               term_fail("no_support_face"),
                               {"minecraft:oak_planks": 4},
                               {"minecraft:oak_planks": 4})],
                {"minecraft:oak_planks": 4},
                {"minecraft:oak_planks": 4}),
        ia_case("A09", [action("mine_opportunity", {"id": "x"},
                               term_fail("stale_target"),
                               {}, {})], {}, {}),
        ia_case("A10", [action("mine_opportunity", {"id": "x"},
                               term_fail("drop_blocked"),
                               {}, {})], {}, {}),
        ia_case("A11", [action("craft",
                               {"item": "minecraft:stick", "count": 4},
                               {"state": "cancelled",
                                "reason": "client_cancelled"},
                               {}, {})], {}, {}),
        ia_case("A12", [action("place",
                               {"item": "minecraft:dirt",
                                "x": 300, "y": 130, "z": 340},
                               term_fail("out_of_reach"),
                               {"minecraft:dirt": 4},
                               {"minecraft:dirt": 4})],
                {"minecraft:dirt": 4}, {"minecraft:dirt": 4}),
        ia_case("V01", [action("craft",
                               {"item": "minecraft:oak_planks",
                                "count": 32},
                               term_fail("material_depleted_midway"),
                               {"minecraft:oak_planks": 0},
                               {"minecraft:oak_planks": 4})],
                {}, {"minecraft:oak_planks": 4}),
        ia_case("V04", [action("eat", {},
                               term_fail("client_eat_timeout"),
                               {"minecraft:cooked_beef": 2}, {})],
                {"minecraft:cooked_beef": 2}, {}),
    ]
    return {"schema": C.IA_SCHEMA,
            "identity": {"first_observe": snap(),
                         "bridge_status_start": {
                             "data": {"server_mod_jar_sha256": SRV}},
                         "bridge_status_end": {
                             "data": {"server_mod_jar_sha256": SRV}},
                         "final_observe": snap()},
            "cases": pos + negs}


def g4_doc():
    def run_doc(rid, t0):
        receipts = []
        ex = [0]

        def rc(op, args):
            ex[0] += 1
            receipts.append({
                "op": op, "args": args,
                "execution_id": "%s-ex%d" % (rid, ex[0]),
                "terminal": {"state": "completed", "reason": "ok"}})
        for i in range(5):
            rc("mine_opportunity", {"id": "log%d" % i})
        rc("craft", {"item": "minecraft:oak_planks", "count": 20})
        rc("craft", {"item": "minecraft:stick", "count": 8})
        rc("craft", {"item": "minecraft:crafting_table", "count": 1})
        rc("craft", {"item": "minecraft:wooden_pickaxe", "count": 2})
        rc("craft", {"item": "minecraft:stone_pickaxe", "count": 1})
        rc("place", {"item": "minecraft:crafting_table",
                     "x": 300, "y": 120, "z": 302})
        for i in range(3):
            rc("mine_opportunity", {"id": "st%d" % i})
        return {
            "run_id": rid,
            "identity": {"candidate_commit": "c" * 40,
                         "first_observe": snap(),
                         "bridge_status_start": {
                             "data": {"server_mod_jar_sha256": SRV}},
                         "run_started_wall": t0,
                         "game_session": GS},
            "events": [{"t": 1.0, "kind": "armed-timer-start"}],
            "receipts": receipts,
            "snapshots": [
                {"tag": "pre", "facts": snap({})},
                {"tag": "after-logs", "facts": snap(
                    {"minecraft:oak_log": 5})},
                {"tag": "final", "facts": snap(
                    {"minecraft:wooden_pickaxe": 1,
                     "minecraft:stone_pickaxe": 1})}],
            "oracle_blocks": {"table@300,120,302": {"result": "passed"}},
            "fail_at": None,
            "ended_wall": t0 + 300,
            "status_end": {"data": {"active_execution": None,
                                    "needs_reconcile": False}},
        }
    return {"schema": "mc.rcf1r3.g4run.v1",
            "expect": {"logs_need": 5, "stone_need": 3},
            "matrix": {"b01": "oak", "b02": "oak", "b03": "birch",
                       "b04": "birch", "b05": "oak-layout"},
            "runs": [run_doc("b0%d" % i, 1000.0 + i * 1000)
                     for i in range(1, 6)]}


def g5_rows():
    rows = []
    t = 1000.0

    def add(kind, data):
        nonlocal t
        t += 5.0
        rows.append({"t_wall": round(t, 3), "kind": kind, "data": data})
    add("prep", {"scene": "s01",
                 "observe": snap({}, err=False)})
    add("armed", {"scene": "s01"})
    # 模型决策 act(非 skill 通道)×3,两种 op
    add("act", {"model_channel": "test-model", "op": "goto",
                "args": {}, "decision_note": "去树",
                "receipt": {"state": "completed",
                            "reason": GOTO_OK},
                "pre_inv": {}, "post_inv": {}})
    add("act", {"model_channel": "skill:mine_opportunity",
                "op": "mine_opportunity", "args": {"id": "l1"},
                "receipt": {"state": "completed", "reason": GAIN_OK},
                "pre_inv": {}, "post_inv": {"minecraft:oak_log": 3}})
    add("act", {"model_channel": "skill:mine_opportunity",
                "op": "mine_opportunity", "args": {"id": "l2"},
                "receipt": {"state": "completed", "reason": GAIN_OK},
                "pre_inv": {"minecraft:oak_log": 3},
                "post_inv": {"minecraft:oak_log": 6}})
    add("act", {"model_channel": "skill:craft", "op": "craft",
                "args": {"item": "minecraft:oak_planks", "count": 4},
                "receipt": {"state": "completed", "reason": "ok"},
                "pre_inv": {"minecraft:oak_log": 6},
                "post_inv": {"minecraft:oak_log": 5,
                             "minecraft:oak_planks": 4}})
    add("act", {"model_channel": "skill:mine_opportunity",
                "op": "mine_opportunity", "args": {"id": "s1"},
                "receipt": {"state": "completed", "reason": GAIN_OK},
                "pre_inv": {}, "post_inv": {"minecraft:cobblestone": 3}})
    add("act", {"model_channel": "test-model", "op": "place",
                "args": {"item": "minecraft:dirt"},
                "decision_note": "封口",
                "receipt": {"state": "completed", "reason": PLACE_OK},
                "pre_inv": {"minecraft:dirt": 2}, "post_inv": {
                    "minecraft:dirt": 1}})
    add("act", {"model_channel": "test-model", "op": "say",
                "args": {"message": "night"},
                "decision_note": "过夜静默",
                "receipt": {"state": "completed",
                            "reason": "client_chat_packet_sent"},
                "pre_inv": {}, "post_inv": {}})
    add("dusk-baseline", {"observe": snap({"minecraft:oak_log": 6}),
                          "day_phase": "dusk"})
    add("shelter-facts", {
        "facts": {"body_cell": [100, 90, -70],
                  "above": "minecraft:dirt",
                  "sides": {"N": "minecraft:dirt", "S": "minecraft:dirt",
                            "E": "minecraft:dirt", "W": "minecraft:dirt"}},
        "verdict": True})
    # 夜晚:12100→23600,每 400 tick 一行(20s),覆盖黄昏/夜/午夜
    wt = 12100
    while wt <= 23600:
        t += 20.0
        rows.append({"t_wall": round(t, 3), "kind": "night",
                     "data": {"phase": "night", "health": 20,
                              "world_time": wt, "pos": {"x": 100, "z": -70},
                              "game_session": GS}})
        wt += 400
    add("night-end", {"dawn": True, "world_time": 23600})
    add("verify-morning", {"inv": {"minecraft:wooden_pickaxe": 1,
                                   "minecraft:stone_pickaxe": 1,
                                   "minecraft:oak_log": 6}})
    add("act", {"model_channel": "skill:mine_opportunity",
                "op": "mine_opportunity", "args": {"id": "m1"},
                "receipt": {"state": "completed", "reason": GAIN_OK},
                "pre_inv": {"minecraft:oak_log": 6},
                "post_inv": {"minecraft:oak_log": 7}})
    return rows


def main():
    # ---------- IA ----------
    doc = ia_doc()
    ok, reason = C.judge_ia(doc)
    run("ST-IA-POS", ok, reason)

    d = copy.deepcopy(doc)
    d["cases"] = list(reversed(d["cases"]))
    ok, reason = C.judge_ia(d)
    run("ST-IA-REORDER-STILL-OK", ok, reason)  # M11 重排免疫

    # M11:必测正例空 actions
    d = copy.deepcopy(doc)
    d["cases"][0]["actions"] = []
    ok, reason = C.judge_ia(d)
    run("ST-IA-M11-EMPTY-ACTIONS", not ok, reason)

    # M01:删 case 快照
    d = copy.deepcopy(doc)
    d["cases"][0]["pre_snapshot"] = None
    ok, reason = C.judge_ia(d)
    run("ST-IA-M01-SNAPSHOT-REMOVED", not ok, reason)

    # M12:混合例中负例(move 拒绝)带真实副作用
    d = copy.deepcopy(doc)
    v02 = [c for c in d["cases"] if c["id"] == "V02"][0]
    neg = [a for a in v02["actions"]
           if a["terminal"]["state"] == "failed"][0]
    neg["post_action"]["inventory"] = {"minecraft:dirt": 3}
    ok, reason = C.judge_ia(d)
    run("ST-IA-M12-NEG-SIDE-EFFECT", not ok, reason)

    # M13:旧式 case 中途 give(case 级污染)在 v2 逐动作下不影响
    # 守恒——正确数据不受 fixture 摆布影响(B1 方向证明)
    d = copy.deepcopy(doc)
    i02a = [c for c in d["cases"] if c["id"] == "I02a"][0]
    i02a["post_snapshot"]["inventory"]["minecraft:cobblestone"] = 26
    ok, reason = C.judge_ia(d)
    run("ST-IA-M13-CASE-LEVEL-FIXTURE-TOLERATED", ok, reason)

    # M14:pre 晚于 post(时间颠倒)
    d = copy.deepcopy(doc)
    a = [c for c in d["cases"] if c["id"] == "A01"][0]["actions"][0]
    a["pre_action"], a["post_action"] = a["post_action"], a["pre_action"]
    ok, reason = C.judge_ia(d)
    run("ST-IA-M14-PRE-POST-SWAPPED", not ok, reason)

    # M15:重复计分身份
    d = copy.deepcopy(doc)
    a1 = [c for c in d["cases"] if c["id"] == "I01"][0]["actions"][0]
    a2 = [c for c in d["cases"] if c["id"] == "I03"][0]["actions"][0]
    a2["execution_id"] = a1["execution_id"]
    ok, reason = C.judge_ia(d)
    run("ST-IA-M15-DUPLICATE-IDENTITY", not ok, reason)

    # M16:总量守恒但 gained≠请求
    d = copy.deepcopy(doc)
    a = [c for c in d["cases"] if c["id"] == "I01"][0]["actions"][0]
    a["terminal"]["reason"] = MOVE_OK.replace("gained=2", "gained=1")
    ok, reason = C.judge_ia(d)
    run("ST-IA-M16-GAIN-NOT-REQUESTED", not ok, reason)

    # M17:witness=0
    d = copy.deepcopy(doc)
    a08 = [c for c in d["cases"] if c["id"] == "A08"][0]
    a08["actions"][0]["terminal"]["reason"] = EAT_OK.replace(
        "witness=1", "witness=0")
    ok, reason = C.judge_ia(d)
    run("ST-IA-M17-EAT-WITNESS-ZERO", not ok, reason)

    # M18:malformed 不崩溃
    run("ST-IA-M18-MALFORMED", all(
        not C.judge_ia(x)[0] for x in
        ([], "x", {}, {"schema": "other"}, {"schema": C.IA_SCHEMA,
                                            "identity": {},
                                            "cases": [{"id": "ZZZ"}]})))
    # 未知 case id
    d = copy.deepcopy(doc)
    d["cases"].append(ia_case("Z99", [], {}, {}))
    ok, reason = C.judge_ia(d)
    run("ST-IA-M18-UNKNOWN-CASE", not ok, reason)
    # outcome_unknown 未结
    d = copy.deepcopy(doc)
    d["cases"][0]["actions"][0]["terminal"]["state"] = "outcome_unknown"
    ok, reason = C.judge_ia(d)
    run("ST-IA-UNKNOWN-UNRESOLVED", not ok, reason)
    # 快照查询失败 ≠ 空包
    d = copy.deepcopy(doc)
    d["cases"][0]["actions"][0]["pre_action"]["error"] = "observe-failed"
    ok, reason = C.judge_ia(d)
    run("ST-IA-SNAPSHOT-ERROR-REJECTED", not ok, reason)

    # ---------- G4 ----------
    g4 = g4_doc()
    ok, reason = C.judge_g4(g4)
    run("ST-G4-POS", ok, reason)

    d = copy.deepcopy(g4)
    del d["runs"][0]["status_end"]
    ok, reason = C.judge_g4(d)
    run("ST-G4-M03-STATUS-END-REMOVED", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][0]["snapshots"][2]["facts"]["screen"] = {
        "present": False, "cursor_count": 3}
    ok, reason = C.judge_g4(d)
    run("ST-G4-M02-CURSOR-PRESENT-FALSE", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][0]["status_end"]["data"]["needs_reconcile"] = True
    ok, reason = C.judge_g4(d)
    run("ST-G4-M04-NEEDS-RECONCILE", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][0]["oracle_blocks"] = {}
    ok, reason = C.judge_g4(d)
    run("ST-G4-M05-TABLE-ORACLE-REMOVED", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][1]["identity"]["candidate_commit"] = "d" * 40
    ok, reason = C.judge_g4(d)
    run("ST-G4-M06-CANDIDATE-MIXED", not ok, reason)

    d = copy.deepcopy(g4)
    d["expect"] = {"logs_need": 2}
    ok, reason = C.judge_g4(d)
    run("ST-G4-M09-EXPECT-DOWNGRADED", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][1]["receipts"][0]["execution_id"] = \
        d["runs"][0]["receipts"][0]["execution_id"]
    ok, reason = C.judge_g4(d)
    run("ST-G4-M08-DUPLICATE-EXEC", not ok, reason)

    d = copy.deepcopy(g4)
    d["runs"][1]["identity"]["run_started_wall"] = 1000.0
    ok, reason = C.judge_g4(d)
    run("ST-G4-M08-INTERVALS-OVERLAP", not ok, reason)

    # ---------- G5 ----------
    rows = g5_rows()
    ok, reason = C.judge_g5(rows, "s01")
    run("ST-G5-POS", ok, reason)

    # M19:verdict=true 但无原始方块事实
    d = [dict(r) for r in rows]
    for r in d:
        if r["kind"] == "shelter-facts":
            r["data"] = {"verdict": True}
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-M19-VERDICT-WITHOUT-FACTS", not ok, reason)

    # M20:某侧 unknown
    d = [dict(r) for r in rows]
    for r in d:
        if r["kind"] == "shelter-facts":
            r2 = copy.deepcopy(r)
            r2["data"]["facts"]["sides"]["E"] = None
            d[d.index(r)] = r2
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-M20-UNKNOWN-SIDE", not ok, reason)

    # M21:时间跳跃
    d = copy.deepcopy(rows)
    n = [r for r in d if r["kind"] == "night"]
    n[len(n) // 2]["data"]["world_time"] += 6000
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-M21-TIME-JUMP", not ok, reason)

    # M22:清晨缺镐(tools_ok=true 也不算)
    d = copy.deepcopy(rows)
    for r in d:
        if r["kind"] == "verify-morning":
            r["data"] = {"tools_ok": True, "inv": {"minecraft:dirt": 3}}
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-M22-MORNING-NO-PICKS", not ok, reason)

    # M23:全 skill 通道(固定脚本演出)
    d = copy.deepcopy(rows)
    for r in d:
        if r["kind"] == "act":
            r["data"]["model_channel"] = "skill:%s" % r["data"]["op"]
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-M23-ALL-SKILL-CHANNEL", not ok, reason)

    # 死亡证据
    d = copy.deepcopy(rows)
    d.append({"t_wall": 9999.0, "kind": "night-death",
              "data": {"health": 0}})
    ok, reason = C.judge_g5(d, "s01")
    run("ST-G5-DEATH-DURING-NIGHT", not ok, reason)

    # ---------- judge-all(M24)----------
    man = {"ia_facts": "/nonexistent.json"}
    gates = C.judge_all(man)
    run("ST-ALL-M24-MISSING-GATES",
        all(v == (False, "evidence-missing") for v in gates.values())
        and len(gates) >= 11, json.dumps(
            {k: v for k, v in gates.items() if v[0]})[:120])

    passed = sum(1 for _, ok in RESULTS if ok)
    print("SUMMARY %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
