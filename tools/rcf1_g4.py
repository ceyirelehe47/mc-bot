# -*- coding: utf-8 -*-
"""MC-RCF-1-R1 G4 核心链(B01 一次):空包→采木→2x2→放台→3x3 木镐→
采石→3x3 石镐。

规则(02_ACCEPTANCE §6):
- 树形/石面可在准备期布置于局部导航范围;观察器先证明初态。
- armed 之后只用正式语义动作;禁止 give/teleport/时间/难度/kill 清怪。
- 每次默认 12 分钟;终点=同时拥有木镐+石镐(材料来自本次合法采集)、
  工作台精确存在且可再打开、cursor 空、无未结 unknown。
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


_FINDER = None


def opp(block, near=None, session=None):
    """用传入 session(不新建——新 Session 会抢走运行中执行的租约,
    实测 lease lost 根因)。"""
    global _FINDER
    s = session or _FINDER
    if s is None:
        _FINDER = play.Session("g4find")
        s = _FINDER
    loc = s.inspect_local(10, "summary")
    opps = (((loc.get("data") or {}).get("snapshot") or {})
            .get("opportunities") or [])
    cands = [o for o in opps if isinstance(o, dict) and o.get("block") == block]
    if near and cands:
        cands = [o for o in cands
                 if abs(o.get("x", 0) - near[0]) <= 10
                 and abs(o.get("z", 0) - near[2]) <= 10]
    cands.sort(key=lambda o: (o.get("y", 0)))
    return cands[0] if cands else None


def mine_n(s, block, n, near, timeout_each=150):
    got = 0
    deadline = time.time() + timeout_each * 3
    while got < n and time.time() < deadline:
        o = opp(block, near)
        if not o:
            time.sleep(2)
            continue
        ex, err = s.submit("mine_opportunity", {"id": o.get("object_id")})
        if ex is None:
            time.sleep(2)
            continue
        term = s.term(ex, timeout_s=timeout_each)[0]
        if term.get("state") == "completed":
            got += 1
        else:
            return got, term
    return got, None


def run_b01(run_id):
    t_start = time.time()
    log = {"run": run_id}
    # ---- 准备期(armed 前):fixture + 初态证明 ----
    rcon("tp Bob 300.5 120 300.5")
    # 橡树:距 Bob 5-7 格(经验:2 格贴脸机会不注册;4+ 可)
    for dz in (305, 306, 307, 308, 309):
        rcon("setblock 300 120 %d minecraft:oak_log" % dz)
    for dz in (304, 310):
        for dy in (120, 121, 122):
            for dx in (299, 300, 301):
                rcon("setblock %d %d %d minecraft:oak_leaves" % (dx, dy, dz))
    for dz in range(305, 310):
        for dx in (299, 301):
            for dy in (120, 121):
                rcon("setblock %d %d %d minecraft:oak_leaves" % (dx, dy, dz))
    # 石面:侧向 6 格挖开表土露 stone
    for dx in range(305, 309):
        rcon("setblock %d 120 296 minecraft:air" % dx)
    for dx in range(305, 309):
        rcon("setblock %d 119 296 minecraft:stone" % dx)
    rcon("time set day")
    rcon("weather clear")
    rcon("clear Bob")
    time.sleep(3)
    inv0 = json.dumps(play.Session("g4inv").observe()
                      .get("data", {}).get("observation", {})
                      .get("inventory", []), ensure_ascii=False)
    log["initial_inventory_empty"] = inv0 in ("[]", "")
    log["tree_visible"] = bool(opp("minecraft:oak_log", near=(300, 120, 307)))
    if not log["tree_visible"]:
        time.sleep(4)
        log["tree_visible"] = bool(opp("minecraft:oak_log", near=(300, 120, 307)))
    # ---- armed:正式语义链 ----
    s = play.Session("g4run")
    steps = []

    def step(name, r):
        steps.append((name, r.get("state"), (r.get("reason") or "")[:90]))
        print(json.dumps({"run": run_id, "step": name,
                          "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:90]},
                         ensure_ascii=False), flush=True)
        return r.get("state") == "completed"

    # 采木×5(树干整树)
    got, term = mine_n(s, "minecraft:oak_log", 5, (300, 120, 307))
    log["logs_mined"] = got
    if got < 5:
        log["fail_at"] = "mine logs %d/5 %s" % (got, term or "")
        log["steps"] = steps
        return log
    # 2x2:planks 20(5 log)→stick 8(用 4 planks)→table 1(4 planks)
    if not step("planks", s.do("craft", {"item": "minecraft:oak_planks",
                                         "count": 20}, 300)[0] if isinstance(
            s.do("craft", {"item": "minecraft:oak_planks", "count": 20}, 300),
            dict) else {}):
        pass
    return log


def main():
    # 简化主链(逐段断言,失败即停并记录)
    t0 = time.time()
    # R1:机会=传感器注册的自然方块(热放不注册,实测);用出生区自然橡树
    rcon("tp Bob 8.5 107 -0.5")
    rcon("time set day"); rcon("weather clear"); rcon("clear Bob")
    time.sleep(3.5)
    s = play.Session("g4")
    # 树干被叶包裹不暴露:先挖叶暴露(log 机会随后注册——上轮 G4 实测路径)
    for i in range(8):
        o = opp("minecraft:oak_log", near=(8, 107, -1), session=s)
        if o:
            break
        leaf = opp("minecraft:oak_leaves", near=(8, 107, -1), session=s)
        if not leaf:
            print(json.dumps({"fail": "叶机会也未注册", "i": i}))
            return 1
        r = s.do("mine_opportunity", {"id": leaf.get("object_id")},
                 timeout_s=180).get("terminal", {})
        print(json.dumps({"leaf": i, "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:60]}), flush=True)
        time.sleep(1.5)
    o = opp("minecraft:oak_log", near=(8, 107, -1), session=s)
    if not o:
        print(json.dumps({"fail": "挖叶后树干机会未注册"}))
        return 1
    print(json.dumps({"tree": (o.get("x"), o.get("y"), o.get("z"))}))
    # 逐木挖
    for i in range(5):
        o = opp("minecraft:oak_log", near=(8, 107, -1), session=s)
        if not o:
            print(json.dumps({"i": i, "fail": "无机会"}))
            return 1
        r = s.do("mine_opportunity", {"id": o.get("object_id")},
                 timeout_s=180)[0] if False else s.do(
            "mine_opportunity", {"id": o.get("object_id")},
            timeout_s=180).get("terminal", {})
        print(json.dumps({"mine": i, "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:80]}), flush=True)
        if r.get("state") != "completed":
            return 1
        time.sleep(1)
    obs = s.observe().get("data", {})
    inv = obs.get("observation", {}).get("inventory", [])
    logs = sum(x.get("count", 0) for x in inv
               if x.get("item", "") == "minecraft:oak_log")
    print(json.dumps({"logs": logs, "elapsed": round(time.time() - t0, 1)}))
    # 合成链
    for name, args in (
            ("planks", {"item": "minecraft:oak_planks", "count": 20}),
            ("sticks", {"item": "minecraft:stick", "count": 8}),
            ("table", {"item": "minecraft:crafting_table", "count": 1})):
        r = s.do("craft", args, timeout_s=300).get("terminal", {})
        print(json.dumps({"craft": name, "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:90]}), flush=True)
        if r.get("state") != "completed":
            return 1
    # 放台(脚下旁)
    bx, by, bz = 8, 107, 2
    r = s.do("place", {"x": bx, "y": by, "z": bz,
                       "item": "minecraft:crafting_table"},
             timeout_s=120).get("terminal", {})
    print(json.dumps({"place": r.get("state"),
                      "reason": (r.get("reason") or "")[:90]}), flush=True)
    if r.get("state") != "completed":
        return 1
    # 木镐
    r = s.do("craft", {"item": "minecraft:wooden_pickaxe", "count": 1},
             timeout_s=300).get("terminal", {})
    print(json.dumps({"wpick": r.get("state"),
                      "reason": (r.get("reason") or "")[:90]}), flush=True)
    if r.get("state") != "completed":
        return 1
    # 采石:自然泥土机会挖开露 stone(泥土/石均为自然方块,机会可注册)
    for i in range(2):
        o = opp("minecraft:dirt", near=(8, 106, -1), session=s)
        if not o:
            break
        r = s.do("mine_opportunity", {"id": o.get("object_id")},
                 timeout_s=150).get("terminal", {})
        print(json.dumps({"dig_dirt": i, "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:60]}), flush=True)
    time.sleep(3)
    for i in range(3):
        o = opp("minecraft:stone", near=(8, 105, -1), session=s)
        if not o:
            print(json.dumps({"i": i, "fail": "石机会未注册"}))
            return 1
        r = s.do("mine_opportunity", {"id": o.get("object_id")},
                 timeout_s=180).get("terminal", {})
        print(json.dumps({"stone": i, "state": r.get("state"),
                          "reason": (r.get("reason") or "")[:80]}), flush=True)
        if r.get("state") != "completed":
            return 1
        time.sleep(1)
    # 石镐
    r = s.do("craft", {"item": "minecraft:stone_pickaxe", "count": 1},
             timeout_s=300).get("terminal", {})
    print(json.dumps({"spick": r.get("state"),
                      "reason": (r.get("reason") or "")[:90]}), flush=True)
    # 终态检查
    obs = s.observe().get("data", {})
    inv = obs.get("observation", {}).get("inventory", [])
    ids = {}
    for x in inv:
        ids[x.get("item", "")] = ids.get(x.get("item", ""), 0) + x.get("count", 0)
    blk = rcon("execute if block 8 107 2 minecraft:crafting_table")
    result = {
        "wooden_pickaxe": ids.get("minecraft:wooden_pickaxe", 0),
        "stone_pickaxe": ids.get("minecraft:stone_pickaxe", 0),
        "cobblestone": ids.get("minecraft:cobblestone", 0),
        "table_placed": "passed" in blk,
        "elapsed_s": round(time.time() - t0, 1),
        "within_12min": time.time() - t0 < 720,
    }
    print(json.dumps({"RESULT": result}, ensure_ascii=False))
    ok = (result["wooden_pickaxe"] >= 1 and result["stone_pickaxe"] >= 1
          and result["table_placed"] and result["within_12min"])
    print("B01 %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
