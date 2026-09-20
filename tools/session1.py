# -*- coding: utf-8 -*-
"""生存会话 1:采集原木 -> 合成链(木板/工作台/木棍/木镐)。

用法: python session1.py [log_quota]
依赖: 环境已起(up.py run), play.py 同目录。
"""
import json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play  # noqa: E402

LOG_SUFFIX = "_log"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


class NightFall(Exception):
    pass


def view_scene(s, allow_night=False):
    v = s.view()["data"]["scene"]
    if not allow_night and v["environment"]["day_phase"] == "NIGHT":
        raise NightFall()
    return v


def scan_logs(scene):
    sa = scene["environment"]["spatial_awareness"]
    return [b for b in sa["visible_blocks"]["items"] if b["block"].endswith(LOG_SUFFIX)]


def inv_count(scene, item):
    return int((scene["self"].get("inventory") or {}).get(item, 0))


def wait_day(s, max_wait_s=720):
    t0 = time.time()
    while time.time() - t0 < max_wait_s:
        s.keepalive()
        phase = s.day_phase()
        if phase in ("DAY", "MORNING", "DAWN"):
            return True
        log("waiting for day (%s)..." % phase)
        time.sleep(20)
    return False


def goto_rel(s, dx, dz, timeout_s=75):
    s.keepalive()
    p = view_scene(s, allow_night=True)["self"]["block_position"]
    tgt = (p["x"] + dx, p["y"], p["z"] + dz)
    r = s.do("goto", {"x": tgt[0], "y": tgt[1], "z": tgt[2], "allow_terrain_changes": False},
             timeout_s=timeout_s)
    return tgt, (r.get("terminal") or {}).get("state")


def explore_find_logs(s, rounds=3):
    """螺旋步进找树(原木或树叶线索);返回可见原木列表。"""
    dirs = [(16, 0), (-16, 0), (0, 16), (0, -16), (16, 16), (-16, -16), (16, -16), (-16, 16),
            (24, 0), (0, 24), (-24, 0), (0, -24)]
    for rnd in range(rounds):
        for (dx, dz) in dirs:
            tgt, state = goto_rel(s, dx, dz, timeout_s=110)
            time.sleep(2)
            scene = view_scene(s, allow_night=True)
            hits = scan_logs(scene)
            p = scene["self"]["block_position"]
            if hits:
                log("probe tgt=%s pos=%s hits=%s" % (
                    tgt, p, [(h["block"], h["position"]) for h in hits]))
                logs_only = [h for h in hits if h['block'].endswith(LOG_SUFFIX)]
                if logs_only:
                    return logs_only
                # 只有树叶:向树叶走,拉近视野
                leaf = sorted(hits, key=lambda b: b['relative']['distance_blocks'])[0]
                lp = leaf['position']
                s.do('goto', {'x': lp['x'], 'y': lp['y'], 'z': lp['z'],
                              'allow_terrain_changes': False}, timeout_s=110)
                s.keepalive()
                scene = view_scene(s)
                logs_only = scan_logs(scene)
                if logs_only:
                    return logs_only
            else:
                log("probe tgt=%s pos=%s no-hits" % (tgt, p))
    return []


def mine_one(s, block_item, block_pos, timeout_s=110):
    """goto(face=目标) 生成 opportunity,再 mine_opportunity。"""
    face_args = {"x": block_pos["x"], "y": block_pos["y"], "z": block_pos["z"],
                 "allow_terrain_changes": False,
                 "face_x": block_pos["x"], "face_y": block_pos["y"], "face_z": block_pos["z"]}
    r = s.do("goto", face_args, timeout_s=timeout_s)
    t = r.get("terminal") or {}
    log("  goto+face -> %s %s" % (t.get("state"), (t.get("reason") or "")[:70]))
    v = s.view()["data"]["scene"]
    opps = (v.get("semantic_objects") or {}).get("resource_opportunities") or {}
    items = opps.get("items") or []
    for o in items:
        oid = o.get("object_id")
        op = o.get("position") or {}
        if op.get("x") == block_pos["x"] and op.get("y") == block_pos["y"] and op.get("z") == block_pos["z"]:
            m = s.do("mine_opportunity", {"id": oid}, timeout_s=timeout_s)
            mt = (m.get("terminal") or {})
            log("  mine %s -> %s %s" % (oid, mt.get("state"), (mt.get("reason") or "")[:70]))
            return mt.get("state") == "completed"
    # 未生成机会:尝试直接 face 一次再查
    log("  no opportunity yet for %s" % (block_pos,))
    return False


def harvest_logs(s, quota=6):
    got = 0
    attempts = 0
    try:
        while got < quota and attempts < quota * 3:
            attempts += 1
            scene = view_scene(s)
            have = inv_count(scene, "minecraft:oak_log") \
                + inv_count(scene, "minecraft:birch_log") \
                + inv_count(scene, "minecraft:spruce_log")
            got = have
            if got >= quota:
                break
            hits = scan_logs(scene)
            if not hits:
                hits = explore_find_logs(s, rounds=1)
                if not hits:
                    continue
            target = sorted(hits, key=lambda b: b["relative"]["distance_blocks"])[0]
            log("attempt %d: mining %s at %s" % (attempts, target["block"], target["position"]))
            mine_one(s, target["block"], target["position"])
    except NightFall:
        log("nightfall: stop moving, wait for day")
    return got


def craft_chain(s):
    scene = view_scene(s, allow_night=True)
    log("inventory: %s" % json.dumps(scene["self"]["inventory"]))
    for item, count in [("minecraft:oak_planks", 8),
                        ("minecraft:crafting_table", 1),
                        ("minecraft:stick", 4),
                        ("minecraft:wooden_pickaxe", 1)]:
        r = s.do("craft", {"item": item, "count": count}, timeout_s=60)
        t = r.get("terminal") or {}
        if not t:
            t = {"state": json.dumps(r.get("submit"))[:120]}
        log("craft %s x%d -> %s | %s" % (item, count, t.get("state"), (t.get("reason") or "")[:90]))
        scene = view_scene(s)
        log("  inventory now: %s" % json.dumps(scene["self"]["inventory"]))


def wait_ready(max_s=300):
    t0 = time.time()
    while time.time() - t0 < max_s:
        r = play.L.call("GET", "/v1/status")
        if (r.get("data") or {}).get("body_ready"):
            return True
        time.sleep(5)
    return False


def main():
    quota = int(sys.argv[1]) if len(sys.argv) > 1 else 6
    if not wait_ready():
        log("body never ready, abort")
        return
    s = play.Session()
    log("session start, pos=%s day=%s" % (
        s.view()["data"]["scene"]["self"]["block_position"], s.day_phase()))
    for day in range(4):
        if not wait_day(s):
            log("no daytime in window, abort")
            return
        try:
            got = harvest_logs(s, quota)
        except NightFall:
            got = 0
            log("nightfall mid-harvest")
        scene = s.view(retries=3)["data"]["scene"]
        log("end of day %d: logs=%s" % (day, json.dumps(scene["self"]["inventory"])))
        inv = scene["self"]["inventory"]
        total_logs = sum(v for k, v in inv.items() if k.endswith("_log"))
        if total_logs >= quota:
            break
    craft_chain(s)
    log("session end")


if __name__ == "__main__":
    main()
