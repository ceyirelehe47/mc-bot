# -*- coding: utf-8 -*-
"""石器时代:挖石 -> 石镐/石斧/熔炉。夜静默,白天流水。"""
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


def wait_daylight(s):
    while True:
        s.keepalive()
        try:
            v = s.view()["data"]["scene"]
            if v["environment"]["day_phase"] in ("DAY", "MORNING", "DAWN"):
                return v
        except Exception:
            pass
        time.sleep(25)


def nearest_stone(s):
    ov = s.overview()
    for ring, d, block, n, ref in ov["rings"]:
        base = block.replace("minecraft:", "")
        if base in ("stone", "cobblestone", "andesite", "diorite", "granite",
                    "cobbled_deepslate", "tuff", "deepslate"):
            ins = s.inspect(ref, "summary")
            ev = (ins.get("data") or {}).get("evidence") or {}
            if "x" in ev:
                return {"pos": {"x": ev["x"], "y": ev["y"], "z": ev["z"]},
                        "block": "minecraft:" + base,
                        "dist": d, "n": n}
    return None


def mine_at(s, pos, block_name):
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
        log("  face(%+d,%+d) %s" % (dx, dz, res.get("state")))
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
    log("  mine %s | %s" % (res2.get("state"), (res2.get("reason") or "")[:50]))
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


def inv(v):
    return v["self"]["inventory"] or {}


def main():
    s = play.Session()
    deadline = time.time() + 10800
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        i = inv(v)
        # 目标:石镐+石斧+熔炉(需圆石 3+3+8=14)
        if (i.get("minecraft:stone_pickaxe", 0) >= 1
                and i.get("minecraft:stone_axe", 0) >= 1
                and i.get("minecraft:furnace", 0) >= 1):
            break
        if v["environment"]["day_phase"] == "NIGHT":
            log("night: silent")
            wait_daylight(s)
            continue
        cobble = (i.get("minecraft:cobblestone", 0)
                  + i.get("minecraft:cobbled_deepslate", 0)
                  + i.get("minecraft:blackstone", 0))
        sticks = i.get("minecraft:stick", 0)
        log("cobble=%d sticks=%d %s" % (cobble, sticks, json.dumps(i)))
        # 优先补工具
        if cobble >= 3 and i.get("minecraft:stone_pickaxe", 0) < 1 and sticks >= 2:
            craft(s, "minecraft:stone_pickaxe", 1)
            continue
        if cobble >= 3 and i.get("minecraft:stone_axe", 0) < 1 and sticks >= 2:
            craft(s, "minecraft:stone_axe", 1)
            continue
        if cobble >= 8 and i.get("minecraft:furnace", 0) < 1:
            craft(s, "minecraft:furnace", 1)
            continue
        if sticks < 2 and sum(n for k, n in i.items() if k.endswith("_planks")) >= 2:
            craft(s, "minecraft:stick", 4)
            continue
        # 采石
        if cobble < 14:
            t = nearest_stone(s)
            if t:
                log("dig %s @ %s (d=%s)" % (t["block"], t["pos"], t["dist"]))
                mine_at(s, t["pos"], t["block"])
            else:
                # 地表无裸岩:垂直下挖(泥土层下即石头),边挖边检
                p = v["self"]["block_position"]
                log("dig-down from %s" % p)
                for depth in range(4):
                    below = {"x": p["x"], "y": p["y"] - 1 - depth, "z": p["z"]}
                    if not mine_at(s, below, "minecraft:dirt"):
                        # dirt 不行试 stone(可能已到石层)
                        mine_at(s, below, "minecraft:stone")
                    time.sleep(1)
                    v2 = scene(s)
                    i2 = inv(v2)
                    c2 = (i2.get("minecraft:cobblestone", 0)
                          + i2.get("minecraft:cobbled_deepslate", 0))
                    if c2 > cobble:
                        log("reached stone layer at depth %d" % depth)
                        break
            continue
        # 材料够但顺序没触发(如缺棍缺镐):打日志排查
        log("materials odd state, wait")
        time.sleep(10)
    v = scene(s)
    log("FINAL inv=%s" % json.dumps(inv(v)))
    log("STONE-AGE-END")


if __name__ == "__main__":
    main()
