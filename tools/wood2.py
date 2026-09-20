# -*- coding: utf-8 -*-
"""木器时代 v2:异步执行+分层视野。补齐 planks -> 木镐 -> 木斧。"""
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
    ex, err = s.do_async("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                                  "allow_terrain_changes": True,
                                  "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]})
    if ex is None:
        log("  goto submit fail: %s" % json.dumps(err)[:80])
        return False
    res, _ = s.term(ex, timeout_s=110)
    if res.get("state") != "completed":
        log("  face %s" % res.get("state"))
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
        log("  no-opp after face")
        return False
    ex2, err2 = s.do_async("mine_opportunity", {"id": best[0]})
    if ex2 is None:
        return False
    res2, _ = s.term(ex2, timeout_s=110)
    log("  mine %s" % res2.get("state"))
    return res2.get("state") == "completed"


def craft(s, item, n):
    ex, err = s.do_async("craft", {"item": item, "count": n})
    if ex is None:
        log("craft %s submit fail: %s" % (item, json.dumps(err)[:60]))
        return False
    res, _ = s.term(ex, timeout_s=60)
    log("craft %s x%d -> %s | %s" % (item, n, res.get("state"),
                                     (res.get("reason") or "")[:60]))
    return res.get("state") == "completed"


def main():
    s = play.Session()
    deadline = time.time() + 4500
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        i = v["self"]["inventory"] or {}
        planks = count(i, "_planks")
        logs = count(i, "_log")
        log("planks=%d logs=%d inv=%s" % (planks, logs, json.dumps(i)))
        # 目标:planks>=12(镐3+斧3+棍2+表4)
        if planks >= 12 or (planks >= 4 and logs >= 1 and planks + logs * 4 >= 12):
            craft(s, "minecraft:oak_planks", 4)
            v = scene(s)
            i = v["self"]["inventory"] or {}
            if count(i, "_planks") >= 11:
                break
        if planks >= 12:
            break
        target = nearest_log(s)
        if not target:
            p = v["self"]["block_position"]
            s.do("goto", {"x": p["x"] + 8, "y": p["y"], "z": p["z"] - 8,
                          "allow_terrain_changes": True}, timeout_s=80)
            continue
        log("dig %s @ %s (d=%s n=%s)" % (target["block"], target["pos"],
                                         target["dist"], target["n"]))
        mine_at(s, target["pos"], target["block"])
        craft(s, "minecraft:oak_planks", 4)
    # 工具
    craft(s, "minecraft:crafting_table", 1)
    craft(s, "minecraft:stick", 4)
    craft(s, "minecraft:wooden_pickaxe", 1)
    craft(s, "minecraft:wooden_axe", 1)
    v = scene(s)
    log("FINAL inv=%s" % json.dumps(v["self"]["inventory"]))
    log("WOOD2-END")


if __name__ == "__main__":
    main()
