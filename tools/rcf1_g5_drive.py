# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 G5 技能驱动器:LLM 决策的机械执行层。

每个子命令执行一组语义操作并把 LLM 决策行(model_channel+note+
完整回执)追加到 g5r3-<run>.jsonl——与 rcf1_g5.py 的 act 行同格式,
judge_g5 同入口判定。决策由会话 LLM 下达;本层不含隐藏信息通道。
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


def _visible_candidates(s, block_id, near):
    """可见方块候选(感知 only,非机会):near 锚点距离排序。"""
    loc = s.inspect_local(10, "all")
    if not loc.get("ok"):
        return None
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    cands = []
    for b in snap.get("blocks") or []:
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


def _goto_face(act_, run_id, s, xyz, py, timeout=90):
    """南向主站位(目标正南 3 格、Bob 地面高)+ goto(face)。
    返回 goto 终态回执。R3C/S01 修复:站位 y 用 Bob 实际地面高,
    不再用目标 y-2(嵌坡不可达→Baritone 搜索失败→原地站立→死亡)。
    """
    return act_(run_id, s, "goto", {
        "x": xyz[0], "y": py, "z": xyz[2] - 3,
        "face_x": xyz[0], "face_y": xyz[1], "face_z": xyz[2]},
        "goto-face %s 南向站位" % (xyz,), timeout=timeout)


def sweep_look(run_id, s, py):
    """R3C:4 方位转身扫视——跟踪器只收录准星/视野扫过的方块,
    不转身的新位置感知为空(实测基点树冠遮挡+未扫视=193 次
    no-reachable-candidate)。转身是正常感知行为,不是透视。"""
    pxz = None
    obs = ((s.observe().get("data") or {}).get("observation") or {})
    pos = obs.get("position") or {}
    px, pz = int(pos.get("x", 0)), int(pos.get("z", 0))
    # R3C:静态转身只录取最终朝向的视锥;行走环路才能连续扫过
    # 视野(实测站定扫视仅 3 块,走查 14 块)。4 段短走,每段面向
    # 下一路点。
    for dx, dz in ((0, -1), (1, 0), (0, 1), (-1, 0)):
        act(run_id, s, "goto",
            {"x": px + dx * 5, "y": py, "z": pz + dz * 5},
            "走查扫视 dx=%d dz=%d" % (dx, dz), timeout=40)
    act(run_id, s, "goto", {"x": px, "y": py, "z": pz},
        "回到扫视中心", timeout=40)


def mine_blocks(run_id, s, block_id, near, need, note, budget_s=420):
    """goto(face)→机会→mine 循环。R3C:同 rcf1_g4 语义——
    距离排序候选、触达过滤(目标 y ≤ py+4)、南向主站位(Bob 地面高)、
    机会不出生时 4 方位轮试、goto 失败换下一候选。"""
    got = 0
    t0 = time.time()
    errors = 0
    empty_rounds = 0
    while got < need and time.time() - t0 < budget_s:
        try:
            cands = _visible_candidates(s, block_id, near)
            py, _pz = _player_yz(s)
        except Exception as exc:  # noqa: BLE001
            errors += 1
            G._append(run_id, "skill-diag", {
                "what": "mine_blocks-percept-error",
                "error": str(exc)[:120]})
            if errors >= 10:
                return got
            time.sleep(2)
            continue
        errors = 0
        if cands is None:
            time.sleep(2)
            continue
        # 触达过滤:站位眼高 py+1.6,4.5 格准星竖直分量 ≤~3.7
        # ⇒ 目标格 y ≤ py+4(R3 实测 112 顶格 facing_timeout 根因)
        cands = [c for c in cands if c[1] <= py + 4]
        if not cands:
            G._append(run_id, "skill-diag", {
                "what": "no-reachable-candidate", "py": py})
            empty_rounds += 1
            if empty_rounds % 3 == 1:
                # 连续空轮:转身扫视再读(正常感知,非透视)
                sweep_look(run_id, s, py)
            time.sleep(2)
            continue
        empty_rounds = 0
        xyz = cands[0]
        r = _goto_face(act, run_id, s, xyz, py)
        if r.get("state") != "completed":
            # R3C:facing 超时若因更低格遮挡,换下一候选(不重试同格)
            G._append(run_id, "skill-diag", {
                "what": "goto-face-failed", "at": list(xyz),
                "reason": str(r.get("reason"))[:200]})
            time.sleep(1)
            continue
        opp = _opp_at(s, block_id, xyz, 12)
        if opp is None:
            # 4 方位邻位轮试(机会不出生=当前站位准星够不到)
            for dx, dz in ((0, -2), (0, -4), (0, 2), (-2, 0), (2, 0)):
                try:
                    py2, _ = _player_yz(s)
                except RuntimeError:
                    break
                r2 = act(run_id, s, "goto", {
                    "x": xyz[0] + dx, "y": py2, "z": xyz[2] + dz,
                    "face_x": xyz[0], "face_y": xyz[1],
                    "face_z": xyz[2]},
                    "stance-rotate %s" % (xyz,), timeout=60)
                if r2.get("state") != "completed":
                    continue
                opp = _opp_at(s, block_id, xyz, 6)
                if opp:
                    G._append(run_id, "skill-diag", {
                        "what": "stance-rotation-born", "at": list(xyz)})
                    break
        if opp is None:
            G._append(run_id, "skill-diag", {
                "what": "opportunity-not-born", "at": list(xyz)})
            continue
        r = act(run_id, s, "mine_opportunity",
                {"id": opp.get("object_id")},
                "挖%s@%s(合法机会)" % (block_id, xyz), timeout=150)
        if r.get("state") == "completed":
            got += 1
    return got


def cmd_mine_wood(a):
    s = play.Session("g5r3-%s" % a.run_id)
    got = mine_blocks(a.run_id, s, a.block, tuple(a.near), a.need,
                      a.note)
    print(json.dumps({"mined": got}))


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
    ap.add_argument("--item", default="")
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
