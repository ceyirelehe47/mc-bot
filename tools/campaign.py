# -*- coding: utf-8 -*-
"""进军树区 + 挖洞过夜 + 次日采集,多日循环直至 6 原木+合成链。"""
import json, sys, time

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play

TREE_ZONE = (132, 119, 89)


def log(m):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), m), flush=True)


def scene(s):
    return s.view()["data"]["scene"]


def scan_logs(v):
    sa = v["environment"]["spatial_awareness"]
    return [b for b in sa["visible_blocks"]["items"] if b["block"].endswith("_log")]


def inv_logs(v):
    return sum(n for k, n in (v["self"]["inventory"] or {}).items()
               if k.endswith("_log"))


def phase(v):
    return v["environment"]["day_phase"]


def mine_at(s, pos, tag="dig"):
    """face 目标生成机会并挖掉。"""
    r = s.do("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                      "allow_terrain_changes": True,
                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]},
             timeout_s=110)
    t = r.get("terminal") or {}
    if t.get("state") != "completed":
        log("  %s face-goto %s" % (tag, t.get("state")))
        return False
    time.sleep(1.5)
    v = scene(s)
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    for o in opps.get("items") or []:
        op = o.get("position") or {}
        if op.get("x") == pos["x"] and op.get("y") == pos["y"] and op.get("z") == pos["z"]:
            m = s.do("mine_opportunity", {"id": o.get("object_id")}, timeout_s=110)
            mt = m.get("terminal") or {}
            ok = mt.get("state") == "completed"
            log("  %s mine(%s) -> %s %s" % (tag, o.get("object_id")[-8:], mt.get("state"),
                                            (mt.get("reason") or "")[:50]))
            return ok
    log("  %s no-opportunity" % tag)
    return False


def dig_shelter(s, depth=3):
    """垂直挖脚下 3 格钻进去过夜。"""
    v = scene(s)
    p = v["self"]["block_position"]
    for i in range(depth):
        below = {"x": p["x"], "y": p["y"] - 1 - i, "z": p["z"]}
        if not mine_at(s, below, tag="shelter%d" % i):
            log("shelter dig %d failed" % i)
            return False
        time.sleep(1)
    log("shelter dug, falling in")
    return True


def march(s, deadline):
    """朝树区分段推进,沿途扫树;到达或发现树即停。"""
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        if phase(v) == "NIGHT":
            return "night"
        hits = scan_logs(v)
        if hits:
            return "trees"
        p = v["self"]["block_position"]
        dx = TREE_ZONE[0] - p["x"]
        dz = TREE_ZONE[2] - p["z"]
        dist = (dx * dx + dz * dz) ** 0.5
        if dist < 16:
            return "zone"
        step = min(24, dist)
        nx = round(p["x"] + dx / dist * step)
        nz = round(p["z"] + dz / dist * step)
        r = s.do("goto", {"x": nx, "y": p["y"], "z": nz,
                          "allow_terrain_changes": True}, timeout_s=90)
        t = r.get("terminal") or {}
        np_ = scene(s)["self"]["block_position"]
        log("march ->(%d,%d) %s now(%d,%d,%d)" % (
            nx, nz, t.get("state"), np_["x"], np_["y"], np_["z"]))
    return "deadline"


def harvest_trees(s, deadline):
    """采集直到 6 原木或时限。"""
    while time.time() < deadline:
        s.keepalive()
        v = scene(s)
        if inv_logs(v) >= 6:
            return True
        if phase(v) == "NIGHT":
            log("night during harvest, dig shelter")
            dig_shelter(s)
            time.sleep(240)  # 洞里等 4 分钟再查看
            continue
        hits = scan_logs(v)
        if not hits:
            p = v["self"]["block_position"]
            s.do("goto", {"x": p["x"] + 8, "y": p["y"], "z": p["z"],
                          "allow_terrain_changes": True}, timeout_s=80)
            continue
        t = sorted(hits, key=lambda b: b["relative"]["distance_blocks"])[0]
        log("mine %s @ %s" % (t["block"], t["position"]))
        mine_at(s, t["position"], tag="tree")
    return inv_logs(scene(s)) >= 6


def craft_chain(s):
    v = scene(s)
    log("inv: %s" % json.dumps(v["self"]["inventory"]))
    for item, count in [("minecraft:oak_planks", 8),
                        ("minecraft:crafting_table", 1),
                        ("minecraft:stick", 4),
                        ("minecraft:wooden_pickaxe", 1)]:
        r = s.do("craft", {"item": item, "count": count}, timeout_s=60)
        t = r.get("terminal") or {}
        log("craft %s x%d -> %s | %s" % (
            item, count, t.get("state"), (t.get("reason") or "")[:70]))


def main():
    s = play.Session()
    for day in range(4):
        # 等天亮
        t0 = time.time()
        while time.time() - t0 < 1300:
            s.keepalive()
            if phase(scene(s)) in ("DAY", "MORNING", "DAWN"):
                break
            time.sleep(20)
        log("day %d begins" % day)
        # 白天窗口:行军(最多 8 分钟)
        state = march(s, time.time() + 480)
        log("march -> %s" % state)
        if state == "night":
            dig_shelter(s)
            time.sleep(240)
            continue
        # 到达(树/区):采集(最多 10 分钟)
        ok = harvest_trees(s, time.time() + 600)
        log("harvest ok=%s" % ok)
        if ok:
            craft_chain(s)
            v = scene(s)
            log("FINAL inv=%s" % json.dumps(v["self"]["inventory"]))
            log("CAMPAIGN-END")
            return
    log("CAMPAIGN-EXHAUSTED")


if __name__ == "__main__":
    main()
