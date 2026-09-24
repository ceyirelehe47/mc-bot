# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D 采集技能核心离线测试(R01 回归;hermetic,无 LIVE)。

对照 ACCEPTANCE D01–D06 的控制流语义(真实 LIVE 切片另行执行):
- T1 正对照(D01):首项正常 → 采到,路径最短。
- T2 首项受阻、次项可用(D02):不饿死次候选;同组合尝试 ≤ COMBO_LIMIT。
- T3 goto 失败换站位(D03):主站位 nav 失败后,同一目标经替代站位采到。
- T4 全部不可达(D04):有界 blocked 返回;每组合提交 ≤ COMBO_LIMIT 次;
  扫视 ≤ SWEEP_LIMIT;总尝试有界。
- T5 总预算截断:剩余预算不足不再提交导航;子动作超时 ≤ 剩余。
- T6 会话失效(D05):钩子 raise → session-lost,立即返回。
- T7 环境变化解除禁令(D06):指纹变化后原禁目标重新候选并采到。
- T8 观察持续空:扫视穷尽后 blocked,轮次有界。
- T9 迟效对账:mine 回执 failed 但适配器对账计数 → 计入净进展。

用法:python tools/rcf1_mine_skill_tests.py  (退出 0=全部通过)
"""
import sys

sys.path.insert(0, "tools")
import rcf1_mine_skill as MS  # noqa: E402


class FakeClock(object):
    def __init__(self):
        self.t = 1000.0

    def now(self):
        return self.t

    def sleep(self, sec):
        self.t += sec


class FakeEnv(object):
    """可编排环境:cands/nav_ok/opp_ok/sweep_effect 控制行为。"""

    def __init__(self, cands, py=93):
        self.clock = FakeClock()
        self.cands = list(cands)
        self.py = py
        self.fp_val = "fp0"
        # (xyz, stance_name) -> True=nav completed / False=failed;
        # 缺省按 target_ok 判定
        self.nav_over = {}
        self.target_ok = {c: True for c in cands}
        self.opp_ok = {c: True for c in cands}
        # 站位几何占用(模拟预检):stance cell 被占 → 合成失败回执
        self.occupied = set()
        # 每次调用消耗的模拟秒
        self.nav_s = 5.0
        self.opp_s = 2.0
        self.opp_fail_s = 12.0
        self.mine_s = 3.0
        self.sweep_s = 20.0
        # 第 n 次扫视(1 起)后的效果:回调列表
        self.sweep_effects = []
        # 记录
        self.nav_calls = []      # (xyz, stance_name, timeout)
        self.mine_calls = []
        self.sweeps = 0
        self.diags = []
        self.mined_via_reconcile = False

    # ---- Env 协议 ----
    def now(self):
        return self.clock.now()

    def sleep(self, sec):
        self.clock.sleep(sec)

    def diag(self, what, **data):
        self.diags.append((what, data))

    def candidates(self):
        return list(self.cands)

    def ground_py(self):
        return self.py

    def fingerprint(self):
        return self.fp_val

    def _stance_cell(self, xyz, stance):
        _name, (dx, dz) = stance
        return (xyz[0] + dx, self.py, xyz[2] + dz)


    def nav(self, xyz, stance, timeout_s):
        # 站位几何预检发生在"提交"之前:占用格不产生副作用请求记录
        if self._stance_cell(xyz, stance) in self.occupied:
            self.clock.sleep(0.1)
            return {"state": "failed",
                    "reason": "stance-cell-occupied(precheck)"}
        self.clock.sleep(self.nav_s)
        self.nav_calls.append((xyz, stance[0], timeout_s))
        if (xyz, stance[0]) in self.nav_over:
            ok = self.nav_over[(xyz, stance[0])]
        else:
            ok = self.target_ok.get(xyz, True)
        if ok:
            return {"state": "completed"}
        return {"state": "failed",
                "reason": "client_final_facing_timeout:cross=block=..."}

    def opp_wait(self, xyz, wait_s):
        if self.opp_ok.get(xyz, True):
            self.clock.sleep(self.opp_s)
            return {"object_id": "opp-%s" % (xyz,)}
        self.clock.sleep(min(self.opp_fail_s, wait_s))
        return None

    def mine(self, opp, timeout_s):
        self.clock.sleep(self.mine_s)
        self.mine_calls.append((opp, timeout_s))
        if self.mined_via_reconcile:
            # 回执 failed 但物理链迟效完成(适配器负责对账)
            return {"state": "failed", "reason": "receipt-timeout-race"}
        return {"state": "completed"}

    def mined_count(self, receipt, xyz):
        if self.mined_via_reconcile:
            return 1
        return 1 if str(receipt.get("state")).lower() == "completed" else 0

    def sweep(self, cap_s):
        self.clock.sleep(self.sweep_s)
        self.sweeps += 1
        if self.sweep_effects:
            fn = self.sweep_effects.pop(0)
            fn(self)


def _key(xyz, stance_name):
    return MS._combo_key(xyz, stance_name)


def t1_positive():
    env = FakeEnv([(10, 95, -70)])
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 1, res
    assert len(env.nav_calls) == 1, env.nav_calls
    assert env.nav_calls[0][1] == "primary"
    assert res["attempts"] == 1
    return "T1 ok: 正常路径 primary 一次完成"


def t2_blocked_first_second_reachable():
    # D02:首候选 nav 全站位失败,次候选可用 → 必须采到次候选
    c1, c2 = (10, 95, -70), (12, 94, -69)
    env = FakeEnv([c1, c2])
    env.target_ok = {c1: False, c2: True}
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 1, res
    # 次候选被真实尝试(不是只打印"已切换")
    c2_navs = [c for c in env.nav_calls if c[0] == c2]
    assert c2_navs, "次候选从未被尝试"
    assert c2_navs[0][1] == "primary"
    # 同组合不超限:c1|primary 在本轮只 1 次(候选推进先于同候选重试)
    n_c1_primary = len([c for c in env.nav_calls
                        if c[0] == c1 and c[1] == "primary"])
    assert n_c1_primary == 1, env.nav_calls
    # c1 的站位梯队被逐个真实尝试
    stances_tried = {c[1] for c in env.nav_calls if c[0] == c1}
    assert stances_tried == {s[0] for s in MS.STANCES}, stances_tried
    return "T2 ok: 首候选 6 站位真实轮试后推进次候选并采到"


def t3_goto_fail_alt_stance_same_target():
    # D03:同一目标主站位被挡,替代站位可用 → 同目标采到
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    env.nav_over = {(c1, "primary"): False}
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 1, res
    names = [c[1] for c in env.nav_calls]
    assert names[0] == "primary" and names[1] == "s2", names
    return "T3 ok: goto 失败分支进入替代站位并采到同目标"


def t4_all_blocked_bounded():
    # D04:全部候选/站位失败 → 有界 blocked;无第三次同组合盲发
    c1, c2 = (10, 95, -70), (12, 94, -69)
    env = FakeEnv([c1, c2])
    env.target_ok = {c1: False, c2: False}
    res = MS.run(env, 1, 420)
    assert res["state"] == "blocked", res
    assert res["reason"] == "all-combos-banned", res
    assert res["got"] == 0
    # 每组合 ≤ COMBO_LIMIT 次
    counts = {}
    for xyz, st, _t in env.nav_calls:
        counts[(xyz, st)] = counts.get((xyz, st), 0) + 1
    assert all(v <= MS.COMBO_LIMIT for v in counts.values()), counts
    assert env.sweeps <= MS.SWEEP_LIMIT
    # 总尝试有界:2 候选×6 站位×2 次 + 扫视后不再新增
    assert len(env.nav_calls) <= 2 * len(MS.STANCES) * MS.COMBO_LIMIT, \
        len(env.nav_calls)
    return "T4 ok: 全禁有界返回,组合尝试 ≤%d,扫视 ≤%d" % (
        MS.COMBO_LIMIT, MS.SWEEP_LIMIT)


def t5_budget_truncation():
    # D05 预算面:剩余不足不再提交导航;子超时 ≤ 剩余预算
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    env.nav_s = 30.0
    env.target_ok = {c1: False}
    res = MS.run(env, 1, 100)
    assert res["state"] == "budget", res
    for _xyz, _st, to in env.nav_calls:
        assert to <= 100.0, env.nav_calls
        assert to <= MS.NAV_TIMEOUT_S
    # 预算耗尽后没有进一步提交
    n = len(env.nav_calls)
    assert n >= 1 and n <= 4, n
    return "T5 ok: 剩余预算截断子动作,耗尽即收尾"


def t6_session_lost():
    # D05 会话面:钩子 RuntimeError → session-lost 快速返回
    env = FakeEnv([(10, 95, -70)])

    def boom():
        raise RuntimeError("player-position-unavailable")

    env.ground_py = boom
    res = MS.run(env, 1, 420)
    assert res["state"] == "session-lost", res
    assert "player-position-unavailable" in res["reason"]
    return "T6 ok: 会话失效有界上抛"


def t7_new_facts_lift_bans():
    # D06:禁令因新相关事实(指纹变化)解除,原目标重新候选并采到
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    env.target_ok = {c1: False}
    # 第 1 次扫视后:遮挡消失(指纹变化)+ 目标恢复可用
    def effect(e):
        e.fp_val = "fp1-geometry-changed"
        e.target_ok = {c1: True}
    env.sweep_effects = [effect]
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 1, res
    assert res["bans_lifted"] > 0, res
    lifted = [d for d, _ in env.diags if d == "bans-lifted-new-facts"]
    assert lifted, env.diags
    # 时间流逝本身不解除:禁令只在指纹变化时解除(此处由扫视带来)
    return "T7 ok: 新事实解除禁令后原目标恢复可用"


def t7b_time_alone_never_lifts():
    # 反向:无新事实时,禁令不因轮次/时间自动解除
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    env.target_ok = {c1: False}
    env.sweep_effects = [lambda e: None, lambda e: None]  # 扫视无效果
    res = MS.run(env, 1, 420)
    assert res["state"] == "blocked", res
    assert res["bans_lifted"] == 0, res
    return "T7b ok: 无新事实禁令不解除"


def t8_empty_observation_bounded():
    env = FakeEnv([])
    res = MS.run(env, 1, 420)
    assert res["state"] == "blocked", res
    assert res["reason"] == "no-visible-candidate", res
    assert env.sweeps == MS.SWEEP_LIMIT
    # 空轮有界:EMPTY_LIMIT 轮内返回
    empty_diags = [d for d, _ in env.diags if d == "no-reachable-candidate"]
    assert len(empty_diags) <= MS.EMPTY_LIMIT, len(empty_diags)
    return "T8 ok: 观察持续空→扫视穷尽→有界 blocked"


def t9_late_reconciled_mine():
    # G4 语义:mine 回执 failed 但适配器迟效对账 → 计入净进展
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    env.mined_via_reconcile = True
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 1, res
    return "T9 ok: 迟效对账计入净进展"


def t10_stance_precheck_skips_occupied():
    # 站位几何预检:占用格直接跳过(合成失败,不提交),不浪费槽位
    c1 = (10, 95, -70)
    env = FakeEnv([c1])
    # primary 站位格被占:(10, 93, -73)
    env.occupied = {(10, 93, -73)}
    res = MS.run(env, 1, 420)
    assert res["state"] == "completed", res
    names = [c[1] for c in env.nav_calls]
    assert "primary" not in names, names
    assert names[0] == "s2", names
    return "T10 ok: 占用站位预检跳过"


def t11_multi_block_progress_unbans_rest():
    # need=2:第一根成功后指纹变化,清禁;第二根照常采到
    c1, c2 = (10, 95, -70), (12, 94, -69)
    env = FakeEnv([c1, c2])
    env.target_ok = {c1: True, c2: True}
    # c1 的 primary 失败一次后 s2 成功(模拟部分遮挡)
    env.nav_over = {(c1, "primary"): False}
    res = MS.run(env, 2, 420)
    assert res["state"] == "completed", res
    assert res["got"] == 2, res
    mined = [d for d, v in env.diags if d == "mined"]
    assert len(mined) == 2, env.diags
    return "T11 ok: 多根采集推进正常"


def main():
    results = []
    for fn in (t1_positive, t2_blocked_first_second_reachable,
               t3_goto_fail_alt_stance_same_target, t4_all_blocked_bounded,
               t5_budget_truncation, t6_session_lost,
               t7_new_facts_lift_bans, t7b_time_alone_never_lifts,
               t8_empty_observation_bounded, t9_late_reconciled_mine,
               t10_stance_precheck_skips_occupied,
               t11_multi_block_progress_unbans_rest):
        try:
            results.append((fn.__name__, fn(), True))
        except AssertionError as exc:
            results.append((fn.__name__, "FAIL: %r" % (exc,), False))
        except Exception as exc:  # noqa: BLE001
            results.append((fn.__name__,
                            "ERROR: %s: %s" % (exc.__class__.__name__, exc),
                            False))
    ok = all(r[2] for r in results)
    for name, msg, passed in results:
        print("%s %s: %s" % ("PASS" if passed else "FAIL", name, msg))
    print("== %d/%d passed ==" % (sum(1 for r in results if r[2]),
                                  len(results)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
