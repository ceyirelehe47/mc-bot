# -*- coding: utf-8 -*-
"""铁器时代编排:石镐下矿 → 铁矿机会挖掘 → 熔炉合成/放置 → smelt 冶炼 → 铁器。
前置: survival.py 完成(石镐+熔炉材料)。夜静默+白天流水。"""
import json, sys, time

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play

IRON_ORES = ("minecraft:iron_ore", "minecraft:deepslate_iron_ore")
FUELS = ("minecraft:coal", "minecraft:charcoal",
         "minecraft:oak_planks", "minecraft:oak_log")


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


def inv(v):
    return v["self"]["inventory"] or {}


def has(v, name):
    return inv(v).get(name, 0)


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


def mine_at(s, pos, block_name):
    for dx, dz in ((0, 0), (2, 0), (-2, 0), (0, 2), (0, -2)):
        stand = {"x": pos["x"] + dx, "y": pos["y"], "z": pos["z"] + dz}
        ex, _ = s.do_async("goto", {"x": stand["x"], "y": stand["y"], "z": stand["z"],
                                    "allow_terrain_changes": True,
                                    "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]})
        if ex is None:
            continue
        res, _ = s.term(ex, timeout_s=100)
        if res.get("state") == "completed":
            break
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
    res2, _ = s.term(ex2, timeout_s=120)
    log("  mine %s | %s" % (res2.get("state"), (res2.get("reason") or "")[:50]))
    return res2.get("state") == "completed"


def craft(s, item, n):
    ex, _ = s.do_async("craft", {"item": item, "count": n})
    if ex is None:
        return False
    res, _ = s.term(ex, timeout_s=60)
    log("craft %s x%d -> %s | %s" % (item, n, res.get("state"),
                                     (res.get("reason") or "")[:56]))
    return res.get("state") == "completed"


def find_opp(s, blocks, max_d=None):
    ov = s.overview()
    for ring, d, block, n, ref in ov["rings"]:
        if ("minecraft:" + block) in blocks:
            if max_d and d > max_d:
                continue
            ins = s.inspect(ref, "summary")
            ev = (ins.get("data") or {}).get("evidence") or {}
            if "x" in ev:
                return {"pos": {"x": ev["x"], "y": ev["y"], "z": ev["z"]},
                        "block": "minecraft:" + block, "dist": d}
    return None


def find_fuel(v):
    for f in FUELS:
        if has(v, f) >= 1:
            return f
    return None


def main():
    s = play.Session()
    deadline = time.time() + 14400
    placed_furnace = None
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        i = inv(v)
        if (i.get("minecraft:iron_ingot", 0) >= 3
                and i.get("minecraft:iron_pickaxe", 0) >= 1):
            log("IRON-AGE-DONE inv=%s" % json.dumps(i))
            return
        if v["environment"]["day_phase"] == "NIGHT":
            log("night: silent (iron=%d raw=%d)" % (
                i.get("minecraft:iron_ingot", 0),
                i.get("minecraft:raw_iron", 0)))
            time.sleep(45)
            continue
        raw = i.get("minecraft:raw_iron", 0)
        ingot = i.get("minecraft:iron_ingot", 0)
        cobble = (i.get("minecraft:cobblestone", 0)
                  + i.get("minecraft:cobbled_deepslate", 0))
        log("raw=%d ingot=%d cobble=%d fuel=%s %s" % (
            raw, ingot, cobble, find_fuel(v), json.dumps(i)))
        # 1) 冶炼优先(有矿有燃料有熔炉)
        if raw >= 1 and ingot < 3:
            fuel = find_fuel(v)
            if fuel and has(v, "minecraft:furnace") >= 1 and not placed_furnace:
                # 放置熔炉在脚下旁边
                p = v["self"]["block_position"]
                ex, _ = s.do_async("place", {"x": p["x"] + 1, "y": p["y"], "z": p["z"]})
                if ex:
                    res, _ = s.term(ex, timeout_s=25)
                    if res.get("state") == "completed":
                        placed_furnace = (p["x"] + 1, p["y"], p["z"])
                        log("furnace placed @ %s" % (placed_furnace,))
            elif fuel and placed_furnace:
                ex, _ = s.do_async("smelt", {
                    "input_item": "minecraft:raw_iron",
                    "fuel_item": fuel,
                    "count": min(raw, 12)})
                if ex:
                    res, _ = s.term(ex, timeout_s=250)
                    log("smelt -> %s | %s" % (res.get("state"),
                                              (res.get("reason") or "")[:60]))
                    placed_furnace = None  # 产物已收,熔炉复用需重贴近
            else:
                log("need fuel=%s furnace=%s placed=%s" % (
                    bool(fuel), has(v, "minecraft:furnace"), bool(placed_furnace)))
                time.sleep(5)
            time.sleep(3)
            continue
        # 2) 铁器合成
        if ingot >= 3:
            if i.get("minecraft:iron_pickaxe", 0) < 1 and i.get("minecraft:stick", 0) >= 2:
                craft(s, "minecraft:iron_pickaxe", 1)
            elif i.get("minecraft:iron_sword", 0) < 1 and i.get("minecraft:stick", 0) >= 1:
                craft(s, "minecraft:iron_sword", 1)
            elif i.get("minecraft:iron_pickaxe", 0) < 1:
                craft(s, "minecraft:stick", 4)
            time.sleep(3)
            continue
        # 3) 缺熔炉材料:挖石
        if has(v, "minecraft:furnace") < 1 and cobble < 8:
            p = v["self"]["block_position"]
            t = find_opp(s, ("stone", "cobblestone", "cobbled_deepslate"), max_d=10)
            if t:
                mine_at(s, t["pos"], t["block"])
            else:
                below = {"x": p["x"], "y": p["y"] - 2, "z": p["z"]}
                mine_at(s, below, "minecraft:dirt") or mine_at(
                    s, below, "minecraft:stone")
            time.sleep(2)
            continue
        if cobble >= 8 and has(v, "minecraft:furnace") < 1:
            craft(s, "minecraft:furnace", 1)
            time.sleep(3)
            continue
        # 4) 找铁矿
        t = find_opp(s, IRON_ORES)
        if t:
            log("dig iron %s @ %s (d=%s)" % (t["block"], t["pos"], t["dist"]))
            mine_at(s, t["pos"], t["block"])
        else:
            # 无铁矿可见:向下/横向探索
            p = v["self"]["block_position"]
            if p["y"] > 40:
                below = {"x": p["x"], "y": p["y"] - 2, "z": p["z"]}
                if not (mine_at(s, below, "minecraft:dirt")
                        or mine_at(s, below, "minecraft:stone")):
                    s.do("goto", {"x": p["x"] + 6, "y": p["y"] - 4, "z": p["z"],
                                  "allow_terrain_changes": True}, timeout_s=90)
            else:
                s.do("goto", {"x": p["x"] + 8, "y": p["y"], "z": p["z"] - 8,
                              "allow_terrain_changes": True}, timeout_s=90)
        time.sleep(2)
    log("IRON-TIMEOUT")


if __name__ == "__main__":
    main()
