# -*- coding: utf-8 -*-
"""拆除村庄房屋获取木料:扫木板/原木/门 -> face+mine 循环 -> 合成链。
游戏内真实挖掘,不作弊。"""
import json, sys, time

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play


def log(m):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), m), flush=True)


def scene(s):
    for attempt in range(6):
        v = s.view()
        if v.get("ok"):
            return v["data"]["scene"]
        time.sleep(3)
        s.keepalive()
    raise RuntimeError("view persistently failing")


failed_targets = set()

WANTED = ("minecraft:oak_planks", "minecraft:oak_log",
          "minecraft:spruce_planks", "minecraft:spruce_log",
          "minecraft:birch_planks", "minecraft:birch_log")
DOORS = ("minecraft:oak_door", "minecraft:spruce_door")


def scan_targets(v):
    sa = v["environment"]["spatial_awareness"]
    out = []
    for b in sa["visible_blocks"]["items"]:
        if b["block"] in WANTED or b["block"] in DOORS:
            out.append(b)
    return out


def inv_count(v):
    inv = v["self"]["inventory"] or {}
    planks = sum(n for k, n in inv.items() if k.endswith("_planks"))
    logs = sum(n for k, n in inv.items() if k.endswith("_log"))
    return planks, logs


def mine_at(s, pos, block_name, tag):
    r = s.do("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                      "allow_terrain_changes": True,
                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]},
             timeout_s=110)
    t = r.get("terminal") or {}
    if t.get("state") != "completed":
        log("  %s face-goto %s %s" % (tag, t.get("state"), (t.get("reason") or "")[:40]))
        return False
    time.sleep(1.5)
    v = scene(s)
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    # face 后 crosshair 方块=目标:按 summary.block 匹配(坐标匹配不可靠,view 有过滤)
    best = None
    for o in opps.get("items") or []:
        sm = o.get("summary") or {}
        if sm.get("block") == block_name:
            d = sm.get("distance_blocks", 99)
            if best is None or d < best[1]:
                best = (o.get("object_id"), d)
    if best:
        m = s.do("mine_opportunity", {"id": best[0]}, timeout_s=110)
        mt = m.get("terminal") or {}
        ok = mt.get("state") == "completed"
        log("  %s mine -> %s %s" % (tag, mt.get("state"), (mt.get("reason") or "")[:50]))
        return ok
    log("  %s no-opportunity(%s)" % (tag, block_name))
    return False


def main():
    s = play.Session()
    deadline = time.time() + 2700
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        planks, logs = inv_count(v)
        log("have planks=%d logs=%d inv=%s" % (planks, logs,
            json.dumps(v["self"]["inventory"])))
        if planks >= 10:
            break
        # 机会列表里的原木(视野列不出普通墙方块):优先挖
        opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
        log_opp = None
        for o in opps.get("items") or []:
            sm = o.get("summary") or {}
            if sm.get("block", "").endswith("_log"):
                if log_opp is None or sm.get("distance_blocks", 99) < log_opp[1]:
                    log_opp = (o, sm.get("distance_blocks", 99))
        if log_opp:
            o = log_opp[0]
            ins = s.inspect(o.get("evidence_ref", ""), "summary")
            ev = (ins.get("data") or {}).get("evidence") or {}
            pos = None
            if isinstance(ev, dict) and "x" in ev and "y" in ev and "z" in ev:
                pos = {"x": ev["x"], "y": ev["y"], "z": ev["z"]}
            if pos:
                log("dig-log %s @ %s (d=%s)" % (o["summary"]["block"], pos, log_opp[1]))
                if mine_at(s, pos, o["summary"]["block"], tag="log"):
                    continue
            else:
                log("inspect no pos: %s" % json.dumps(ev)[:150])
        hits = [b for b in scan_targets(v)
                if (b["position"]["x"], b["position"]["y"], b["position"]["z"]) not in failed_targets]
        if not hits:
            p = v["self"]["block_position"]
            s.do("goto", {"x": p["x"] + 6, "y": p["y"], "z": p["z"] + 6,
                          "allow_terrain_changes": True}, timeout_s=70)
            continue
        t = sorted(hits, key=lambda b: b["relative"]["distance_blocks"])[0]
        log("dig %s @ %s" % (t["block"], t["position"]))
        ok = mine_at(s, t["position"], t["block"], tag="house")
        if not ok:
            failed_targets.add((t["position"]["x"], t["position"]["y"], t["position"]["z"]))
            # 跳过该目标,换个方块(避免同一堵墙死磕)
            p = scene(s)["self"]["block_position"]
            s.do("goto", {"x": p["x"] + 4, "y": p["y"], "z": p["z"],
                          "allow_terrain_changes": True}, timeout_s=60)
    # 合成链(木板在手)
    for item, count in [("minecraft:crafting_table", 1),
                        ("minecraft:stick", 4),
                        ("minecraft:wooden_pickaxe", 1),
                        ("minecraft:wooden_axe", 1)]:
        r = s.do("craft", {"item": item, "count": count}, timeout_s=60)
        t = r.get("terminal") or {}
        log("craft %s x%d -> %s | %s" % (
            item, count, t.get("state"), (t.get("reason") or "")[:70]))
    v = scene(s)
    log("FINAL inv=%s" % json.dumps(v["self"]["inventory"]))
    log("SALVAGE-END")


if __name__ == "__main__":
    main()
