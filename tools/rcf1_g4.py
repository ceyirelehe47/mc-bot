# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 G4 核心链 runner(B01-B05 + 非计分诊断)。

R3/F02 修复要点(对照审查 ff29078):
- session/candidate 身份来自真实运行时握手(observe/status 的
  game_session、control_session_epoch、jar SHA-256、event 区间),
  不再是 run_id+秒数或硬编码提交 SHA。
- 回执完整落盘,不截断 reason。
- cursor_empty / no_unresolved_unknown 由终态真实观察派生:
  cursor←终局 observe 的 screen.cursor_count;unknown←run 区间内
  无 outcome_unknown 回执且结束时 active_execution 为空。
- 每次限时 720s 在 runner 内实际执行(逐步检查,超时中断当前执行)。
"""
import json
import subprocess
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402
import rcf1_facts as F  # noqa: E402

OPP_WAIT_S = 12          # 转向后等待机会出生的上限
MINE_TIMEOUT = 150
CRAFT_TIMEOUT = 240
RUN_LIMIT_S = 720        # 矩阵原文:每次从首次受控观察起 12 分钟


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def now():
    return round(time.time(), 3)


def git_commit():
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True,
            cwd=r"D:\code\mc-bot").stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


class Chain:
    """一次 run 的全部事实采集(judge_g4 证据直接来源)。"""

    def __init__(self, run_id, session):
        self.run_id = run_id
        self.session = session
        self.started_wall = time.time()
        self.limit_deadline = None   # 首次受控观察后设定
        self.events = []
        self.receipts = []           # 完整未截断回执
        self.snapshots = []          # 关键观察快照(pre/终态等)
        self.facts = F.FactsCollector(session, "g4-%s" % run_id)
        self.fail_at = None
        self.final_observe = None
        self.status_end = None

    def evt(self, kind, **kw):
        row = {"t": round(time.time() - self.started_wall, 3),
               "kind": kind}
        row.update(kw)
        self.events.append(row)
        print(json.dumps({"run": self.run_id, "evt": row},
                         ensure_ascii=False), flush=True)

    def receipt(self, op, args, terminal, execution_id=None):
        self.receipts.append({
            "op": op, "args": args,
            "execution_id": execution_id,
            "terminal": F._strip_secrets(terminal),
            "t_wall": now(),
            "t_rel": round(time.time() - self.started_wall, 3)})
        reason = str(terminal.get("reason") or "")
        self.evt("receipt", op=op, state=terminal.get("state"),
                 reason=reason)

    def snap(self, tag):
        s = F.observe_facts(self.session)
        self.snapshots.append({"tag": tag, "facts": s})
        return s

    def stop(self, where, detail=None):
        self.fail_at = where
        self.evt("stop", at=where, detail=str(detail)[:400])

    def time_up(self):
        return (self.limit_deadline is not None
                and time.time() > self.limit_deadline)

    def doc(self):
        """事实文档(judge_g4 输入)。不写任何 passed=true 判定。"""
        self.final_observe = self.snap("final")
        self.status_end = F.status_facts()
        ident = dict(self.facts.identity)
        ident.update({
            "candidate_commit": git_commit(),
            "run_started_wall": self.started_wall,
            "run_limit_s": RUN_LIMIT_S,
            "receipt_count": len(self.receipts),
        })
        return {
            "schema": "mc.rcf1r3.g4run.v1",
            "run_id": self.run_id,
            "identity": ident,
            "events": self.events,
            "receipts": self.receipts,
            "snapshots": self.snapshots,
            "oracle_blocks": self.facts.cases[0]["oracle_blocks"]
            if self.facts.cases else {},
            "fail_at": self.fail_at,
            "ended_wall": now(),
            # R3C/M03:结束 status 是必需事实(缺 ≠ 无未决动作)
            "status_end": self.status_end,
        }

def inventory(s):
    return F.inv_counts_of(F.observe_facts(s))


def player_pos(s):
    obs = s.observe().get("data", {}) \
        .get("observation", {})
    pos = obs.get("position") or {}
    # R3 修复:observe 异常/缺字段时绝不默认 (0,0,0)——那会把
    # goto 目标发到世界原点,把 Bob 派出 107 格(实测石面阶段
    # facing_timeout 的根因)。缺数据 = 诚实失败。
    if not all(k in pos for k in ("x", "y", "z")):
        raise RuntimeError("player-position-unavailable")
    return (int(pos["x"]), int(pos["y"]), int(pos["z"]))


def _local_snapshot(s, chain=None):
    """inspect-local 快照;错误(503/超时/降级)返回 None——绝不把
    错误响应当成'空场景'(R3 实测:执行风暴期查询失败被当 no-visible
    死循环 7 分钟)。"""
    loc = s.inspect_local(10, "all")
    if not loc.get("ok"):
        if chain is not None:
            chain.evt("inspect-error",
                      error=str(loc.get("error"))[:120])
        return None
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    return snap


def visible_blocks(s, block_suffix, near=None, limit=24, window=None,
                   chain=None):
    """只读感知里的可见方块候选(awareness_only,不是机会)。
    查询错误返回 None(调用方区分错误与真空)。"""
    snap = _local_snapshot(s, chain)
    if snap is None:
        return None
    blocks = snap.get("blocks") or []
    cands = []
    for b in blocks:
        if not str(b.get("block", "")).endswith(block_suffix):
            continue
        if not b.get("line_of_sight"):
            continue
        p = b.get("position") or {}
        xyz = (int(p.get("x", 0)), int(p.get("y", 0)), int(p.get("z", 0)))
        if window:
            dx = abs(xyz[0] - near[0]); dy = abs(xyz[1] - near[1])
            dz = abs(xyz[2] - near[2])
            if dx > window[0] or dy > window[1] or dz > window[2]:
                continue
        elif near:
            if (abs(xyz[0] - near[0]) > 6 or abs(xyz[1] - near[1]) > 8
                    or abs(xyz[2] - near[2]) > 6):
                continue
        cands.append((sum(abs(a - b) for a, b in zip(xyz, near))
                      if near else 0, xyz, b.get("object_id", "")))
    # R3(修正):按与声明锚点的距离排序(R2 语义)。自顶向下排序
    # 实测不可行——近距站位对柱状目标的中心瞄准会先命中更低格,
    # 高格只能从特定距离外瞄准且超出客户端准星触达(4.5 格)。
    cands.sort()
    return cands[:limit]


def opportunities(s, block_id=None, at=None):
    loc = s.inspect_local(10, "summary")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    res = []
    container = snap.get("opportunities") or []
    if isinstance(container, dict):
        entries = container.get("entries") or []
    else:
        entries = []
        for o in container:
            if isinstance(o, dict) and o.get("entries"):
                entries.extend(o["entries"])
            else:
                entries.append(o)
    for e in entries:
        if not isinstance(e, dict):
            continue
        if block_id and e.get("block") != block_id:
            continue
        if at and (abs(e.get("x", 0) - at[0]) > 2
                   or abs(e.get("y", 0) - at[1]) > 2
                   or abs(e.get("z", 0) - at[2]) > 2):
            continue
        res.append(e)
    return res


def do_op(s, chain, op, args, timeout):
    ex_id, err = s.submit(op, args, tag="g4-%s" % chain.run_id)
    if ex_id is None:
        receipt = {"state": "failed",
                   "reason": json.dumps(err, ensure_ascii=False)}
        chain.receipt(op, args, receipt, execution_id=None)
        return receipt
    res, _trail = s.term(ex_id, timeout_s=timeout)
    chain.receipt(op, args, res, execution_id=ex_id)
    return res


def expected_item_of(block_id):
    """挖掘掉落物:石类→圆石,其余同 ID。"""
    return ("minecraft:cobblestone" if block_id == "minecraft:stone"
            else block_id)


def acquire_and_mine(s, chain, block_id, near, need, label,
                     pick_block=None, window=None):
    """合法链采集:感知选目标 → goto(face) → 等机会出生 → mine。
    mine 回执失败但物理链迟效完成(块已空+期望物品入包)时,
    以迟效对账计数并记录事件——receipt 保留 failed,不改写。"""
    got = 0
    no_vis = 0
    inspect_errors = 0
    deadline = time.time() + 420
    while got < need and time.time() < deadline and not chain.time_up():
        cands = visible_blocks(
            s, pick_block or block_id, near=near, window=window,
            chain=chain)
        if cands is None:
            inspect_errors += 1
            if inspect_errors >= 10:
                chain.stop("inspect-loop-errors")
                return got
            time.sleep(2)
            continue
        inspect_errors = 0
        if not cands:
            chain.evt("no-visible-candidate", label=label)
            no_vis += 1
            if no_vis >= 5 and near:
                r = do_op(s, chain, "goto", {
                    "x": near[0], "y": near[1] - 2, "z": near[2]},
                    timeout=60)
                chain.evt("reapproach-fixture", state=r.get("state"))
                no_vis = 0
            time.sleep(2)
            continue
        no_vis = 0
        try:
            _ppx, py_now, _ppz = player_pos(s)
        except RuntimeError:
            chain.evt("player-position-unavailable", label=label)
            time.sleep(2)
            continue
        # R3:客户端准星触达(4.5 格)过滤——站位眼高 py+1.6,命中点
        # 竖直分量 ≤ ~3.7 ⇒ 目标格 y ≤ py+4;更高的格子即使可见也
        # 无法从地面准入/瞄准(实测 112 顶格 facing_timeout 根因)。
        cands = [c for c in cands if c[1][1] <= py_now + 4]
        if not cands:
            chain.evt("no-reachable-candidate", label=label)
            time.sleep(2)
            continue
        _d, xyz, _oid = cands[0]
        inv_before = inventory(s).get(expected_item_of(block_id), 0)
        try:
            _px, py, _pz = player_pos(s)
        except RuntimeError:
            chain.evt("player-position-unavailable", label=label)
            time.sleep(2)
            continue
        # R3:主站位=目标正南 3 格(同 Bob 地面高度)。实测规律:目标
        # 位于南向(yaw≈0)时 facing/crosshair 全部通过;东/北向在当前
        # 客户端旋转链路下必败(serverYaw 与 sensor headYaw 分叉)。
        # 南向站位同时满足柱状目标的可瞄准几何(命中点落在目标格内)。
        r = do_op(s, chain, "goto", {
            "x": xyz[0], "y": py, "z": xyz[2] - 3,
            "face_x": xyz[0], "face_y": xyz[1], "face_z": xyz[2]},
            timeout=90)
        if r.get("state") != "completed":
            chain.evt("goto-face-failed", label=label,
                      reason=str(r.get("reason"))[:400])
            time.sleep(2)
            continue
        opp = None
        t0 = time.time()
        while time.time() - t0 < OPP_WAIT_S:
            found = opportunities(s, block_id=block_id, at=xyz)
            if found:
                opp = found[0]
                break
            time.sleep(0.8)
        if not opp:
            # R2 行为(R3 重写时丢失,实测高枝机会不出生根因):
            # 当前站位下客户端准星(4.5 格触达)够不到目标——按
            # 4 方位邻位轮试重新站位再等机会。
            for dx, dz in ((0, -2), (0, -3), (0, -4), (0, 2),
                        (-2, 0), (2, 0)):
                if chain.time_up():
                    break
                try:
                    _px2, py2, _pz2 = player_pos(s)
                except RuntimeError:
                    break
                r = do_op(s, chain, "goto", {
                    "x": xyz[0] + dx, "y": py2, "z": xyz[2] + dz,
                    "face_x": xyz[0], "face_y": xyz[1],
                    "face_z": xyz[2]}, timeout=60)
                if r.get("state") != "completed":
                    continue
                t1 = time.time()
                while time.time() - t1 < 6:
                    found = opportunities(s, block_id=block_id, at=xyz)
                    if found:
                        opp = found[0]
                        break
                    time.sleep(0.8)
                if opp:
                    chain.evt("stance-rotation-born", at=xyz,
                              stance=[xyz[0] + dx, py2, xyz[2] + dz])
                    break
        if not opp:
            chain.evt("opportunity-not-born", label=label, at=xyz)
            continue
        r = do_op(s, chain, "mine_opportunity",
                  {"id": opp.get("object_id")}, timeout=MINE_TIMEOUT)
        if r.get("state") == "completed":
            got += 1
            chain.evt("mined", label=label, at=xyz, n=got)
        else:
            # 迟效对账:回执失败,但块已消失且物品入包(掉落拾取晚于
            # 回执判定的竞态)——物理完成,receipt 保持 failed。
            time.sleep(6)
            still = any(c[1] == xyz for c in (visible_blocks(
                s, pick_block or block_id, near=near, window=window,
                chain=chain) or []))
            inv_after = inventory(s).get(
                expected_item_of(block_id), 0)
            if not still and inv_after >= inv_before + 1:
                got += 1
                chain.evt("mined-late-reconciled", label=label,
                          at=xyz, n=got, receipt_state=r.get("state"),
                          inv_delta=inv_after - inv_before)
            elif not still:
                # 块已空但物品未入包(掉落滞留):走到原格拾取,有界
                # 重查;拾取不到如实记录(物品留在世界)。
                do_op(s, chain, "goto", {
                    "x": xyz[0], "y": max(xyz[1] - 2, 60),
                    "z": xyz[2]}, timeout=45)
                time.sleep(2)
                inv_pick = inventory(s).get(
                    expected_item_of(block_id), 0)
                if inv_pick >= inv_before + 1:
                    got += 1
                    chain.evt("mined-drop-picked", label=label,
                              at=xyz, n=got)
                else:
                    chain.evt("mine-drop-lost", label=label, at=xyz)
            else:
                chain.evt("mine-failed", label=label,
                          reason=str(r.get("reason"))[:400])
    return got

def craft(s, chain, item, count):
    r = do_op(s, chain, "craft", {"item": item, "count": count},
              timeout=CRAFT_TIMEOUT)
    return r.get("state") == "completed", r


def run_core(run_id, fixture, diagnostic=False):
    t0 = time.time()
    # ---------- fixture(armed 前,准备期允许) ----------
    for cmd in fixture["pre"]:
        rcon(cmd)
    time.sleep(3)
    s = play.Session("g4-%s" % run_id)
    chain = Chain(run_id, s)
    chain.facts.case(run_id)
    chain.evt("session-open")

    chain.snap("pre")
    inv0 = inventory(s)
    chain.evt("initial-inventory", inv=inv0)
    if inv0:
        chain.stop("initial-inventory-not-empty", inv0)
        return chain
    # 首次受控观察 → 限时起算(矩阵:从执行器取得控制并开始首次观察计时)
    chain.limit_deadline = time.time() + RUN_LIMIT_S
    chain.evt("armed-timer-start", limit_s=RUN_LIMIT_S)

    pre_logs = 0
    if fixture.get("layout_perturb"):
        if not perturb_layout(s, chain):
            chain.stop("layout-perturb-failed")
            return chain
        pre_logs = 1
    wood_log = ("minecraft:oak_log" if fixture.get("wood") == "oak"
                else "minecraft:%s_log" % fixture.get("wood"))
    need = fixture["logs_need"] - (pre_logs if not fixture.get(
        "layout_perturb") else 0)
    if fixture.get("layout_perturb"):
        need = fixture["logs_need"] - 1
    logs = pre_logs + acquire_and_mine(s, chain, wood_log,
                                       fixture["tree_near"], need,
                                       "logs")
    # R3:采集计数与物理库存对账——初态空包+封闭 fixture 下,库存中
    # 的原木只能来自本次真实挖掘/拾取(迟效拾取可能晚于回执窗口)。
    # 布局扰动 run:扰动用木已真实合成为 4 板,按 板/4 折算。
    inv_logs = inventory(s).get(wood_log, 0)
    eff_logs = inv_logs
    if fixture.get("layout_perturb"):
        eff_logs += inventory(s).get("minecraft:oak_planks", 0) // 4
    if eff_logs >= fixture["logs_need"] and logs < fixture["logs_need"]:
        chain.evt("logs-reconciled-from-inventory",
                  counted=logs, inv=inv_logs, effective=eff_logs,
                  note="receipt-counted<inventory; provenance=empty-initial"
                       "+real-mine-drops")
        logs = eff_logs
    chain.evt("logs-collected", n=logs)
    chain.snap("after-logs")
    if chain.time_up():
        chain.stop("run-time-limit")
        return chain
    if logs < fixture["logs_need"]:
        # R3 诚实性修复:原木不足必须记为失败(fail_at),否则被当
        # PASS 计入矩阵(实测 b05 4/5 原木静默通过的漏洞)。
        chain.stop("logs-insufficient:%d/%d"
                   % (logs, fixture["logs_need"]))
        return chain

    ok, _ = craft(s, chain, "minecraft:%s_planks" % fixture["wood"],
                  16 if fixture.get("layout_perturb") else 20)
    if not ok:
        return chain
    ok, _ = craft(s, chain, "minecraft:stick", 8)
    if not ok:
        return chain
    ok, _ = craft(s, chain, "minecraft:crafting_table", 1)
    if not ok:
        return chain

    tx, ty, tz = fixture["table_pos"]
    px, py, pz = fixture["table_stand"]
    r = do_op(s, chain, "goto", {"x": px, "y": py, "z": pz},
              timeout=90)
    if r.get("state") != "completed":
        chain.stop("goto-table-stand", r.get("reason"))
        return chain
    r = do_op(s, chain, "place", {"x": tx, "y": ty, "z": tz,
                                  "item": "minecraft:crafting_table"},
              timeout=90)
    chain.facts.oracle_block(run_id, "table", tx, ty, tz,
                             rcon("execute if block %d %d %d "
                                  "minecraft:crafting_table"
                                  % (tx, ty, tz)))
    if r.get("state") != "completed":
        chain.stop("place-table", r.get("reason"))
        return chain

    ok, _ = craft(s, chain, "minecraft:wooden_pickaxe", 1)
    if not ok:
        return chain

    stone = acquire_and_mine(s, chain, "minecraft:stone",
                             fixture["stone_near"], fixture["stone_need"],
                             "stone", window=(3, 2, 3))
    if chain.time_up():
        chain.stop("run-time-limit")
        return chain
    if stone < fixture["stone_need"]:
        return chain
    px, py, pz = fixture["table_stand"]
    # R3:回台重试=3 次,先小步挪动(卡位根因:采石坑边缘 pathing
    # 偶发卡死;实测 b03 两次 90s 超时)再回站位。
    for attempt in range(3):
        if attempt > 0:
            hop = [(px + 1, pz + 1), (px - 1, pz - 1),
                   (px + 1, pz - 1)][(attempt - 1) % 3]
            do_op(s, chain, "goto", {"x": hop[0], "y": py, "z": hop[1]},
                  timeout=45)
        r = do_op(s, chain, "goto", {"x": px, "y": py, "z": pz},
                  timeout=120)
        if r.get("state") == "completed":
            break
        chain.evt("goto-table-retry", attempt=attempt)
    if r.get("state") != "completed":
        chain.stop("goto-table-return", r.get("reason"))
        return chain
    ok, _ = craft(s, chain, "minecraft:stone_pickaxe", 1)
    if not ok:
        return chain

    # 重新打开原工作台:再合成一把木镐证明台仍在精确位置可用
    ok, _ = craft(s, chain, "minecraft:wooden_pickaxe", 1)
    if not ok:
        return chain

    chain.evt("final-begin")
    return chain


FIXTURES = {
    # 受控局部场景:暴露树干、桌面平台、声明石露头(armed 前布置)
    "oak1": {
        "wood": "oak", "logs_need": 5, "stone_need": 3,
        "tree_near": (8, 109, 8), "stone_near": (11, 107, 2),
        "table_pos": (8, 107, 2), "table_stand": (8, 107, 3),
        "pre": [
            # 暴露树干柱(y107-112,无侧叶遮挡视线;叶帽只在 113)
            "tp Bob 8.5 107 -0.5",
        ] + ["setblock 8 %d %d minecraft:air" % (y, z)
             for y in range(107, 114) for z in range(2, 8)]
        + ["setblock 8 %d 8 minecraft:oak_log" % y
           for y in range(107, 114)]
        + ["setblock %d 114 %d minecraft:oak_leaves" % (8 + dx, 8 + dz)
           for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
        + ["setblock 8 114 8 minecraft:oak_log"]
        # 石面:受控露头——清出空气后放置自然石(armed 前声明)
        + ["setblock %d %d %d minecraft:air" % (x, y, z)
           for x in (10, 11, 12) for y in (107, 108, 109)
           for z in (2, 3)]
        + ["setblock %d 106 %d minecraft:dirt" % (x, z)
           for x in (10, 11, 12) for z in (2, 3)]
        + ["setblock %d 107 %d minecraft:stone" % (x, z)
           for x in (10, 11, 12) for z in (2, 3)]
        # 桌面平台:泥土台(非石族,不会成为采集候选;石料只来自
        # 声明的露头),目标/站位均有支撑
        + ["setblock 8 105 %d minecraft:dirt" % z for z in (1, 2, 3)]
        + ["setblock 8 107 2 minecraft:air",
           "setblock 8 106 4 minecraft:stone",
           "tp Bob 8.5 107 4.5",
           "time set day", "weather clear", "clear Bob"],
    },
}


FIXTURES["oak1"]["layout_perturb"] = False
FIXTURES["birch1"] = dict(FIXTURES["oak1"], wood="birch")
FIXTURES["birch1"]["pre"] = [c.replace("minecraft:oak_log",
                                       "minecraft:birch_log")
                             for c in FIXTURES["oak1"]["pre"]]
# 非默认快捷栏/堆叠布局:同橡木几何,armed 后先真实调槽扰动
FIXTURES["oak-layout"] = dict(FIXTURES["oak1"], layout_perturb=True)


def perturb_layout(s, chain):
    """B05 场景:空包开局后用真实事务扰动布局——先采 1 木,
    craft 4 板后 move_items 至非默认快捷栏槽,再消耗掉。"""
    got = acquire_and_mine(s, chain, "minecraft:oak_log",
                           (8, 109, 8), 1, "perturb")
    if got < 1:
        return False
    r = do_op(s, chain, "craft",
              {"item": "minecraft:oak_planks", "count": 4}, 240)
    if r.get("state") != "completed":
        return False
    r = do_op(s, chain, "move_items",
              {"item": "minecraft:oak_planks", "count": 4, "hotbar": 7}, 60)
    return r.get("state") == "completed"


def main():
    diagnostic = "--diagnostic" in sys.argv
    if diagnostic:
        chain = run_core("diag", FIXTURES["oak1"], True)
        result = chain.doc()
        out = r"D:\mc-rcf1-raw\g4-diag-r3.json"
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(result, fh, ensure_ascii=False, indent=1)
        print(json.dumps({"RESULT": {"run": "diag",
                                     "fail_at": result["fail_at"],
                                     "saved": out}}, ensure_ascii=False),
              flush=True)
        return 0 if result["fail_at"] is None else 1
    # B01-B05 预写定矩阵:两橡木、两白桦、一非默认布局
    matrix = [("b01", "oak1"), ("b02", "oak1"),
              ("b03", "birch1"), ("b04", "birch1"),
              ("b05", "oak-layout")]
    expect = {
        "logs_need": 5, "stone_need": 3,
        "wood_item": "minecraft:oak_log",
        "crafts": {
            "minecraft:stick": 8,
            "minecraft:crafting_table": 1,
            "minecraft:wooden_pickaxe": 2,
            "minecraft:stone_pickaxe": 1}}
    runs = []
    import time as _t
    stamp = _t.strftime("%Y%m%d-%H%M%S")
    for run_id, fixture_key in matrix:
        chain = run_core(run_id, FIXTURES[fixture_key], False)
        doc = chain.doc()
        runs.append(doc)
        out = r"D:\mc-rcf1-raw\g4-%s-%s.json" % (run_id, stamp)
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(doc, fh, ensure_ascii=False, indent=1)
        print(json.dumps({"RESULT": {
            "run": run_id, "fail_at": doc["fail_at"],
            "receipts": len(doc["receipts"]), "saved": out}},
            ensure_ascii=False), flush=True)
        if doc["fail_at"] is not None:
            break  # 保存失败,不挑选零散成功
    import os as _os
    g4_out = _os.path.join(_os.environ.get(
        "RCF1_RAW", r"D:\mc-rcf1-raw"), "g4-runs-r3.json")
    with open(g4_out, "w",
              encoding="utf-8") as fh:
        json.dump({"matrix": matrix, "expect": expect,
                   "runs": runs}, fh,
                  ensure_ascii=False, indent=1)
    ok = len(runs) == 5 and all(r["fail_at"] is None for r in runs)
    print("G4 %s" % ("5/5 runs complete" if ok
                     else "FAILED at %d/5" % len(runs)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
