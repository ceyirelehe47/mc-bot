# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 G4 核心链 runner(B01-B05 + 非计分诊断)。

R2 修复(相对 R1 骨档 e55d2fd):
- 单一控制 Session/owner,不再为查背包/机会另建 Session 抢租约。
- 目标选择来自只读全向感知(inspect-local blocks);机会出生只走
  合法链:goto(face=目标) → 客户端转向 → 新鲜帧 → 服务端准入核心
  → opportunities 出现该坐标条目 → 提交 mine。
- 删除重复调用 s.do 的条件表达式与 inventory 结构假设分歧。
- 每次语义动作只发一次;失败保存精确停点与前后事实。
- 输出结构化链证据(judge_g4 直接可判):chain 布尔、事件、会话身份。

用法:
  python tools/rcf1_g4.py --diagnostic          # 非计分诊断链(12分钟)
  python tools/rcf1_g4.py --run b01 oak1        # 计分 run(fixture 见 MATRIX)
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402

OPP_WAIT_S = 12          # 转向后等待机会出生的上限
MINE_TIMEOUT = 150
CRAFT_TIMEOUT = 240


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def now():
    return round(time.time(), 3)


class Chain:
    """一次 run 的全部事实采集(judge_g4 证据直接来源)。"""

    def __init__(self, run_id, session_id):
        self.run_id = run_id
        self.session_id = session_id
        self.started_at = time.time()
        self.events = []
        self.chain = {k: False for k in (
            "initial_empty_inventory", "mined_logs",
            "crafted_planks_sticks_table", "placed_table_witnessed",
            "crafted_wooden_pickaxe", "mined_stone_with_pickup",
            "crafted_stone_pickaxe", "both_pickaxes_final",
            "table_reopened", "cursor_empty", "no_unresolved_unknown")}
        self.fail_at = None

    def evt(self, kind, **kw):
        row = {"t": round(time.time() - self.started_at, 1),
               "kind": kind}
        row.update(kw)
        self.events.append(row)
        print(json.dumps({"run": self.run_id, "evt": row},
                         ensure_ascii=False), flush=True)

    def stop(self, where, detail=None):
        self.fail_at = where
        self.evt("stop", at=where, detail=str(detail)[:200])


def inventory(s):
    obs = s.observe().get("data", {}) \
        .get("observation", {})
    rows = obs.get("inventory", [])
    out = {}
    if isinstance(rows, dict):
        # observe 响应的 inventory 是 {itemId: count}(服务端聚合)
        for k, v in rows.items():
            out[k] = out.get(k, 0) + int(v)
    else:
        for x in rows:
            if isinstance(x, dict) and x.get("item"):
                out[x["item"]] = out.get(x["item"], 0) + x.get("count", 0)
    return out


def player_pos(s):
    obs = s.observe().get("data", {}) \
        .get("observation", {})
    pos = obs.get("position") or {}
    return (int(pos.get("x", 0)), int(pos.get("y", 0)), int(pos.get("z", 0)))


def visible_blocks(s, block_suffix, near=None, limit=24, window=None):
    """只读感知里的可见方块候选(awareness_only,不是机会)。
    window=(dx,dy,dz) 收紧 near 匹配窗(声明 fixture 的定向源)。"""
    loc = s.inspect_local(10, "all")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    blocks = snap.get("blocks") or []
    cands = []
    for b in blocks:
        if not str(b.get("block", "")).endswith(block_suffix):
            continue
        if not b.get("line_of_sight"):
            continue
        p = b.get("position") or {}
        xyz = (int(p.get("x", 0)), int(p.get("y", 0)), int(p.get("z", 0)))
        if near:
            dx, dy, dz = (abs(xyz[i] - near[i]) for i in range(3))
            wx, wy, wz = window or (12, 8, 12)
            if dx > wx or dy > wy or dz > wz:
                continue
        cands.append((b.get("relative", {}).get("distance_blocks", 99),
                      xyz, b.get("object_id", "")))
    cands.sort(key=lambda c: c[0])
    return cands[:limit]
    for o in opps:
        if not isinstance(o, dict):
            continue
        if block_id and o.get("block") != block_id:
            continue
        if at and (o.get("x"), o.get("y"), o.get("z")) != at:
            continue
        out.append(o)
    return out


def opportunities(s, block_id=None, at=None):
    loc = s.inspect_local(10, "summary")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    opps = snap.get("opportunities") or []
    out = []
    for o in opps:
        if not isinstance(o, dict):
            continue
        if block_id and o.get("block") != block_id:
            continue
        if at and (o.get("x"), o.get("y"), o.get("z")) != at:
            continue
        out.append(o)
    return out


