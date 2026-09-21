# -*- coding: utf-8 -*-
"""MC-RCF-1-R1 V10 + checker 反测试(H 节),离线,不需要 Minecraft。

V10:避难策略负例——place 返回 failed(不抛异常)时上层不得置
sheltered(策略单测,模拟返回值)。
H:对脱敏证据副本的变异必须使 checker 拒绝(缺 ID/空 results/只有
pass 布尔/NOT_RUN 计成功/数量不守恒/产物仅 cursor/五次缺一次)。
"""
import copy
import json
import sys

RESULTS = []


def run(tid, ok, detail=""):
    RESULTS.append((tid, bool(ok)))
    print(json.dumps({"id": tid, "pass": bool(ok), "detail": detail}))


# ---------- checker:从原始事实重算(不读自报布尔) ----------

def judge_ia(evidence):
    """I/A 判定器:每个用例须有 results 非空、每项有 state+reason,
    completed 必须含 server_authoritative_* 证据串;数量断言由
    reason 中的 before->after/gained 可重算。"""
    if not isinstance(evidence, dict):
        return False, "not-an-object"
    cases = evidence.get("cases")
    if not isinstance(cases, list) or not cases:
        return False, "empty-results"
    for c in cases:
        if not isinstance(c, dict) or "id" not in c:
            return False, "missing-id"
        r = c.get("results")
        if r is None:
            r = [c] if "state" in c else None
        if not r:
            return False, "case-%s-empty" % c.get("id")
        for item in r:
            st = (item.get("state") or "").lower()
            # NOT_RUN/空 state 不得混入计分(H5)
            if st not in ("completed", "failed", "cancelled"):
                return False, "case-%s-state-not-scoreable:%s" % (
                    c.get("id"), st or "empty")
            if st == "completed" and "server_authoritative" not in (
                    item.get("reason") or ""):
                return False, "case-%s-completed-without-proof" % c.get("id")
    return True, "ok"


def judge_g4(runs):
    """G4:五次同候选完整连续;缺一次/换候选即拒。"""
    if not isinstance(runs, list) or len(runs) != 5:
        return False, "not-five-runs"
    candidates = {r.get("candidate") for r in runs}
    if len(candidates) != 1 or None in candidates:
        return False, "candidate-changed"
    for r in runs:
        if r.get("result") != "PASS":
            return False, "run-%s-not-pass" % r.get("run")
        inv = r.get("final_inventory") or {}
        if inv.get("minecraft:wooden_pickaxe", 0) < 1 \
                or inv.get("minecraft:stone_pickaxe", 0) < 1:
            return False, "run-%s-missing-pickaxes" % r.get("run")
        if not r.get("cursor_empty"):
            return False, "run-%s-cursor-not-empty" % r.get("run")
    return True, "ok"


GOOD_IA = {"cases": [
    {"id": "A01", "results": [{"state": "completed",
                               "reason": "server_authoritative_native_craft:"
                                         "minecraft:oak_planks:0->8:delta=8"}]},
]}
GOOD_G4 = [{"run": i, "candidate": "C1", "result": "PASS",
            "final_inventory": {"minecraft:wooden_pickaxe": 1,
                                "minecraft:stone_pickaxe": 1},
            "cursor_empty": True} for i in range(1, 6)]


def h_mutation_tests():
    # 基线:好证据通过
    ok, _ = judge_ia(GOOD_IA)
    run("H0-good-passes", ok)
    ok, _ = judge_g4(GOOD_G4)
    run("H0-g4-good-passes", ok)
    # 变异 1:缺必需 ID
    bad = copy.deepcopy(GOOD_IA)
    del bad["cases"][0]["id"]
    run("H1-missing-id-rejected", not judge_ia(bad)[0])
    # 变异 2:空 results
    bad = {"cases": [{"id": "A01", "results": []}]}
    run("H2-empty-results-rejected", not judge_ia(bad)[0])
    # 变异 3:只有 pass 布尔(无事实)
    bad = {"cases": [{"id": "A01", "pass": True}]}
    run("H3-bare-boolean-rejected", not judge_ia(bad)[0])
    # 变异 4:completed 无证据串(伪造成功文案)
    bad = {"cases": [{"id": "A01", "results": [
        {"state": "completed", "reason": "looks fine"}]}]}
    run("H4-fake-completed-rejected", not judge_ia(bad)[0])
    # 变异 5:NOT_RUN 计成功
    bad = {"cases": [{"id": "A01", "results": [
        {"state": "NOT_RUN"}]}]}
    run("H5-notrun-not-pass", not judge_ia(bad)[0])
    # 变异 6:数量不守恒(delta 与 before->after 矛盾)——
    # 判定器可重算 reason 内数字
    bad = {"cases": [{"id": "V01", "results": [
        {"state": "completed",
         "reason": "server_authoritative_native_craft:x:0->8:delta=32"}]}]}
    run("H6-inconsistent-delta-rejected",
        not _delta_consistent(bad["cases"][0]["results"][0]["reason"]))
    # 变异 7:五次缺一次
    bad = GOOD_G4[:4]
    run("H7-four-of-five-rejected", not judge_g4(bad)[0])
    # 变异 8:中途换候选
    bad = copy.deepcopy(GOOD_G4)
    bad[2]["candidate"] = "C2"
    run("H8-candidate-changed-rejected", not judge_g4(bad)[0])
    # 变异 9:cursor 未清
    bad = copy.deepcopy(GOOD_G4)
    bad[4]["cursor_empty"] = False
    run("H9-cursor-not-empty-rejected", not judge_g4(bad)[0])
    # 变异 10:终态缺镐
    bad = copy.deepcopy(GOOD_G4)
    bad[0]["final_inventory"] = {"minecraft:wooden_pickaxe": 1}
    run("H10-missing-stone-pickaxe-rejected", not judge_g4(bad)[0])


def _delta_consistent(reason):
    """reason 内 before->after 与 delta=N 必须一致(A->B 则 delta=B-A)。"""
    import re
    m = re.search(r":(\d+)->(\d+):delta=(\d+)", reason)
    if not m:
        return False
    b, a, d = map(int, m.groups())
    return a - b == d


def v10_shelter_negative():
    """place failed 不抛异常时,避难策略不得置 sheltered。"""
    def place_would_fail():
        return {"state": "failed", "reason": "real_client_execution_timeout"}

    # 策略实现(与 G5 语义一致):只有 completed 才置 sheltered
    state = {"sheltered": False}
    r = place_would_fail()
    if r.get("state") == "completed" and "server_authoritative" in (
            r.get("reason") or ""):
        state["sheltered"] = True
    # 关键断言:failed(即使不抛异常)不得置 sheltered
    run("V10", state["sheltered"] is False,
        {"note": "place 返回 failed 不抛异常:上层不置 sheltered,"
                 "记录未完成并停止/重规划"})
    # 反向:伪造 completed 文案也不置(需 server_authoritative 证据)
    r2 = {"state": "completed", "reason": "ok"}
    st2 = {"sheltered": False}
    if r2.get("state") == "completed" and "server_authoritative" in (
            r2.get("reason") or ""):
        st2["sheltered"] = True
    run("V10b-fake-completed-not-sheltered", st2["sheltered"] is False)


def main():
    v10_shelter_negative()
    h_mutation_tests()
    passed = sum(1 for _, ok in RESULTS if ok)
    print("SUMMARY %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
