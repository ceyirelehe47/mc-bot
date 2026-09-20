# -*- coding: utf-8 -*-
"""凑齐木器时代:挖原木(机会优先) -> planks -> sticks -> 木镐+木斧 -> 挖石。
从当前背包续跑(已有 table/torch 等)。"""
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


def mine_at(s, pos, block_name, tag):
    r = s.do("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                      "allow_terrain_changes": True,
                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]},
             timeout_s=110)
    t = r.get("terminal") or {}
    if t.get("state") != "completed":
        log("  %s face %s" % (tag, t.get("state")))
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
        log("  %s no-opp" % tag)
        return False
    m = s.do("mine_opportunity", {"id": best[0]}, timeout_s=110)
    mt = m.get("terminal") or {}
    ok = mt.get("state") == "completed"
    log("  %s mine %s" % (tag, mt.get("state")))
    return ok


def find_log_opp(s, v):
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    best = None
    for o in opps.get("items") or []:
        sm = o.get("summary") or {}
        bl = sm.get("block", "")
        if bl.endswith("_log"):
            d = sm.get("distance_blocks", 99)
            if best is None or d < best[1]:
                best = (o, d)
    if not best:
        return None
    o = best[0]
    ins = s.inspect(o.get("evidence_ref", ""), "summary")
    ev = (ins.get("data") or {}).get("evidence") or {}
    if "x" not in ev:
        return None
    return {"pos": {"x": ev["x"], "y": ev["y"], "z": ev["z"]},
            "block": o["summary"]["block"]}


def inv(v):
    return v["self"]["inventory"] or {}


def count(inv, *suffixes):
    return sum(n for k, n in inv.items() if k.endswith(suffixes))


def craft(s, item, n):
    r = s.do("craft", {"item": item, "count": n}, timeout_s=60)
    t = r.get("terminal") or {}
    log("craft %s x%d -> %s | %s" % (item, n, t.get("state"),
                                     (t.get("reason") or "")[:70]))
    return t.get("state") == "completed"


def main():
    s = play.Session()
    deadline = time.time() + 5400
    phase = "gather"  # gather -> tools
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        i = inv(v)
        logs = count(i, "_log")
        if phase == "gather":
            planks = count(i, "_planks")
            log("inv logs=%d planks=%d %s" % (logs, planks, json.dumps(i)))
            if planks >= 12 or (logs >= 1 and planks < 12):
                if not craft(s, "minecraft:oak_planks", 4):
                    # 原木不足时有多少合多少
                    craft(s, "minecraft:oak_planks", 4)
            v = scene(s)
            i = inv(v)
            planks = count(i, "_planks")
            if planks >= 12:
                phase = "tools"
                continue
            if v["environment"]["day_phase"] == "NIGHT":
                time.sleep(40)
                continue
            hit = find_log_opp(s, v)
            if hit:
                log("dig %s @ %s" % (hit["block"], hit["pos"]))
                mine_at(s, hit["pos"], hit["block"], tag="log")
            else:
                p = v["self"]["block_position"]
                s.do("goto", {"x": p["x"] + 6, "y": p["y"], "z": p["z"] - 6,
                              "allow_terrain_changes": True}, timeout_s=70)
        elif phase == "tools":
            log("tools phase inv=%s" % json.dumps(i))
            craft(s, "minecraft:crafting_table", 1)
            craft(s, "minecraft:stick", 4)
            craft(s, "minecraft:wooden_pickaxe", 1)
            craft(s, "minecraft:wooden_axe", 1)
            v = scene(s)
            log("FINAL inv=%s" % json.dumps(inv(v)))
            log("WOOD-AGE-END")
            return
    log("TIMEOUT-EXHAUSTED")


if __name__ == "__main__":
    main()
