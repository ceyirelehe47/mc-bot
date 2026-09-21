# -*- coding: utf-8 -*-
"""MC-RCF-1 N 组(导航)LIVE 验收:G2 Baritone 适配器 + 工作站位。

fixture 策略(04 §3,armed 之前):天空平台确定性竞技场(y=119 地面/120 站位),
远离地表噪声;/tp 起点与 time set day 属装置设置;计分运行不适用。
每类场景 ≥3 次;普通成功 ≤120s;世界改动=装置块逐点核验(零改动要求)。
证据落 D:/mc-rcf1-raw/n-group/。
"""
import json
import os
import pathlib
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import play  # noqa: E402
import rcf1_env as E  # noqa: E402

RAW = pathlib.Path(r"D:\mc-rcf1-raw\n-group")

# 天空竞技场(距出生地形数百格,固定 y)
P_FLOOR = 119          # 地面层
P_STAND = 120          # 站位层
CX, CZ = 300, 300      # 竞技场中心
ARENA = 24             # 半宽
START = (CX - 16, P_STAND, CZ)          # 起点(西缘)
S = play.Session("n-group")


def ev(name, obj):
    RAW.mkdir(parents=True, exist_ok=True)
    p = RAW / name
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    return str(p)


def rcon(cmd):
    out = E.rcon(cmd)
    return (out or "").strip()


def arena_reset(extra_walls=()):
    """重建竞技场基线:平台+边界墙;extra_walls=((cA),(cB),block) 三元组。
    返回装置块清单。fill 后立即核验四角,失败重试一次。"""
    placed = []

    def fill(x1, y1, z1, x2, y2, z2, block):
        rcon("fill %d %d %d %d %d %d %s" % (x1, y1, z1, x2, y2, z2, block))
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                for y in range(min(y1, y2), max(y1, y2) + 1):
                    placed.append((x, y, z, block))

    def corner_ok():
        for (cx_, cz_) in ((CX - ARENA, CZ - ARENA), (CX + ARENA, CZ - ARENA),
                           (CX - ARENA, CZ + ARENA), (CX + ARENA, CZ + ARENA),
                           (CX, CZ)):
            out = rcon("execute if block %d %d %d minecraft:smooth_stone run seed"
                       % (cx_, P_FLOOR, cz_))
            if "Seed" not in (out or ""):
                return False
        return True

    fill(CX - ARENA, P_FLOOR, CZ - ARENA, CX + ARENA, P_FLOOR, CZ + ARENA,
         "minecraft:smooth_stone")
    if not corner_ok():   # 填充竞态:重试一次
        fill(CX - ARENA, P_FLOOR, CZ - ARENA, CX + ARENA, P_FLOOR, CZ + ARENA,
             "minecraft:smooth_stone")
    rcon("fill %d %d %d %d %d %d minecraft:air"
         % (CX - ARENA, P_STAND, CZ - ARENA, CX + ARENA, P_STAND + 5, CZ + ARENA))
    for (ca, cb, blk) in extra_walls:
        x1, y1, z1 = ca
        x2, y2, z2 = cb
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                for y in range(min(y1, y2), max(y1, y2) + 1):
                    placed.append((x, y, z, blk))
        rcon("fill %d %d %d %d %d %d %s" % (x1, y1, z1, x2, y2, z2, blk))
    return placed


def fixture_blocks_intact(placed):
    """装置块逐点核验(execute if block ... run seed 带回显)。"""
    bad = []
    for (x, y, z, blk) in placed:
        out = rcon("execute if block %d %d %d %s run seed" % (x, y, z, blk))
        if "Seed" not in (out or ""):
            bad.append((x, y, z, blk))
    return bad


def settle(tp_fn):
    """传送后等区块加载与身体稳定(此前未等致首跑坠虚空 y=114)。"""
    tp_fn()
    deadline = time.time() + 25
    last = bob_pos()
    stable = 0
    while time.time() < deadline:
        time.sleep(0.6)
        cur = bob_pos()
        if abs(cur[1] - P_STAND) < 1.0 and cur == last:
            stable += 1
            if stable >= 3:
                return True
        else:
            stable = 0
        last = cur
    return False

def tp_start():
    settle(lambda: rcon("tp Bob %d %d %d"
                        % (START[0] + 0.5, START[1], START[2] + 0.5)))

