# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D 采集技能核心(R01 修复):失败驱动的候选/站位推进。

LIVE 适配层(rcf1_g5_drive / rcf1_g4)与离线测试(rcf1_mine_skill_tests)
共用同一控制流;环境按 Env 协议注入,核心不 import 会话层。

行为合同(TASKBOOK §3.2/§3.3;ACCEPTANCE D01–D06 的判定依据):
- 失败组合 =(目标格 xyz, 站位名)。同组合连续 COMBO_LIMIT(2)次无净
  进展即禁;禁令仅因"新相关事实"(候选集/地面高指纹变化)解除,不因
  时间流逝、轮次或新 execution_id 解除。冷却到期本身不是重试理由。
- 候选推进真实发生:主候选全部可用站位失败后推进次候选,不重排序又
  取回首项;全部候选禁尽时有界返回上层(state=blocked),不无限轮询。
- goto 失败与机会不出生同权进入站位梯队;梯队穷尽才弃该候选。成功
  采集=净进展,重新观察世界(指纹变化自然解除禁令)。
- 总预算覆盖全部嵌套子动作:子动作超时 = min(默认, 剩余预算);剩余
  < MIN_NAV_S 不再提交导航,进入预算收尾。act/term 的同步提交-终态-
  超时取消语义保证收尾时无在途执行(旧请求不复活)。
- 观察持续空/错误有界;走查扫视有界(SWEEP_LIMIT);均不构成无限轮询。
- 阶段耗时(observe/nav/opp/mine/wait)与组合统计随结果返回,由同一
  原始时间线汇总,不另造报告系统。

Env 协议(鸭子类型;离线测试用 FakeEnv,LIVE 用各驱动器适配器):
  now() -> float
  sleep(s)
  diag(what, **data)                    # 诊断事件(进 run 日志)
  candidates() -> [xyz, ...] | None     # None=感知错误;调用方已排序
  ground_py() -> int                    # Bob 实际地面高;失联 raise
  nav(xyz, stance, timeout_s) -> receipt(dict)   # stance=(name,(dx,dz))
  opp_wait(xyz, wait_s) -> entry | None # 等机会出生在指定格
  mine(entry, timeout_s) -> receipt(dict)
  mined_count(receipt, xyz) -> int      # 适配器侧对账(completed/迟效)
  sweep(cap_s) -> None                  # 走查扫视(有界,感知行为)