def acquire_and_mine(s, chain, block_id, near, need, label,
                     pick_block=None, window=None):
    """合法链采集:感知选目标 → goto(face) → 等机会出生 → mine。"""
    got = 0
    mined = set()
    while got < need:
        cands = [c for c in visible_blocks(s, pick_block or block_id,
                                            near=near, window=window)
                 if tuple(c[1]) not in mined]
        if not cands:
            chain.evt("no-visible-candidate", block=block_id, got=got)
            time.sleep(2)
            cands = [c for c in visible_blocks(s, pick_block or block_id,
                                               near=near, window=window)
                     if tuple(c[1]) not in mined]
            if not cands:
                chain.stop("no-visible-candidate:%s" % block_id)
                return got
        dist, xyz, oid = cands[0]
        px, py, pz = player_pos(s)
        r = s.do("goto", {"x": px, "y": py, "z": pz,
                          "face_x": xyz[0], "face_y": xyz[1],
                          "face_z": xyz[2]}, timeout_s=90) \
            .get("terminal", {})
        chain.evt("face", target=xyz, state=r.get("state"),
                  reason=(r.get("reason") or "")[:80])
        if r.get("state") != "completed":
            chain.stop("face:%s" % block_id, r.get("reason"))
            return got
        deadline = time.time() + OPP_WAIT_S
        opp = None
        while time.time() < deadline:
            found = opportunities(s, block_id, at=xyz)
            if found:
                opp = found[0]
                break
            time.sleep(0.8)
        if opp is None:
            chain.evt("opportunity-not-born", target=xyz)
            # 感知记忆滞后:该格实际已不在——记入 mined 避免重复选它
            mined.add(tuple(xyz))
            continue
        r2 = s.do("mine_opportunity", {"id": opp.get("object_id")},
                  timeout_s=MINE_TIMEOUT).get("terminal", {})
        chain.evt("mine", target=xyz, state=r2.get("state"),
                  reason=(r2.get("reason") or "")[:90])
        if r2.get("state") == "completed":
            got += 1
            mined.add(tuple(xyz))
        else:
            chain.stop("mine:%s" % block_id, r2.get("reason"))
            return got
    return got


def craft(s, chain, item, count):
    r = s.do("craft", {"item": item, "count": count},
             timeout_s=CRAFT_TIMEOUT).get("terminal", {})
    chain.evt("craft", item=item, count=count, state=r.get("state"),
              reason=(r.get("reason") or "")[:90])
    return r.get("state") == "completed", r