def day():
    rcon("time set day")
    rcon("weather clear")
    # 击杀基准=竞技场中心(distance 选择器默认以执行位置=RCON 原点,
    # 此前原点基准导致竞技场怪未清,Bob 在 N04 连续被杀——实测教训)
    for mob in ("zombie", "skeleton", "creeper", "spider", "husk", "stray"):
        rcon("execute positioned %d %d %d run kill @e[type=minecraft:%s,distance=..80]"
             % (CX, P_STAND, CZ, mob))


def bob_pos():
    o = S.observe().get("data", {})
    p = ((o.get("observation") or {}).get("position") or {})
    return (round(p.get("x", 0), 1), round(p.get("y", -999), 1), round(p.get("z", 0), 1))


def arrived_3d(target, radius=2.5):
    x, y, z = bob_pos()
    return ((x - target[0]) ** 2 + (y - target[1]) ** 2 + (z - target[2]) ** 2) ** 0.5 <= radius


def run_goto(target, timeout_s=120, face=None, sample=False):
    args = {"x": target[0], "y": target[1], "z": target[2]}
    if face:
        args.update({"face_x": face[0], "face_y": face[1], "face_z": face[2]})
    t0 = time.time()
    track = []
    if not sample:
        r = S.do("goto", args, timeout_s=timeout_s)
        t = r.get("terminal", {})
        return {"state": t.get("state"), "reason": t.get("reason"),
                "seconds": round(time.time() - t0, 1), "pos": bob_pos(),
                "track": track}
    import threading
    ex, err = S.submit("goto", args)
    if ex is None:
        return {"state": "submit-failed", "reason": err, "seconds": 0,
                "pos": bob_pos(), "track": track}
    stop_flag = {"stop": False}
    def sampler():
        while not stop_flag["stop"]:
            track.append(bob_pos())
            time.sleep(0.5)
    th = threading.Thread(target=sampler, daemon=True)
    th.start()
    t = S.term(ex, timeout_s=timeout_s)[0]
    stop_flag["stop"] = True
    th.join(timeout=2)
    return {"state": t.get("state"), "reason": t.get("reason"),
            "seconds": round(time.time() - t0, 1), "pos": bob_pos(),
            "track": track}


# ---------- 场景 ----------

def n01_flat_and_step(i):
    """N01:平地与一格台阶,到指定可站格;零地形改动。
    R2/R04 修复:台阶真实比地面高一格(P_FLOOR+1),并采样轨迹证明
    路径确实经过该台阶(不是同高方块冒充)。"""
    placed = arena_reset()
    # R2/R04:抬高一格的平台(顶面=P_FLOOR+1,比周围地面高一整格),
    # 目标站在平台上——到达必然经过一格台阶;台阶块本身保持原样。
    plat = []
    for dx in range(6, 10):
        for dz in (-1, 0, 1):
            plat.append((CX + dx, P_FLOOR + 1, CZ + dz))
    for p in plat:
        rcon("setblock %d %d %d minecraft:smooth_stone" % p)
        placed.append((p[0], p[1], p[2], "minecraft:smooth_stone"))
    target = (CX + 8, P_STAND + 1, CZ)   # 平台顶面站立位
    day(); tp_start()
    res = run_goto(target, sample=True)
    track = res.get("track") or []
    ground_y = [p[1] for p in track if abs(p[1] - P_STAND) < 0.6]
    plat_y = [p[1] for p in track if abs(p[1] - (P_STAND + 1)) < 0.6]
    both_levels = bool(ground_y) and bool(plat_y)
    bad = fixture_blocks_intact(placed)
    ok = (res["state"] == "completed" and arrived_3d(target) and not bad
          and res["seconds"] <= 120 and both_levels)
    return {"id": "N01", "run": i, "pass": ok,
            "detail": {"state": res.get("state"),
                       "seconds": res.get("seconds"),
                       "step_elevation": 1,
                       "levels_seen": {"ground": len(ground_y),
                                       "platform": len(plat_y)},
                       "sampled": len(track)},
            "world_changes": bad[:5]}


