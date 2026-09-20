# -*- coding: utf-8 -*-
"""木器时代 v3:夜晚完全静默(不被打断),白天流水线采集+合成。
目标: 木镐+木斧+工作台 在手。"""
import json, sys, time

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play


def log(m):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), m), flush=True)


def scene(s):
    for _ in range(6):
        v = s.view()
        if v.get("ok"):
            return v["data"]["scene"]
        time.sleep(3)
        s.keepalive()
    raise RuntimeError("view failing")


def count(inv, *suf):
    return sum(n for k, n in inv.items() if k.endswith(suf))


def wait_daylight(s):
    """夜静默:只保活观察,不执行任何世界动作。"""
    while True:
        s.keepalive()
        try:
            v = s.view()["data"]["scene"]
            if v["environment"]["day_phase"] in ("DAY", "MORNING", "DAWN"):
                return v
        except Exception:
            pass
        time.sleep(25)


def nearest_log(s):
    ov = s.overview("_log")
    for ring, d, block, n, ref in ov["rings"]:
        ins = s.inspect(ref, "summary")
        ev = (ins.get("data") or {}).get("evidence") or {}
        if "x" in ev:
            return {"pos": {"x": ev["x"], "y": ev["y"], "z": ev["z"]},
                    "block": "minecraft:" + block, "dist": d, "n": n}
    return None


def mine_at(s, pos, block_name):
    # face 站位轮试:正面被地形遮挡时(crosshair 打到坡面远处),换相邻侧面
    for dx, dz in ((0, 0), (2, 0), (-2, 0), (0, 2), (0, -2)):
        stand = {"x": pos["x"] + dx, "y": pos["y"], "z": pos["z"] + dz}
        ex, err = s.do_async("goto", {"x": stand["x"], "y": stand["y"], "z": stand["z"],
                                      "allow_terrain_changes": True,
                                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]})
        if ex is None:
            continue
        res, _ = s.term(ex, timeout_s=100)
        if res.get("state") == "completed":
            break
        log("  face(%+d,%+d) %s | %s" % (dx, dz, res.get("state"),
                                         (res.get("reason") or "")[:52]))
    else:
        return False
    time.sleep(1.5)
    v = scene(s)
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    best = None
    for o in opps.get("items") or []:
        sm = o.get("summary") or {}
        if sm.get("block") == block_name:
            d = sm.get("distance_blocks", 99)
            if best is None or d < best[1]:
                best = (o.get("object_id"), d)
    if not best:
        return False
    ex2, _ = s.do_async("mine_opportunity", {"id": best[0]})
    if ex2 is None:
        return False
    res2, _ = s.term(ex2, timeout_s=100)
    log("  mine %s" % res2.get("state"))
    return res2.get("state") == "completed"


def craft(s, item, n):
    ex, err = s.do_async("craft", {"item": item, "count": n})
    if ex is None:
        return False
    res, _ = s.term(ex, timeout_s=60)
    ok = res.get("state") == "completed"
    log("craft %s x%d -> %s | %s" % (item, n, res.get("state"),
                                     (res.get("reason") or "")[:56]))
    return ok


def tools_done(inv):
    return (inv.get("minecraft:wooden_pickaxe", 0) >= 1
            and inv.get("minecraft:wooden_axe", 0) >= 1)


def main():
    s = play.Session()
    deadline = time.time() + 7200
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        inv = v["self"]["inventory"] or {}
        if tools_done(inv):
            break
        # 夜静默
        if v["environment"]["day_phase"] == "NIGHT":
            log("night: silent (inv=%s)" % json.dumps(inv))
            wait_daylight(s)
            continue
        planks = count(inv, "_planks")
        logs = count(inv, "_log")
        sticks = inv.get("minecraft:stick", 0)
        log("planks=%d logs=%d sticks=%d %s" % (planks, logs, sticks, json.dumps(inv)))
        # 材料换算:还需要多少 planks
        need = 12 - planks  # 表4+棍2(4原木)+镐3+斧3 = 12
        if need > 0 and logs > 0:
            # craft 是"最终配额"语义:请求当前+增量,否则已满足时不消耗材料
            craft(s, "minecraft:oak_planks", planks + 4)
            continue
        if planks >= 4 and sticks < 4 and planks >= 6:
            pass  # sticks 稍后
        if need > 0:
            target = nearest_log(s)
            if target:
                log("dig %s @ %s (d=%s)" % (target["block"], target["pos"], target["dist"]))
                mine_at(s, target["pos"], target["block"])
            else:
                p = v["self"]["block_position"]
                s.do("goto", {"x": p["x"] + 8, "y": p["y"], "z": p["z"] - 8,
                              "allow_terrain_changes": True}, timeout_s=80)
            continue
        # 材料齐:工具链
        if inv.get("minecraft:crafting_table", 0) < 1:
            craft(s, "minecraft:crafting_table", 1)
        if inv.get("minecraft:stick", 0) < 4:
            craft(s, "minecraft:stick", 4)
        craft(s, "minecraft:wooden_pickaxe", 1)
        craft(s, "minecraft:wooden_axe", 1)
    v = scene(s)
    log("FINAL inv=%s" % json.dumps(v["self"]["inventory"]))
    log("WOOD3-END")


if __name__ == "__main__":
    main()