"""
import time

# 同组合无净进展尝试上限:第 2 次失败后禁用该组合(不再第 3 次盲发)
COMBO_LIMIT = 2
# 站位梯队:主站位(目标正南 3 格,Bob 地面高)+ 4 方位邻位。
# R3 已证南向站位在当前旋转链路下 facing 可过;邻位应对遮挡/坡地。
STANCES = (
    ("primary", (0, -3)),
    ("s2", (0, -2)),
    ("s4", (0, -4)),
    ("n2", (0, 2)),
    ("w2", (-2, 0)),
    ("e2", (2, 0)),
)
MIN_NAV_S = 10          # 剩余预算低于此值不再提交导航(避免超短超时伪失败)
SWEEP_LIMIT = 2         # 每次技能调用的走查扫视上限
EMPTY_LIMIT = 4         # 扫视穷尽后仍无可见候选的空轮上限
PERCEPT_LIMIT = 10      # 感知错误上限
NAV_TIMEOUT_S = 90.0
OPP_WAIT_S = 12.0
MINE_TIMEOUT_S = 150.0

TERMINAL_STATES = ("completed", "failed", "cancelled", "rejected",
                   "outcome_unknown")


def _combo_key(xyz, stance_name):
    return "%d,%d,%d|%s" % (xyz[0], xyz[1], xyz[2], stance_name)


def _phase(res, key, dt):
    res["phase_s"][key] = round(res["phase_s"].get(key, 0.0) + dt, 3)


def _fail(env, res, fails, banned, xyz, stance_name, cls, reason):
    """记录一次组合失败;达 COMBO_LIMIT 即禁用并诊断。"""
    key = _combo_key(xyz, stance_name)
    ent = fails.setdefault(key, {"n": 0, "classes": []})
    ent["n"] += 1
    ent["classes"].append(cls)
    ent["last_reason"] = str(reason)[:200]
    env.diag("combo-failed", combo=key, cls=cls, n=ent["n"],
             reason=str(reason)[:200])
    if ent["n"] >= COMBO_LIMIT and key not in banned:
        banned.add(key)
        res["banned_combos"] += 1
        env.diag("combo-banned", combo=key, classes=ent["classes"])


def _attempt_round(env, res, st, reach_dy, fails, banned):
    """单轮:观察→指纹判定→候选×站位梯队→进展/禁尽处理。
    RuntimeError(会话/租约失效)向上抛给 run 统一转 session-lost。"""
    remaining = st["remaining"]
    # ---------- 观察 ----------
    t = env.now()
    cands = env.candidates()
    py = env.ground_py()
    _phase(res, "observe", env.now() - t)
    if cands is None:
        st["percept_err"] += 1
        env.diag("percept-error", n=st["percept_err"])
        if st["percept_err"] >= PERCEPT_LIMIT:
            res.update(state="blocked", reason="percept-errors")
            return
        t = env.now()
        env.sleep(2)
        _phase(res, "wait", env.now() - t)
        return
    # 触达过滤(必要条件,非充分:机会出生才是真实准星准入)
    cands = [c for c in cands if c[1] <= py + reach_dy]
    fp_extra = getattr(env, "fingerprint", None)
    new_fp = (tuple(cands), py,
              fp_extra() if callable(fp_extra) else None)
    if new_fp != st["fp"]:
        if banned:
            env.diag("bans-lifted-new-facts", n=len(banned))
            res["bans_lifted"] += len(banned)
            banned.clear()
            fails.clear()
        st["fp"] = new_fp
    if not cands:
        st["empty_rounds"] += 1
        if res["sweeps"] < SWEEP_LIMIT and remaining() > 60:
            res["sweeps"] += 1
            t = env.now()
            env.sweep(min(200.0, remaining()))
            _phase(res, "wait", env.now() - t)
            return
        if st["empty_rounds"] >= EMPTY_LIMIT:
            res.update(state="blocked", reason="no-visible-candidate")
            return
        env.diag("no-reachable-candidate", py=py)
        t = env.now()
        env.sleep(2)
        _phase(res, "wait", env.now() - t)
        return
    st["empty_rounds"] = 0
    st["percept_err"] = 0

    # ---------- 候选×站位 梯队 ----------
    progressed = False
    budget_out = False
    for xyz in cands:
        if res["got"] >= st["need"]:
            break
        if remaining() < MIN_NAV_S:
            budget_out = True
            break
        for stance in STANCES:
            key = _combo_key(xyz, stance[0])
            if key in banned:
                continue
            if remaining() < MIN_NAV_S:
                budget_out = True
                break
            t = env.now()
            r = env.nav(xyz, stance, min(NAV_TIMEOUT_S, remaining()))
            _phase(res, "nav", env.now() - t)
            res["attempts"] += 1
            if str(r.get("state") or "").lower() != "completed":
                _fail(env, res, fails, banned, xyz, stance[0],
                      "nav", r.get("reason"))
                continue
            t = env.now()
            opp = env.opp_wait(xyz, min(OPP_WAIT_S, max(1.0, remaining())))
            _phase(res, "opp", env.now() - t)
            if opp is None:
                _fail(env, res, fails, banned, xyz, stance[0],
                      "opp", "opportunity-not-born")
                continue
            t = env.now()
            r = env.mine(opp, min(MINE_TIMEOUT_S, max(5.0, remaining())))
            _phase(res, "mine", env.now() - t)
            n = int(env.mined_count(r, xyz) or 0)
            if n > 0:
                res["got"] += n
                env.diag("mined", at=list(xyz), stance=stance[0],
                         n=res["got"])
                progressed = True
                break
            _fail(env, res, fails, banned, xyz, stance[0],
                  "mine", r.get("reason"))
        if progressed or budget_out:
            break
    if budget_out:
        res.update(state="budget", reason="budget-low-before-nav")
        return
    if progressed:
        return  # 净进展:下一轮重新观察(指纹变化自然解除禁令)
    # 本轮无任何候选成功:全部组合被禁时,有界扫视一次或返回上层
    live_combos = any(
        _combo_key(c, s[0]) not in banned
        for c in cands for s in STANCES)
    if not live_combos:
        if res["sweeps"] < SWEEP_LIMIT and remaining() > 60:
            res["sweeps"] += 1
            t = env.now()
            env.sweep(min(200.0, remaining()))
            _phase(res, "wait", env.now() - t)
            return
        res.update(state="blocked", reason="all-combos-banned")
        return
    t = env.now()
    env.sleep(1)
    _phase(res, "wait", env.now() - t)


def run(env, need, budget_s, reach_dy=4):
    """采集技能主循环。返回结果 dict(见模块 docstring 的行为合同)。

    state: completed | blocked | budget | session-lost
    """
    res = {"state": "running", "reason": "", "got": 0, "attempts": 0,
           "phase_s": {"observe": 0.0, "nav": 0.0, "opp": 0.0,
                       "mine": 0.0, "wait": 0.0},
           "banned_combos": 0, "bans_lifted": 0, "sweeps": 0,
           "combo_fails": {}}
    deadline = env.now() + budget_s
    fails = {}
    banned = set()
    st = {"need": need, "fp": None, "percept_err": 0, "empty_rounds": 0,
          "remaining": lambda: deadline - env.now()}
    while res["got"] < need and res["state"] == "running":
        if st["remaining"]() <= 0:
            res.update(state="budget", reason="budget-exhausted")
            break
        try:
            _attempt_round(env, res, st, reach_dy, fails, banned)
        except RuntimeError as exc:
            # 会话/租约失效(观察/导航/挖掘钩子抛出):有界返回上层
            res.update(state="session-lost", reason=str(exc)[:200])
            break
    if res["state"] == "running":
        res["state"] = "completed" if res["got"] >= need else "blocked"
        res["reason"] = res["reason"] or (
            "" if res["got"] >= need else "need-not-met")
    res["combo_fails"] = {
        k: {"n": v["n"], "classes": v["classes"],
            "last_reason": v.get("last_reason", "")}
        for k, v in fails.items()}
    res["phase_s"] = {k: round(v, 3) for k, v in res["phase_s"].items()}
    return res


class Clock(object):
    """离线/在线统一时钟+sleep 计费(FakeEnv 用加速时钟)。"""

    def __init__(self, scale=1.0):
        self.scale = scale
        self._t = time.time()

    def now(self):
        return self._t

    def sleep(self, sec):
        self._t += sec * self.scale
