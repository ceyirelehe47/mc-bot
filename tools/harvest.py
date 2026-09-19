# -*- coding: utf-8 -*-
"""定向采集:回树区(125,114,91)挖满 6 原木+合成链。"""
import sys, time, json

sys.path.insert(0, r"D:\code\mc-bot\tools")
import play

TREE_ZONE = (125, 114, 91)


def log(m):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), m), flush=True)


def scan(v):
    sa = v["environment"]["spatial_awareness"]
    return [b for b in sa["visible_blocks"]["items"]
            if b["block"].endswith("_log")]


def inv_logs(v):
    return sum(n for k, n in (v["self"]["inventory"] or {}).items()
               if k.endswith("_log"))


def mine_one(s, pos):
    r = s.do("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                      "allow_terrain_changes": True,
                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]},
             timeout_s=110)
    t = r.get("terminal") or {}
    log("  face-goto %s %s" % (t.get("state"), (t.get("reason") or "")[:50]))
    time.sleep(2)
    v = s.view()["data"]["scene"]
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    for o in opps.get("items") or []:
        op = o.get("position") or {}
        if op.get("x") == pos["x"] and op.get("y") == pos["y"] and op.get("z") == pos["z"]:
            m = s.do("mine_opportunity", {"id": o.get("id")}, timeout_s=110)
            mt = m.get("terminal") or {}
            log("  mine -> %s %s" % (mt.get("state"), (mt.get("reason") or "")[:60]))
            return mt.get("state") == "completed"
    log("  no opportunity")
    return False


def main():
    s = play.Session()
    # 等白天
    t0 = time.time()
    while time.time() - t0 < 1300:
        s.keepalive()
        if s.day_phase() in ("DAY", "MORNING", "DAWN"):
            break
        time.sleep(20)
    log("day=%s" % s.day_phase())
    # 回树区:分段推进(goto 单跳上限 32 格)
    for leg in range(6):
        s.keepalive()
        v = s.view()["data"]["scene"]
        p = v["self"]["block_position"]
        dx = TREE_ZONE[0] - p["x"]; dz = TREE_ZONE[2] - p["z"]
        dist = (dx * dx + dz * dz) ** 0.5
        if dist < 30 or scan(v):
            break
        step = min(24, dist)
        nx = round(p["x"] + dx / dist * step)
        nz = round(p["z"] + dz / dist * step)
        r = s.do("goto", {"x": nx, "y": p["y"], "z": nz,
                          "allow_terrain_changes": True}, timeout_s=110)
        log("leg %d -> (%d,%d) %s" % (leg, nx, nz,
                                      (r.get("terminal") or {}).get("state")))
    # 采集循环:夜等待不占预算,总时长 25 分钟
    deadline = time.time() + 3300
    attempt = 0
    while time.time() < deadline:
        s.keepalive()
        v = s.view()["data"]["scene"]
        if v["environment"]["day_phase"] == "NIGHT":
            log("nightfall, have=%d" % inv_logs(v))
            time.sleep(45)
            continue
        have = inv_logs(v)
        if have >= 6:
            break
        hits = scan(v)
        if not hits:
            # 未到树区:继续分段靠近
            p = v["self"]["block_position"]
            dx = TREE_ZONE[0] - p["x"]; dz = TREE_ZONE[2] - p["z"]
            dist = (dx * dx + dz * dz) ** 0.5
            if dist > 24:
                step = min(24, dist)
                nx = round(p["x"] + dx / dist * step)
                nz = round(p["z"] + dz / dist * step)
            else:
                nx, nz = p["x"] + 8, p["z"]
            s.do("goto", {"x": nx, "y": p["y"], "z": nz,
                          "allow_terrain_changes": True}, timeout_s=90)
            continue
        t = sorted(hits, key=lambda b: b["relative"]["distance_blocks"])[0]
        attempt += 1
        log("attempt %d mine %s @ %s (have %d)" % (
            attempt, t["block"], t["position"], have))
        mine_one(s, t["position"])
    v = s.view()["data"]["scene"]
    log("logs=%d inv=%s" % (inv_logs(v), json.dumps(v["self"]["inventory"])))
    # 合成链
    if inv_logs(v) >= 1:
        for item, count in [("minecraft:oak_planks", 8),
                            ("minecraft:crafting_table", 1),
                            ("minecraft:stick", 4),
                            ("minecraft:wooden_pickaxe", 1)]:
            r = s.do("craft", {"item": item, "count": count}, timeout_s=60)
            t = r.get("terminal") or {}
            log("craft %s x%d -> %s | %s" % (
                item, count, t.get("state"), (t.get("reason") or "")[:70]))
    v = s.view()["data"]["scene"]
    log("FINAL inv=%s" % json.dumps(v["self"]["inventory"]))
    log("HARVEST-END")


if __name__ == "__main__":
    main()
