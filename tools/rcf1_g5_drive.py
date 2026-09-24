# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D G5 技能驱动器:LLM 决策的机械执行层。

R3D/R01:mine_blocks 重写为 rcf1_mine_skill 核心的 LIVE 适配层——
失败驱动的候选/站位推进、同组合 ≤2 次盲发上限、总预算覆盖嵌套
子动作、会话失效有界上抛。离线回归见 tools/rcf1_mine_skill_tests.py。
"""
import argparse
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_g5 as G  # noqa: E402
import rcf1_env as E  # noqa: E402

MODEL_CHANNEL = G.MODEL_CHANNEL


def _log_path(run_id):
    return G._log_path(run_id)


def act(run_id, s, op, args, note, timeout=180):
    """机械技能子步。R3C/M23:技能子步必须标为子步——channel=
    "skill:<op>",不得冒充 LLM 调用;模型决策走 rcf1_g5.cmd_act。
    同步记录 pre/post 库存(checker 从原始事实重算采集量)。"""
    import rcf1_facts as F
    pre = F.observe_facts(s)
    ex, err = s.submit(op, args, tag="g5r3-%s" % run_id)
    if ex is None:
        receipt = {"state": "failed",
                   "reason": json.dumps(err, ensure_ascii=False)}
    else:
        receipt, _ = s.term(ex, timeout_s=timeout)
    post = F.observe_facts(s)
    G._append(run_id, "act", {
        "model_channel": "skill:%s" % op,
        "decision_note": note, "op": op, "args": args,
        "submit": {"execution_id": ex} if ex else {"error": str(err)[:120]},
        "receipt": receipt, "t_wall": round(time.time(), 3),
        "pre_inv": pre.get("inventory"),
        "post_inv": post.get("inventory"),
        "pre_pos": pre.get("position"), "post_pos": post.get("position"),
        "game_session": post.get("game_session")})
    return receipt


def obs_inv(s):
    import rcf1_facts as F
    return F.inv_counts_of(F.observe_facts(s))


def _player_yz(s):
    """Bob 实际地面坐标(yz)。失败抛错,不默认原点(R3 已证坑)。"""
    obs = ((s.observe().get("data") or {}).get("observation") or {})
    pos = obs.get("position") or {}
    y = pos.get("y")
    z = pos.get("z")
    if y is None or z is None:
        raise RuntimeError("player-position-unavailable")
    return int(y), int(z)


def _local_blocks(s):
    """inspect_local(10,"all") 原始方块表;感知错误返回 None。"""
    loc = s.inspect_local(10, "all")
    if not loc.get("ok"):
        return None
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    return snap.get("blocks") or []


def _visible_candidates(s, block_id, near, blocks=None):
    """可见方块候选(感知 only,非机会):near 锚点距离排序。"""
    if blocks is None:
        blocks = _local_blocks(s)
        if blocks is None:
            return None
    cands = []
    for b in blocks:
        if not str(b.get("block", "")).endswith(
                block_id.split(":")[-1]):
            continue
        if not b.get("line_of_sight"):
            continue
        p = b.get("position") or {}
        xyz = (int(p.get("x", 0)), int(p.get("y", 0)),
               int(p.get("z", 0)))
        if near and (abs(xyz[0] - near[0]) > 6
                     or abs(xyz[1] - near[1]) > 8
                     or abs(xyz[2] - near[2]) > 6):
            continue
        cands.append((sum(abs(xyz[i] - near[i]) for i in range(2))
                      + abs(xyz[2] - near[2]), xyz))
    cands.sort()
    return [c[1] for c in cands]


def _opp_at(s, block_id, xyz, wait_s):
    """等机会出生在指定格;返回机会条目或 None。"""
    t0 = time.time()
    while time.time() - t0 < wait_s:
        loc = s.inspect_local(10, "summary")
        snap = ((loc.get("data") or {}).get("snapshot") or {})
        if isinstance(snap, str):
            snap = json.loads(snap)
        cont = snap.get("opportunities") or {}
        entries = (cont.get("entries") if isinstance(cont, dict)
                   else cont) or []
        found = [e for e in entries if isinstance(e, dict)
                 and e.get("block") == block_id
                 and (e.get("x"), e.get("y"), e.get("z")) == xyz]
        if found:
            return found[0]
        time.sleep(0.8)
    return None


def sweep_look(run_id, s, py, leg_timeout=40):
    """R3C:4 方位走查扫视——跟踪器只收录准星/视野扫过的方块。
    转身是正常感知行为,不是透视;每段面向下一路点。"""
    pxz = None
    obs = ((s.observe().get("data") or {}).get("observation") or {})
    pos = obs.get("position") or {}
    px, pz = int(pos.get("x", 0)), int(pos.get("z", 0))
    leg_timeout = max(10, min(40, int(leg_timeout)))
    for dx, dz in ((0, -1), (1, 0), (0, 1), (-1, 0)):
        act(run_id, s, "goto",
            {"x": px + dx * 5, "y": py, "z": pz + dz * 5},
            "走查扫视 dx=%d dz=%d" % (dx, dz), timeout=leg_timeout)
    act(run_id, s, "goto", {"x": px, "y": py, "z": pz},
        "回到扫视中心", timeout=leg_timeout)


# 站位格可通行集合(几何预检;只做"确定占用则跳过"的负向过滤,
# 不放宽任何真实准入——漏判的占用仍会经 goto 失败进入组合禁令)
_PASSABLE = {
    "air", "cave_air", "void_air", "grass", "short_grass", "tall_grass",
    "fern", "large_fern", "dead_bush", "dandelion", "poppy",
    "oxeye_daisy", "cornflower", "azure_bluet", "allium", "white_tulip",
    "red_tulip", "orange_tulip", "pink_tulip", "blue_orchid",
    "lily_of_the_valley", "torch", "vine", "glow_lichen", "seagrass",
    "tall_seagrass", "kelp", "kelp_plant", "sugar_cane",
}


class LiveMineEnv(object):
    """rcf1_mine_skill 核心的 LIVE 适配(Env 协议)。"""

    def __init__(self, run_id, s, block_id, near):
        self.run_id = run_id
        self.s = s
        self.block_id = block_id
        self.near = near
        self._blocks = None

    def now(self):
        return time.time()

    def sleep(self, sec):
        time.sleep(sec)

    def diag(self, what, **data):
        G._append(self.run_id, "skill-diag", dict(what=what, **data))

    def candidates(self):
        blocks = _local_blocks(self.s)
        if blocks is None:
            self._blocks = None
            return None
        self._blocks = blocks
        return _visible_candidates(self.s, self.block_id, self.near,
                                   blocks=blocks)

    def fingerprint(self):
        """局部几何指纹:全部已感知方块 (xyz,block) 摘要。
        遮挡/支撑等几何变化 ⇒ 指纹变化 ⇒ 解除组合禁令。"""
        if not self._blocks:
            return None
        import hashlib
        sig = sorted(
            "%d,%d,%d=%s" % (
                int((b.get("position") or {}).get("x", 0)),
                int((b.get("position") or {}).get("y", 0)),
                int((b.get("position") or {}).get("z", 0)),
                b.get("block"))
            for b in self._blocks)
        return hashlib.sha256("|".join(sig).encode()).hexdigest()[:16]

    def ground_py(self):
        py, _pz = _player_yz(self.s)
        return py

    def _cell_block(self, x, y, z):
        for b in self._blocks or []:
            p = b.get("position") or {}
            if (int(p.get("x", 0)), int(p.get("y", 0)),
                    int(p.get("z", 0))) == (x, y, z):
                return str(b.get("block") or "")
        return None

    def _stance_occupied(self, xyz, stance):
        _n, (dx, dz) = stance
        py = self.ground_py()
        for y in (py, py + 1):
            blk = self._cell_block(xyz[0] + dx, y, xyz[2] + dz)
            if blk and blk.split(":")[-1] not in _PASSABLE:
                return blk
        return None

    def nav(self, xyz, stance, timeout_s):
        name, (dx, dz) = stance
        occ = self._stance_occupied(xyz, stance)
        if occ:
            return {"state": "failed",
                    "reason": "stance-cell-occupied:%s" % occ}
        py = self.ground_py()
        return act(self.run_id, self.s, "goto", {
            "x": xyz[0] + dx, "y": py, "z": xyz[2] + dz,
            "face_x": xyz[0], "face_y": xyz[1], "face_z": xyz[2]},
            "goto-face %s 站位%s" % (xyz, name), timeout=timeout_s)

    def opp_wait(self, xyz, wait_s):
        return _opp_at(self.s, self.block_id, xyz, wait_s)

    def mine(self, opp, timeout_s):
        return act(self.run_id, self.s, "mine_opportunity",
                   {"id": opp.get("object_id")},
                   "挖%s@合法机会" % self.block_id, timeout=timeout_s)

    def mined_count(self, receipt, xyz):
        return 1 if str(receipt.get("state") or "").lower() \
            == "completed" else 0

    def sweep(self, cap_s):
        py = self.ground_py()
        sweep_look(self.run_id, self.s, py,
                   leg_timeout=max(10, int(cap_s // 5)))


def mine_blocks(run_id, s, block_id, near, need, note, budget_s=420):
    """R3D/R01:采集技能 = rcf1_mine_skill 核心 + LIVE 适配。
    返回结果 dict(state/got/attempts/phase_s/combo_fails/...);
    state ∈ completed | blocked | budget | session-lost。"""
    import rcf1_mine_skill as MS
    env = LiveMineEnv(run_id, s, block_id, near)
    res = MS.run(env, need, budget_s)
    G._append(run_id, "skill-result", {
        "skill": "mine_blocks", "block": block_id, "need": need,
        "note": note, "budget_s": budget_s, "result": res})
    return res


def cmd_mine_wood(a):
    s = play.Session("g5r3-%s" % a.run_id)
    res = mine_blocks(a.run_id, s, a.block, tuple(a.near), a.need,
                      a.note, budget_s=a.budget)
    print(json.dumps({"mined": res.get("got"), "state": res.get("state"),
                      "reason": str(res.get("reason", ""))[:200]}))
    return 0 if res.get("state") == "completed" else 1


def cmd_craft(a):
    s = play.Session("g5r3-%s" % a.run_id)
    r = act(a.run_id, s, "craft",
            {"item": a.item, "count": a.count}, a.note, timeout=240)
    print(json.dumps({"state": r.get("state"),
                      "reason": str(r.get("reason"))[:100]}))


def cmd_act(a):
    s = play.Session("g5r3-%s" % a.run_id)
    r = act(a.run_id, s, a.op, json.loads(a.args), a.note,
            timeout=a.timeout)
    print(json.dumps({"state": r.get("state"),
                      "reason": str(r.get("reason"))[:150]}))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd")
    ap.add_argument("run_id")
    ap.add_argument("rest", nargs="*")
    ap.add_argument("--note", default="")
    ap.add_argument("--block", default="minecraft:oak_log")
    ap.add_argument("--near", nargs=3, type=int,
                    default=[0, 96, -70])
    ap.add_argument("--need", type=int, default=1)
    ap.add_argument("--budget", type=int, default=420)
    ap.add_argument("--count", type=int, default=1)
    ap.add_argument("--args", default="{}")
    ap.add_argument("--timeout", type=int, default=180)
    a = ap.parse_args()
    if a.cmd == "mine":
        return cmd_mine_wood(a)
    if a.cmd == "craft":
        return cmd_craft(a)
    if a.cmd == "act":
        return cmd_act(a)
    ap.error("unknown")


if __name__ == "__main__":
    sys.exit(main())