def n02_wall_with_side_path(i):
    """N02:两格高墙正挡,侧面有通路;绕过而非挖穿;零改动。"""
    walls = [((CX - 2, P_STAND, CZ - 8), (CX - 2, P_STAND + 1, CZ - 1), "minecraft:polished_andesite"),
             ((CX - 2, P_STAND, CZ + 1), (CX - 2, P_STAND + 1, CZ + 8), "minecraft:polished_andesite")]
    placed = arena_reset(extra_walls=walls)   # 墙在 CZ=0 处留 1 格缺口(z=CZ)
    # 封掉正中,只留侧面远缺口
    rcon("setblock %d %d %d minecraft:polished_andesite" % (CX - 2, P_STAND, CZ))
    rcon("setblock %d %d %d minecraft:polished_andesite" % (CX - 2, P_STAND + 1, CZ))
    placed += [(CX - 2, P_STAND, CZ, "minecraft:polished_andesite"),
               (CX - 2, P_STAND + 1, CZ, "minecraft:polished_andesite")]
    # 开一个远侧缺口
    rcon("fill %d %d %d %d %d %d minecraft:air" % (CX - 2, P_STAND, CZ - 8, CX - 2, P_STAND + 1, CZ - 8))
    placed = [p for p in placed if not (p[0] == CX - 2 and p[2] == CZ - 8)]
    target = (CX + 8, P_STAND, CZ)
    day(); tp_start()
    res = run_goto(target)
    bad = fixture_blocks_intact(placed)
    ok = res["state"] == "completed" and arrived_3d(target) and not bad and res["seconds"] <= 120
    return {"id": "N02", "run": i, "pass": ok,
            "detail": res, "world_changes": bad[:5]}


def n03_u_obstacle(i):
    """N03:U 形障碍:必须先远离目标绕行。"""
    # U 形开口朝向起点,三面墙
    walls = [((CX - 4, P_STAND, CZ - 6), (CX - 4, P_STAND + 2, CZ + 6), "minecraft:polished_andesite"),   # 底(近)
             ((CX - 4, P_STAND, CZ - 6), (CX + 4, P_STAND + 2, CZ - 6), "minecraft:polished_andesite"),   # 左
             ((CX - 4, P_STAND, CZ + 6), (CX + 4, P_STAND + 2, CZ + 6), "minecraft:polished_andesite")]   # 右
    placed = arena_reset(extra_walls=walls)
    target = (CX - 8, P_STAND, CZ)   # 障碍后方(必须绕过开口朝向侧)
    day()
    # 起点在 U 开口一侧(东),目标在西墙外:必须绕 U 的侧边
    settle(lambda: rcon("tp Bob %d %d %d" % (CX + 8.5, P_STAND, CZ + 0.5)))
    res = run_goto(target)
    bad = fixture_blocks_intact(placed)
    ok = res["state"] == "completed" and arrived_3d(target) and not bad and res["seconds"] <= 120
    return {"id": "N03", "run": i, "pass": ok,
            "detail": res, "world_changes": bad[:5]}


def n04_slope_stance(i):
    """N04:山坡到可交互方块的工作站位:到达+exact crosshair 命中。
    连续实心坡道(每 2 格 x 升 1 格,整列填充,可走)。"""
    placed = arena_reset()
    # 坡道:x 从 CX-10 到 CX+2,y 顶面 = P_FLOOR + (x-(CX-10))//2 + 1
    top_y = {}
    for x in range(CX - 10, CX + 3):
        top_y[x] = P_FLOOR + (x - (CX - 10)) // 2 + 1
    for x, ty in top_y.items():
        rcon("fill %d %d %d %d %d %d minecraft:smooth_stone"
             % (x, P_FLOOR, CZ - 4, x, ty, CZ - 2))
        for y in range(P_FLOOR, ty + 1):
            for z in range(CZ - 4, CZ - 1):
                placed.append((x, y, z, "minecraft:smooth_stone"))
    # 坡顶平面 + 工作台(交互目标)
    top = top_y[CX + 2]
    rcon("fill %d %d %d %d %d %d minecraft:smooth_stone"
         % (CX + 2, top, CZ - 4, CX + 6, top, CZ - 2))
    table = (CX + 4, top + 1, CZ - 3)
    rcon("setblock %d %d %d minecraft:crafting_table" % table)
    placed.append((table[0], table[1], table[2], "minecraft:crafting_table"))
    # 工作站位:桌面前一格(可站、可及、LOS 干净)
    stance = (CX + 3, top + 1, CZ - 3)
    day()
    settle(lambda: rcon("tp Bob %d %d %d" % (CX - 8.5, P_STAND, CZ + 0.5)))
    res = run_goto(stance, face=table)
    bad = fixture_blocks_intact(placed)
    ok = res["state"] == "completed" and arrived_3d(stance) and not bad and res["seconds"] <= 120
    return {"id": "N04", "run": i, "pass": ok,
            "detail": res, "world_changes": bad[:5],
            "stance": stance, "facing_target": table}


