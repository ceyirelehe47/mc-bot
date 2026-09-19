# -*- coding: utf-8 -*-
"""长距直线探索:连续同方向 goto,每跳扫描原木/树叶;命中即挖。"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play  # noqa: E402


def log(m):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), m), flush=True)


def scan(v):
    sa = v["environment"]["spatial_awareness"]
    hits = []
    for b in sa["visible_blocks"]["items"]:
        if b["block"].endswith("_log") or b["block"].endswith("_leaves"):
            hits.append(b)
    return hits


def inv_logs(v):
    return {k: n for k, n in (v["self"]["inventory"] or {}).items()
            if k.endswith("_log")}


def mine_one(s, block, pos):
    """goto(face=目标) 生成机会 -> mine_opportunity。"""
    r = s.do("goto", {"x": pos["x"], "y": pos["y"], "z": pos["z"],
                      "allow_terrain_changes": True,
                      "face_x": pos["x"], "face_y": pos["y"], "face_z": pos["z"]},
             timeout_s=110)
    t = r.get("terminal") or {}
    log("  face-goto %s %s" % (t.get("state"), (t.get("reason") or "")[:60]))
    time.sleep(2)
    v = s.view(3)["data"]["scene"] if False else s.view()["data"]["scene"]
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    for o in opps.get("items") or []:
        op = o.get("position") or {}
        if op.get("x") == pos["x"] and op.get("y") == pos["y"] and op.get("z") == pos["z"]:
            m = s.do("mine_opportunity", {"id": o.get("id")}, timeout_s=110)
            mt = m.get("terminal") or {}
            log("  mine %s -> %s %s" % (o.get("id"), mt.get("state"),
                                        (mt.get("reason") or "")[:60]))
            return mt.get("state") == "completed"
    log("  no opportunity for %s" % pos)
    return False


def main():
    s = play.Session()
    directions = [(24, 0), (0, 24), (-24, 0), (0, -24)]  # 优先 +X
    total_logs = 0
    for hop_round in range(40):
        s.keepalive()
        v = s.view()["data"]["scene"]
        phase = v["environment"]["day_phase"]
        inv = inv_logs(v)
        total_logs = sum(inv.values())
        p = v["self"]["block_position"]
        if phase == "NIGHT":
            log("night: idle wait (survive) logs=%d" % total_logs)
            time.sleep(45)
            continue
        if total_logs >= 6:
            log("quota met: %s" % inv)
            break
        hits = scan(v)
        if hits:
            log("HITS: %s" % [(h["block"], h["position"]) for h in hits])
            logs_only = [h for h in hits if h["block"].endswith("_log")]
            if logs_only:
                t = sorted(logs_only, key=lambda b: b["relative"]["distance_blocks"])[0]
                mine_one(s, t["block"], t["position"])
                continue
            leaf = sorted(hits, key=lambda b: b["relative"]["distance_blocks"])[0]
            lp = leaf["position"]
            s.do("goto", {"x": lp["x"], "y": lp["y"], "z": lp["z"],
                          "allow_terrain_changes": True}, timeout_s=110)
            continue
        # 无命中:沿方向链直线推进
        dx, dz = directions[hop_round % len(directions)]
        tgt = (p["x"] + dx, p["y"], p["z"] + dz)
        r = s.do("goto", {"x": tgt[0], "y": tgt[1], "z": tgt[2],
                          "allow_terrain_changes": True}, timeout_s=100)
        t = r.get("terminal") or {}
        np_ = s.view()["data"]["scene"]["self"]["block_position"]
        log("hop r=%d %s->%s %s now=%s" % (hop_round, (p["x"], p["z"]), tgt,
                                           t.get("state"), np_))
        time.sleep(2)
    log("EXPLORE-END logs=%d" % total_logs)


if __name__ == "__main__":
    main()