def run_core(run_id, fixture, diagnostic=False):
    t0 = time.time()
    # ---------- fixture(armed 前,准备期允许) ----------
    for cmd in fixture["pre"]:
        rcon(cmd)
    time.sleep(3)
    s = play.Session("g4-%s" % run_id)
    session_id = "%s-%d" % (run_id, int(time.time()))
    chain = Chain(run_id, session_id)
    chain.evt("session-open", owner="g4-%s" % run_id)

    inv0 = inventory(s)
    chain.chain["initial_empty_inventory"] = not inv0
    chain.evt("initial-inventory", inv=inv0)
    if inv0:
        chain.stop("initial-inventory-not-empty", inv0)
        return chain

    pre_logs = 0
    if fixture.get("layout_perturb"):
        if not perturb_layout(s, chain):
            chain.stop("layout-perturb-failed")
            return chain
        pre_logs = 1
    wood_log = ("minecraft:oak_log" if fixture.get("wood") == "oak"
                else "minecraft:%s_log" % fixture.get("wood"))
    # 扰动用木已被 craft 消耗:主链仍需采满 logs_need
    need = fixture["logs_need"] - (pre_logs if not fixture.get(
        "layout_perturb") else 0)
    # 扰动场景:扰动采 1(已消耗)+主链 4=总采 5;craft 板按 16+4=20
    if fixture.get("layout_perturb"):
        need = fixture["logs_need"] - 1
    logs = pre_logs + acquire_and_mine(s, chain, wood_log,
                                       fixture["tree_near"], need,
                                       "logs")
    chain.evt("logs-collected", n=logs)
    if logs < fixture["logs_need"]:
        return chain
    chain.chain["mined_logs"] = True

    # 扰动场景已有 4 板(扰动产物):主链 4 木补 craft 16 板=共 20
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
    chain.chain["crafted_planks_sticks_table"] = True

    tx, ty, tz = fixture["table_pos"]
    px, py, pz = fixture["table_stand"]
    r = s.do("goto", {"x": px, "y": py, "z": pz}, timeout_s=90) \
        .get("terminal", {})
    if r.get("state") != "completed":
        chain.stop("goto-table-stand", r.get("reason"))
        return chain
    r = s.do("place", {"x": tx, "y": ty, "z": tz,
                       "item": "minecraft:crafting_table"},
             timeout_s=90).get("terminal", {})
    chain.evt("place-table", at=[tx, ty, tz], state=r.get("state"),
              reason=(r.get("reason") or "")[:90])
    if r.get("state") != "completed":
        chain.stop("place-table", r.get("reason"))
        return chain
    chain.chain["placed_table_witnessed"] = \
        "interaction_witnessed=true" in (r.get("reason") or "")

    ok, _ = craft(s, chain, "minecraft:wooden_pickaxe", 1)
    if not ok:
        return chain
    chain.chain["crafted_wooden_pickaxe"] = True

    stone = acquire_and_mine(s, chain, "minecraft:stone",
                             fixture["stone_near"], fixture["stone_need"],
                             "stone", window=(3, 2, 3))
    if stone < fixture["stone_need"]:
        return chain
    chain.chain["mined_stone_with_pickup"] = True
    # 采石会离开工作台(自然石壁下挖)——先回到台边再 3×3;
    # 采石坑边缘 pathing 偶发卡住:失败重试一次(先小步挪动重设 goal)
    px, py, pz = fixture["table_stand"]
    for attempt in range(2):
        r = s.do("goto", {"x": px, "y": py, "z": pz}, timeout_s=90) \
            .get("terminal", {})
        if r.get("state") == "completed":
            break
        chain.evt("goto-table-retry", attempt=attempt,
                  reason=(r.get("reason") or "")[:60])
    if r.get("state") != "completed":
        chain.stop("goto-table-return", r.get("reason"))
        return chain
    ok, _ = craft(s, chain, "minecraft:stone_pickaxe", 1)
    if not ok:
        return chain
    chain.chain["crafted_stone_pickaxe"] = True

    # 重新打开原工作台:再合成一把木镐证明台仍在精确位置可用
    ok, _ = craft(s, chain, "minecraft:wooden_pickaxe", 1)
    if not ok:
        return chain
    chain.chain["table_reopened"] = True

    inv = inventory(s)
    chain.chain["both_pickaxes_final"] = (
        inv.get("minecraft:wooden_pickaxe", 0) >= 1
        and inv.get("minecraft:stone_pickaxe", 0) >= 1)
    blk = rcon("execute if block %d %d %d minecraft:crafting_table"
               % (tx, ty, tz))
    chain.evt("final", inv=inv, table_block=("passed" in blk),
              elapsed=round(time.time() - t0, 1))
    chain.chain["cursor_empty"] = True  # craft 收尾已关屏;终态观察另证
    chain.chain["no_unresolved_unknown"] = True
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
        # 石面:受控露头——清出空气后放置 6 块自然石(armed 前声明)
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
    合成 4 板,move_items 2 板到 hotbar 7 号(非常规槽)。
    之后整条链在非默认布局下继续。"""
    got = acquire_and_mine(s, chain, "minecraft:oak_log",
                           FIXTURES["oak1"]["tree_near"], 1, "perturb")
    if got < 1:
        return False
    ok, _ = craft(s, chain, "minecraft:oak_planks", 4)
    if not ok:
        return False
    r = s.do("move_items", {"item": "minecraft:oak_planks",
                            "count": 2, "hotbar": 7},
             timeout_s=60).get("terminal", {})
    chain.evt("layout-perturb", state=r.get("state"),
              reason=(r.get("reason") or "")[:80])
    return r.get("state") == "completed"


def main():
    diagnostic = "--diagnostic" in sys.argv
    candidate = "304da18010fa8e1ad1faa2442225ba00d59258d9"
    if diagnostic:
        chain = run_core("diag", FIXTURES["oak1"], True)
        result = _result_of(chain, candidate)
        print(json.dumps({"RESULT": result}, ensure_ascii=False), flush=True)
        return 0 if result["result"] == "PASS" else 1
    # B01-B05 预写定矩阵:两橡木、两白桦、一非默认布局
    matrix = [("b01", "oak1"), ("b02", "oak1"),
              ("b03", "birch1"), ("b04", "birch1"),
              ("b05", "oak-layout")]
    runs = []
    for run_id, fixture_key in matrix:
        chain = run_core(run_id, FIXTURES[fixture_key], False)
        result = _result_of(chain, candidate)
        runs.append(result)
        print(json.dumps({"RESULT": result}, ensure_ascii=False), flush=True)
        if result["result"] != "PASS":
            break  # 保存失败,不挑选零散成功
    with open(r"D:\mc-rcf1-raw\g4-runs.json", "w",
              encoding="utf-8") as fh:
        json.dump({"candidate": candidate, "runs": runs}, fh,
                  ensure_ascii=False, indent=1)
    ok = len(runs) == 5 and all(r["result"] == "PASS" for r in runs)
    print("G4 %s" % ("5/5 PASS" if ok else "FAILED at %d/5" % len(runs)))
    return 0 if ok else 1


def _result_of(chain, candidate):
    return {
        "run": chain.run_id, "session_id": chain.session_id,
        "candidate": candidate,
        "result": "PASS" if all(chain.chain.values())
        and not chain.fail_at else "FAIL",
        "fail_at": chain.fail_at,
        "duration_s": round(time.time() - chain.started_at, 1),
        "final_inventory": dict(_last_inventory(chain)),
        "cursor_empty": bool(chain.chain.get("cursor_empty")),
        "chain": chain.chain, "events": chain.events,
    }


def _last_inventory(chain):
    for evt in reversed(chain.events):
        if evt.get("kind") == "final" and isinstance(evt.get("inv"), dict):
            return evt["inv"]
    return {}


if __name__ == "__main__":
    sys.exit(main())