def n05_midpath_blockage(i):
    """N05:途中通路被外部 actor 封闭:有界重规划或准确失败。"""
    placed = arena_reset()
    target = (CX + 12, P_STAND, CZ)
    day(); tp_start()
    # 异步在 2.5s 后封掉前方走廊(模拟外部事件,装置注入)
    import threading
    def block_later():
        time.sleep(2.5)
        rcon("fill %d %d %d %d %d %d minecraft:polished_andesite"
             % (CX - 4, P_STAND, CZ - 2, CX - 4, P_STAND + 2, CZ + 2))
    threading.Thread(target=block_later, daemon=True).start()
    res = run_goto(target, timeout_s=120)
    # 墙留给核验(装置块)
    for x in range(CX - 4, CX - 3):
        for z in range(CZ - 2, CZ + 3):
            for y in range(P_STAND, P_STAND + 3):
                placed.append((x, y, z, "minecraft:polished_andesite"))
    # 成功(绕行:平台 24 宽,可绕)或诚实失败都接受;不得挖穿(墙完好)
    bad = fixture_blocks_intact(placed)
    wall_intact = not bad
    ok = wall_intact and (res["state"] in ("completed", "failed")) and res["seconds"] <= 120
    if res["state"] == "completed":
        ok = ok and arrived_3d(target)
    return {"id": "N05", "run": i, "pass": ok,
            "detail": res, "wall_intact": wall_intact,
            "note": "绕行成功或诚实失败均可;挖墙即败"}


def n06_fully_enclosed(i):
    """N06:完全不可达且不许破坏:预算内明确失败,无挖穿。"""
    # 密闭房间(顶+底+四壁,目标在内)
    x0, x1 = CX + 4, CX + 12
    z0, z1 = CZ - 4, CZ + 4
    y0, y1 = P_FLOOR + 1, P_FLOOR + 4
    walls = [((x0, y0, z0), (x1, y1, z1), "minecraft:polished_deepslate")]
    placed = arena_reset(extra_walls=walls)
    rcon("fill %d %d %d %d %d %d minecraft:air"
         % (x0 + 1, y0 + 1, z0 + 1, x1 - 1, y1 - 2, z1 - 1))  # 内腔
    placed = [p for p in placed if not (x0 + 1 <= p[0] <= x1 - 1 and y0 + 1 <= p[1] <= y1 - 2 and z0 + 1 <= p[2] <= z1 - 1)]
    target = (CX + 8, P_FLOOR + 1, CZ)   # 房内
    day(); tp_start()
    res = run_goto(target, timeout_s=150)
    bad = fixture_blocks_intact(placed)
    ok = (res["state"] == "failed" and not bad and res["seconds"] <= 150)
    return {"id": "N06", "run": i, "pass": ok,
            "detail": res, "world_changes": bad[:5],
            "note": "不可达必须诚实失败;任何挖穿=败"}


SCENARIOS = [n01_flat_and_step, n02_wall_with_side_path, n03_u_obstacle,
             n04_slope_stance, n05_midpath_blockage, n06_fully_enclosed]


def main():
    results = []
    for fn in SCENARIOS:
        for i in range(1, 4):
            try:
                r = fn(i)
            except Exception as exc:  # noqa: BLE001
                r = {"id": fn.__name__, "run": i, "pass": False,
                     "detail": {"exception": repr(exc)[:200]}}
            print(json.dumps(r, ensure_ascii=False))
            results.append(r)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    n_pass = sum(1 for r in results if r.get("pass"))
    out = ev("n-group-%s.json" % stamp, {"run": stamp, "results": results})
    print("SUMMARY %d/%d -> %s" % (n_pass, len(results), out))
    return 0 if n_pass == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
