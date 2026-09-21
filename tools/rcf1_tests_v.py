# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 V 组反例 + 最终 checker 一致性验证。

- V10:调用真实 G5 避难策略模块 tools/rcf1_shelter.py(生产代码),
  注入 place/mine 回执;不得在测试里重写"正确的 if"(审查 R01)。
- checker 一致性:最终判定入口 tools/rcf1_checker.py 的 judge_ia/judge_g4
  判定真实正例与其语义变异副本;详细反测试在 rcf1_checker_selftest.py,
  此处验证最终报告确实由同一入口产生。
- V01–V09:需要 LIVE 环境(注入执行结果/事件测试服务端证明函数),
  离线运行时如实报告 NOT_RUN,不伪造通过。

用法:
  python tools/rcf1_tests_v.py            # 离线部分(V10 + checker 一致性)
  python tools/rcf1_tests_v.py --live     # LIVE 部分(环境在线时)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import rcf1_checker as CK  # noqa: E402
import rcf1_shelter as SH  # noqa: E402

RESULTS = []


def run(tid, ok, detail=""):
    RESULTS.append((tid, bool(ok)))
    print(json.dumps({"id": tid, "pass": bool(ok),
                      "detail": str(detail)[:300]}, ensure_ascii=False))


def not_run(tid, why):
    RESULTS.append((tid, None))
    print(json.dumps({"id": tid, "pass": None, "not_run": True,
                      "why": why}, ensure_ascii=False))


# ---------- V10:真实避难策略负例/正例 ----------

def v10_real_strategy():
    # V10a:place 返回 failed 但不抛异常 → 真实策略不得置 sheltered
    r = SH.ShelterRun()
    r.record_result("dig_in", {"state": "completed",
                               "reason": "server_authoritative_block_gone"})
    ok1 = r.record_result("seal", {"state": "failed",
                                   "reason": "real_client_execution_timeout"})
    sheltered = r.finalize({"solid_above": True, "open_sides": 0,
                            "inside_enclosure": True})
    run("V10a-place-failed-not-sheltered",
        ok1 is False and sheltered is False)

    # V10b:伪造 completed 文案但物理状态未封闭 → 不得 sheltered
    r2 = SH.ShelterRun()
    r2.record_result("dig_in", {"state": "completed", "reason": "ok"})
    r2.record_result("seal", {"state": "completed",
                              "reason": "server_authoritative_block_placed"})
    sheltered2 = r2.finalize({"solid_above": False, "open_sides": 2,
                              "inside_enclosure": True})
    run("V10b-fake-completed-unsealed-not-sheltered",
        sheltered2 is False)

    # V10c:前置挖洞/走入失败 → 不得 sheltered
    r3 = SH.ShelterRun()
    r3.record_result("dig_in", {"state": "failed",
                                "reason": "real_client_resource_opportunity_stale"})
    r3.record_result("seal", {"state": "completed", "reason": "x"})
    run("V10c-pre-dig-failed-not-sheltered",
        r3.finalize({"solid_above": True, "open_sides": 0,
                     "inside_enclosure": True}) is False)

    # V10d:部分效果(dig 完成、封口未做)→ 不得 sheltered
    r4 = SH.ShelterRun()
    r4.record_result("dig_in", {"state": "completed", "reason": "ok"})
    run("V10d-partial-effects-not-sheltered",
        r4.finalize({"solid_above": True, "open_sides": 0,
                     "inside_enclosure": True}) is False)

    # V10e:完整正例:关键步全部真实 completed + 世界条件成立 → sheltered
    r5 = SH.ShelterRun()
    r5.record_result("dig_in", {"state": "completed", "reason": "ok"})
    r5.record_result("seal", {"state": "completed", "reason": "ok"})
    run("V10e-full-positive-sheltered",
        r5.finalize({"solid_above": True, "open_sides": 0,
                     "inside_enclosure": True}) is True)

    # V10f:黄昏判定与计划动作来自同一策略模块
    run("V10f-dusk-triggers-shelter-plan",
        SH.should_start_shelter("dusk") is True
        and SH.plan_shelter_action({"solid_above": False, "open_sides": 4,
                                    "inside_enclosure": False})["action"]
        == "dig_in")


# ---------- checker 一致性:最终入口判正例与变异 ----------

def checker_consistency():
    sys.path.insert(0, HERE)
    import rcf1_checker_selftest as ST
    ST.RESULTS.clear()
    ST.historical_counterexamples()
    ST.semantic_mutations()
    passed = sum(1 for _, ok in ST.RESULTS if ok)
    run("CHK-final-entry-rejects-history-and-mutations",
        passed == len(ST.RESULTS),
        "selftest %d/%d via judge_ia/judge_g4" % (passed, len(ST.RESULTS)))
    # 最终入口 CLI 也可用(报告由同一入口产生)
    import subprocess
    doc = {"cases": [{"id": "A01", "results": [
        {"state": "completed", "reason": "server_authoritative"}]}]}
    tmp = os.path.join(HERE, "__chk_tmp.json")
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(doc, fh)
    try:
        p = subprocess.run([sys.executable, os.path.join(HERE, "rcf1_checker.py"),
                            "judge-ia", tmp], capture_output=True, text=True)
        out = json.loads(p.stdout)
        run("CHK-cli-entry-rejects-counterexample",
            p.returncode == 1 and out["accept"] is False)
    finally:
        os.unlink(tmp)


# ---------- V01–V09:LIVE 注入反例(需要环境在线) ----------

LIVE_CASES = {
    "V01": "craft 32 只得 8 → 拒绝 completed(单位=物品数)",
    "V02": "移动 7 件目标已有 10 无变化 → 不算移动成功",
    "V03": "外部抢先放块无库存消耗 → 不归 Bob 放置成功",
    "V04": "食物外部取走/饥饿外部变化 → 不归 Bob 进食成功",
    "V05": "错屏调用个人 move/2×2 → 无错屏点击",
    "V06": "pathing 中 cancel/pause/timeout → 输入与路径全停",
    "V07": "导航设置篡改/组件缺失 → 拒绝而非继续",
    "V08": "目标已变/掉落被拿走 → 不换目标不冒充获取",
    "V09": "工作台墙后/同类型异屏 → 无 3×3 捷径",
}


def live_cases():
    live = "--live" in sys.argv
    for tid, desc in LIVE_CASES.items():
        if not live:
            not_run(tid, "需要 LIVE 环境:%s(离线不伪造通过)" % desc)
            continue
        # LIVE 注入实现在 rcf1_tests_live_v.py(阶段3 与 I/A 回归同场执行)
        try:
            import rcf1_tests_live_v as LV
            LV.run_case(tid, run)
        except ImportError:
            not_run(tid, "live harness 未部署")


def main():
    v10_real_strategy()
    checker_consistency()
    live_cases()
    scored = [ok for _, ok in RESULTS if ok is not None]
    passed = sum(1 for ok in scored if ok)
    print("SUMMARY %d/%d (%d NOT_RUN)"
          % (passed, len(scored), len(RESULTS) - len(scored)))
    return 0 if passed == len(scored) else 1


if __name__ == "__main__":
    sys.exit(main())
