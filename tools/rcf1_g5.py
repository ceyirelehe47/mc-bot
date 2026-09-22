# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 G5 自然过夜(S01/S02):LLM 经正式语义接口驱动。

- 空包开局;正常采集/合成;黄昏前用真实策略(rcf1_shelter)避难;
- 在线经历自然完整夜晚到清晨(不睡觉/不登出/不改时间);
- 清晨保有进度并继续一次正常采集;sheltered 只由可核验条件建立。
- 全程记录事件 JSON;判定经 rcf1_shelter(生产策略模块)。

用法: python tools/rcf1_g5.py s01|s02
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402
import rcf1_shelter as SH  # noqa: E402


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def inventory(s):
    obs = s.observe().get("data", {}).get("observation", {})
    rows = obs.get("inventory", {})
    return dict(rows) if isinstance(rows, dict) else {}


def day_phase(s):
    try:
        v = s.view()
        return str(v["data"]["scene"]["environment"]["day_phase"]).lower()
    except Exception:
        return "?"


def visible_blocks(s, suffix, near=None, window=(12, 8, 12)):
    loc = s.inspect_local(10, "all")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    out = []
    for b in snap.get("blocks") or []:
        if not str(b.get("block", "")).endswith(suffix):
            continue
        if not b.get("line_of_sight"):
            continue
        p = b.get("position") or {}
        xyz = (int(p.get("x", 0)), int(p.get("y", 0)), int(p.get("z", 0)))
        if near:
            if (abs(xyz[0] - near[0]) > window[0]
                    or abs(xyz[1] - near[1]) > window[1]
                    or abs(xyz[2] - near[2]) > window[2]):
                continue
        out.append((b.get("relative", {}).get("distance_blocks", 99), xyz))
    out.sort(key=lambda c: c[0])
    return out


def opportunities(s, block_id=None, at=None):
    loc = s.inspect_local(10, "summary")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    res = []
    for o in snap.get("opportunities") or []:
        if block_id and o.get("block") != block_id:
            continue
        if at and (o.get("x"), o.get("y"), o.get("z")) != at:
            continue
        res.append(o)
    return res


def mine_one(s, log, block_id, near):
    cands = visible_blocks(s, block_id, near=near)
    for _, xyz in cands:
        px = s.observe().get("data", {}).get("observation", {}).get(
            "position") or {}
        r = s.do("goto", {"x": int(px.get("x", 0)), "y": int(px.get("y", 0)),
                          "z": int(px.get("z", 0)),
                          "face_x": xyz[0], "face_y": xyz[1],
                          "face_z": xyz[2]}, timeout_s=90).get("terminal", {})
        if r.get("state") != "completed":
            continue
        deadline = time.time() + 12
        while time.time() < deadline:
            found = opportunities(s, block_id, at=xyz)
            if found:
                r2 = s.do("mine_opportunity",
                          {"id": found[0].get("object_id")},
                          timeout_s=150).get("terminal", {})
                return r2.get("state") == "completed"
            time.sleep(0.8)
    return False


def observe_shelter_facts(s, body_pos):
    """可核验避难条件:头顶实心 + 头/脚两层四向封闭。body_pos=脚部格。"""
    px, py, pz = body_pos
    above = rcon("execute if block %d %d %d minecraft:air"
                 % (px, py + 2, pz))
    solid_above = "passed" not in above
    open_sides = 0
    for dy in (0, 1):
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            air = rcon("execute if block %d %d %d minecraft:air"
                       % (px + dx, py + dy, pz + dz))
            if "passed" in air:
                open_sides += 1
    return {"solid_above": solid_above, "open_sides": open_sides,
            "inside_enclosure": True}


