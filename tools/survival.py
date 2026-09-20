# -*- coding: utf-8 -*-
"""生存核心循环:白天石器时代采集,黄昏前水平挖洞封口过夜。
状态机: dig_stone -> craft_tools -> shelter -> dawn -> resume"""
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


def inv(v):
    return v["self"]["inventory"] or {}


def count(v, *names):
    i = inv(v)
    return sum(n for k, n in i.items() if k in names)


def phase_of(v):
    return v["environment"]["day_phase"]


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
    if res2.get("state") != "completed":
        log("  mine %s | %s" % (res2.get("state"), (res2.get("reason") or "")[:46]))
    return res2.get("state") == "completed"


def craft(s, item, n):
    ex, err = s.do_async("craft", {"item": item, "count": n})
    if ex is None:
        return False
    res, _ = s.term(ex, timeout_s=60)
    log("craft %s x%d -> %s | %s" % (item, n, res.get("state"),
                                     (res.get("reason") or "")[:56]))
    return res.get("state") == "completed"


def dig_shelter_and_seal(s, v):
    """水平挖 2 格进山体,进去后回头放置封口(2 格高)。"""
    p = v["self"]["block_position"]
    # 选一个朝向:挖前方 2 格(脚+头)
    for depth in (1, 2):
        for yy in (0, 1):
            t = {"x": p["x"] + 2, "y": p["y"] + yy, "z": p["z"]}
            mine_at(s, t, "minecraft:dirt")
    time.sleep(1)
    # 走进洞
    ex, _ = s.do_async("goto", {"x": p["x"] + 2, "y": p["y"], "z": p["z"],
                                "allow_terrain_changes": True})
    if ex:
        s.term(ex, timeout_s=40)
    # 回头封口:放置在洞口列(原位置+1 深度方向即口),头/脚两格
    for yy in (1, 0):
        ex2, _ = s.do_async("place", {"x": p["x"] + 1, "y": p["y"] + yy, "z": p["z"]})
        if ex2:
            res, _ = s.term(ex2, timeout_s=25)
            log("  seal y+%d -> %s" % (yy, res.get("state")))
    log("shelter sealed, sleeping")


def main():
    s = play.Session()
    deadline = time.time() + 14400
    sheltered = False
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        ph = phase_of(v)
        i = inv(v)
        # 完成判定
        if (i.get("minecraft:stone_pickaxe", 0) >= 1
                and i.get("minecraft:furnace", 0) >= 1):
            log("STONE-AGE-DONE inv=%s" % json.dumps(i))
            return
        # 黄昏预警: DUSK 即准备过夜
        if ph in ("NIGHT", "DUSK"):
            if not sheltered and count(v, "minecraft:dirt") >= 2:
                log("dusk: digging shelter")
                try:
                    dig_shelter_and_seal(s, v)
                    sheltered = True
                except Exception as e:
                    log("shelter err: %s" % str(e)[:60])
            time.sleep(30)
            continue
        sheltered = False
        # 白天:石器流水
        cobble = count(v, "minecraft:cobblestone", "minecraft:cobbled_deepslate")
        log("ph=%s cobble=%d inv=%s" % (ph, cobble, json.dumps(i)))
        if cobble >= 14:
            if i.get("minecraft:stone_pickaxe", 0) < 1 and i.get("minecraft:stick", 0) >= 2:
                craft(s, "minecraft:stone_pickaxe", 1)
            elif i.get("minecraft:furnace", 0) < 1:
                craft(s, "minecraft:furnace", 1)
            elif i.get("minecraft:stone_axe", 0) < 1 and i.get("minecraft:stick", 0) >= 2:
                craft(s, "minecraft:stone_axe", 1)
            else:
                craft(s, "minecraft:stone_pickaxe", 1)
            time.sleep(4)
            continue
        # 缺棍
        if (i.get("minecraft:stick", 0) < 2
                and count(v, "minecraft:oak_planks") >= 5):
            craft(s, "minecraft:stick", 4)
            time.sleep(4)
            continue
        # 缺镐(木镐也行)
        if (i.get("minecraft:wooden_pickaxe", 0) < 1
                and i.get("minecraft:stone_pickaxe", 0) < 1):
            if count(v, "minecraft:oak_planks") >= 3:
                craft(s, "minecraft:wooden_pickaxe", 1)
                time.sleep(4)
            else:
                # 无镐无板:先挖木头(与 woodage 同法)
                ov = s.overview("_log")
                target = None
                for ring, d, block, n, ref in ov["rings"]:
                    ins = s.inspect(ref, "summary")
                    ev = (ins.get("data") or {}).get("evidence") or {}
                    if "x" in ev:
                        target = {"x": ev["x"], "y": ev["y"], "z": ev["z"],
                                  "block": "minecraft:" + block}
                        break
                if target:
                    log("dig %s @ %s" % (target["block"], target))
                    if mine_at(s, target, target["block"]):
                        craft(s, "minecraft:oak_planks", 4)
                else:
                    p = v["self"]["block_position"]
                    s.do("goto", {"x": p["x"] + 6, "y": p["y"], "z": p["z"] - 6,
                                  "allow_terrain_changes": True}, timeout_s=70)
            continue
        # 采石:下挖
        p = v["self"]["block_position"]
        log("dig-down from %s" % p)
        for depth in range(5):
            below = {"x": p["x"], "y": p["y"] - 1 - depth, "z": p["z"]}
            if not mine_at(s, below, "minecraft:dirt"):
                if not mine_at(s, below, "minecraft:stone"):
                    break
            time.sleep(0.6)
            v2 = scene(s)
            if count(v2, "minecraft:cobblestone", "minecraft:cobbled_deepslate") > 0:
                break
    log("SURVIVAL-TIMEOUT")


if __name__ == "__main__":
    main()