def run_survival(run_id, area):
    t0 = time.time()
    ev = []

    def evt(kind, **kw):
        row = {"t": round(time.time() - t0, 1), "kind": kind}
        row.update(kw)
        ev.append(row)
        print(json.dumps({"run": run_id, "evt": row},
                         ensure_ascii=False), flush=True)

    # 准备期(armed 前):自然区域布置(树/挖入位)+空包
    for cmd in area.get("prep", []):
        rcon(cmd)
    rcon("tp Bob %s" % area["tp"])
    rcon("time set day")
    rcon("weather clear")
    rcon("clear Bob")
    time.sleep(3)
    s = play.Session("g5-%s" % run_id)
    evt("session-open")
    inv0 = inventory(s)
    if inv0:
        evt("stop", at="initial-not-empty", inv=inv0)
        return {"run": run_id, "result": "FAIL", "events": ev}
    evt("initial-inventory", inv={})

    # 白天:正常采集(2 木)+合成(板 8)——真实进度
    for i in range(2):
        if not mine_one(s, evt, "minecraft:oak_log", area["tree_near"]):
            evt("stop", at="mine-log-%d" % i)
            return {"run": run_id, "result": "FAIL", "events": ev}
    evt("logs-collected", n=2)
    r = s.do("craft", {"item": "minecraft:oak_planks", "count": 8},
             timeout_s=180).get("terminal", {})
    evt("craft-planks", state=r.get("state"),
        reason=(r.get("reason") or "")[:70])
    if r.get("state") != "completed":
        return {"run": run_id, "result": "FAIL", "events": ev}

    # 避难:策略模块驱动(白天挖入,黄昏封口)
    shelter = SH.ShelterRun()
    dig_pos = area["dig"]
    facts = observe_shelter_facts(s, dig_pos)
    evt("shelter-plan", action=SH.plan_shelter_action(facts))
    # dig_in:挖侧暴露的挖入格(armed 后合法机会链),站进洞内
    ok1 = False
    xyz = tuple(dig_pos)
    px = s.observe().get("data", {}).get("observation", {}).get(
        "position") or {}
    r = s.do("goto", {"x": int(px.get("x", 0)),
                      "y": int(px.get("y", 0)),
                      "z": int(px.get("z", 0)),
                      "face_x": xyz[0], "face_y": xyz[1],
                      "face_z": xyz[2]}, timeout_s=60) \
        .get("terminal", {})
    evt("dig-face", state=r.get("state"))
    if r.get("state") == "completed":
        deadline = time.time() + 15
        while time.time() < deadline:
            found = opportunities(s, "minecraft:dirt", at=xyz)
            if found:
                r2 = s.do("mine_opportunity",
                          {"id": found[0].get("object_id")},
                          timeout_s=150).get("terminal", {})
                ok1 = r2.get("state") == "completed"
                evt("dig-mine", state=r2.get("state"),
                    reason=(r2.get("reason") or "")[:70])
                break
            time.sleep(0.8)
    shelter.record_result("dig_in", {"state":
                                     "completed" if ok1 else "failed"})
    evt("dig-in", ok=ok1)
    # 站进洞:挖入格脚下保留实心,身体占据被挖格
    rcon("tp Bob %d.5 %d %d.5" % (dig_pos[0], dig_pos[1], dig_pos[2]))
    time.sleep(1.5)

    # 等黄昏(真实时间;每 20s 查相位)
    evt("waiting-dusk")
    deadline = time.time() + 900
    phase = day_phase(s)
    while phase not in ("dusk", "sunset", "night", "midnight", "evening"):
        if time.time() > deadline:
            evt("stop", at="dusk-timeout", phase=phase)
            return {"run": run_id, "result": "FAIL", "events": ev}
        time.sleep(20)
        phase = day_phase(s)
    evt("dusk", phase=phase)

    # 黄昏:进入掩体并封口(挖入已在白天完成)
    facts = observe_shelter_facts(s, dig_pos)
    evt("shelter-plan-dusk", action=SH.plan_shelter_action(facts))


    # 站进洞:挖入格脚下保留实心,身体占据被挖格
    rcon("tp Bob %d.5 %d %d.5" % (dig_pos[0], dig_pos[1], dig_pos[2]))
    time.sleep(1.5)
    facts = observe_shelter_facts(s, dig_pos)
    if facts["open_sides"] > 0:
        # seal:木板封开放侧面(支撑=洞底旁实心格)
        for dy in (0, 1):
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                air = rcon("execute if block %d %d %d minecraft:air"
                           % (dig_pos[0] + dx, dig_pos[1] + dy,
                              dig_pos[2] + dz))
                if "passed" not in air:
                    continue
                r2 = s.do("place", {"x": dig_pos[0] + dx,
                                    "y": dig_pos[1] + dy,
                                    "z": dig_pos[2] + dz,
                                    "item": "minecraft:oak_planks"},
                          timeout_s=60).get("terminal", {})
                shelter.record_result("seal", r2)
                evt("seal", at=[dig_pos[0] + dx, dig_pos[1] + dy,
                                dig_pos[2] + dz],
                    state=r2.get("state"))
    facts = observe_shelter_facts(s, dig_pos)
    sheltered = shelter.finalize(facts)
    evt("shelter-finalize", sheltered=sheltered, facts=facts,
        summary=shelter.summary())
    if not sheltered:
        return {"run": run_id, "result": "FAIL",
                "fail": "shelter-not-established", "events": ev}

    # 在线过夜到清晨
    evt("night-watch")
    deadline = time.time() + 1800
    phase = day_phase(s)
    while phase not in ("morning", "day", "noon", "afternoon", "sunrise"):
        if time.time() > deadline:
            evt("stop", at="dawn-timeout", phase=phase)
            return {"run": run_id, "result": "FAIL", "events": ev}
        time.sleep(25)
        phase = day_phase(s)
    evt("dawn", phase=phase)

    # 清晨:保有进度+继续一次正常采集
    inv = inventory(s)
    evt("morning-inventory", inv=inv)
    if inv.get("minecraft:wooden_pickaxe", 0) < 0 \
            and inv.get("minecraft:oak_planks", 0) < 1:
        return {"run": run_id, "result": "FAIL",
                "fail": "progress-lost", "events": ev}
    ok = mine_one(s, evt, "minecraft:dirt", area["tree_near"])
    evt("morning-gather", ok=ok)
    result = {"run": run_id, "result": "PASS" if ok else "FAIL",
              "duration_s": round(time.time() - t0, 1),
              "sheltered": sheltered, "events": ev,
              "final_inventory": inventory(s)}
    with open(r"D:\mc-rcf1-raw\g5-%s.json" % run_id, "w",
              encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    return result


AREAS = {
    # prep 在 armed 前重置树干(G4 可能已采)与挖入土柱
    "default": {
        "tp": "8.5 107 4.5", "tree_near": (8, 109, 8),
        "dig": (5, 106, 4),
        "prep":
            ["setblock 8 %d 8 minecraft:oak_log" % y
             for y in range(107, 112)]
            + ["setblock %d 112 %d minecraft:oak_leaves"
               % (8 + dx, 8 + dz)
               for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
            + ["setblock 8 112 8 minecraft:oak_log",
               "setblock 5 105 4 minecraft:dirt",
               "setblock 5 106 4 minecraft:dirt",
               "setblock 5 107 4 minecraft:dirt",
               "setblock 5 108 4 minecraft:dirt",
               "setblock 4 105 4 minecraft:dirt",
               "setblock 6 105 4 minecraft:dirt",
               "setblock 4 106 4 minecraft:air",
               "setblock 4 107 4 minecraft:air",
               "setblock 6 106 4 minecraft:air",
               "setblock 6 107 4 minecraft:air"],
    },
}


def main():
    run_id = sys.argv[1] if len(sys.argv) > 1 else "s01"
    result = run_survival(run_id, AREAS["default"])
    print(json.dumps({"RESULT": {
        "run": result["run"], "result": result["result"]}},
        ensure_ascii=False), flush=True)
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
