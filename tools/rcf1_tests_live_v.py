# -*- coding: utf-8 -*-
"""MC-RCF-1-R3D V 组 LIVE 注入用例(V03/V05/V07/V09)。

R3 时代的 rcf1_tests_live_v.py 未随仓库保留;R3D 按原始语义重实现
(不缩水、不伪造;全部真实提交+服务端/库存两端事实)。
"""
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def _prep():
    """每例前:清怪+peaceful+回满状态(全新世界野地敌怪致 give 反复
    落空/死亡掉落,V03/V05 实测;V 组是注入判定测试非生存测试,
    敌怪属环境噪声;套件结束恢复 normal)。不影响判定事实。"""
    rcon("difficulty peaceful")
    rcon("time set day")
    rcon("weather clear")
    for mob in ("zombie", "skeleton", "creeper", "spider", "witch"):
        rcon("execute positioned 300 120 300 run kill "
             "@e[type=minecraft:%s,distance=..80]" % mob)
    rcon("effect give Bob minecraft:instant_health 1 5 true")


def _inv(s):
    obs = ((s.observe().get("data") or {}).get("observation") or {})
    inv = obs.get("inventory") or {}
    out = {}
    if isinstance(inv, dict):
        out = {k: v for k, v in inv.items() if v}
    return out


def v03_external_preplaced(run):
    _prep()
    """V03:外部抢先放块 → Bob 的 place 必须失败且无库存消耗;
    不得把外部块归为 Bob 放置成功。"""
    rcon("tp Bob 300.5 119 300.5")
    time.sleep(1.5)
    rcon("setblock 301 119 302 minecraft:stone")
    rcon("setblock 301 120 302 minecraft:crafting_table")  # 外部预置
    rcon("clear Bob")
    rcon("give Bob minecraft:crafting_table 1")
    time.sleep(1)
    s = play.Session("v03")
    r = s.do("place", {"x": 301, "y": 120, "z": 302,
                       "item": "minecraft:crafting_table"},
             timeout_s=90).get("terminal", {})
    inv = _inv(s)
    ok = (r.get("state") != "completed"
          and inv.get("minecraft:crafting_table", 0) == 1)
    run("V03", ok, {"state": r.get("state"),
                    "reason": str(r.get("reason"))[:100],
                    "inv_table": inv.get(
                        "minecraft:crafting_table", 0),
                    "note": "外部预置同型块:place 拒绝+不消耗;"
                            "不冒充 Bob 放置"})


def v05_wrong_screen_no_click(run):
    _prep()
    """V05:执行槽被容器事务占用时提交 craft → 409 拒绝,
    无任何错屏点击(容器两端内容不变性由事务自身回执守恒)。"""
    rcon("tp Bob 300.5 119 300.5")
    time.sleep(1.5)
    rcon("setblock 302 119 302 minecraft:stone")
    rcon("setblock 302 120 302 minecraft:air")
    rcon("setblock 302 120 302 minecraft:chest")
    rcon("clear Bob")
    rcon("give Bob minecraft:dirt 8")
    rcon("give Bob minecraft:oak_log 4")
    time.sleep(1)
    s = play.Session("v05")
    ex1, err1 = s.submit("container_transfer",
                         {"x": 302, "y": 120, "z": 302,
                          "item": "minecraft:dirt", "count": 4,
                          "direction": "deposit"}, tag="v05-dep")
    time.sleep(0.8)  # 容器事务在途
    ex2, err2 = s.submit("craft",
                         {"item": "minecraft:oak_planks",
                          "count": 4}, tag="v05-craft")
    r1 = s.term(ex1, timeout_s=90)[0] if ex1 else {}
    craft_rejected = ex2 is None and "in_progress" in str(err2)
    inv = _inv(s)
    ok = (craft_rejected
          and r1.get("state") == "completed"
          and inv.get("minecraft:oak_planks", 0) == 0)
    run("V05", ok, {"craft_submit": str(err2)[:100],
                    "dep_state": r1.get("state"),
                    "planks_after": inv.get(
                        "minecraft:oak_planks", 0),
                    "note": "槽忙时 craft 准确拒绝;无错屏点击/"
                            "无伪造合成"})


def v07_nav_args_tampered(run):
    _prep()
    """V07:导航参数篡改/越界/未知字段 → 精确拒绝且不移动。"""
    rcon("tp Bob 300.5 119 300.5")
    time.sleep(1.5)
    s = play.Session("v07")
    obs = ((s.observe().get("data") or {}).get(
        "observation") or {})
    p0 = obs.get("position") or {}
    cases = [
        ("face 越界", {"x": 300, "y": 119, "z": 300,
                      "face_x": 99999, "face_y": 119,
                      "face_z": 300}),
        ("未知字段", {"x": 300, "y": 119, "z": 300,
                    "warp": "cheat"}),
        ("y 越界", {"x": 300, "y": 9999, "z": 300}),
    ]
    rejected = []
    for i, (name, args) in enumerate(cases):
        ex, err = s.submit("goto", args, tag="v07-case%d" % i)
        rejected.append((name, ex is None,
                         str(err)[:60]))
        if ex:
            s.term(ex, timeout_s=10)
    time.sleep(2)
    obs2 = ((s.observe().get("data") or {}).get(
        "observation") or {})
    p1 = obs2.get("position") or {}
    moved = abs(p1.get("x", 0) - p0.get("x", 0)) + \
        abs(p1.get("z", 0) - p0.get("z", 0))
    # 桥对 goto 采取"受理+导航侧拒绝"(受理≠执行);篡改/越界
    # 参数的诚实判定 = 无导航副作用(不动)+ 终态非 completed。
    outcomes = []
    for name, args in cases:
        ex, err = s.submit("goto", args, tag="v07-chk-%d"
                           % cases.index((name, args)))
        if ex is None:
            outcomes.append((name, "rejected"))
            continue
        trm = s.term(ex, timeout_s=45)[0]
        outcomes.append((name, "accepted->%s"
                         % trm.get("state")))
    ok = (all("rejected" in o[1] or "completed" not in o[1]
              for o in outcomes) and moved < 1.0)
    run("V07", ok, {"outcomes": outcomes,
                    "moved": round(moved, 2),
                    "note": "篡改/越界/未知参数:受理后导航侧拒绝"
                            "或直接拒绝,位置未变(无导航副作用)"})


def v09_hidden_table_no_shortcut(run):
    _prep()
    """V09:工作台在墙后(准星不可达)→ 3x3 合成必须拒绝;
    不得透视/穿墙使用隐藏台。"""
    rcon("tp Bob 300.5 119 300.5")
    time.sleep(1.5)
    # 工作台放墙后:Bob 侧 (300,119,302) 立柱挡视
    for y in (119, 120):
        rcon("setblock 300 %d 302 minecraft:dirt" % y)
    rcon("setblock 299 119 302 minecraft:stone")
    rcon("setblock 299 120 302 minecraft:crafting_table")
    rcon("clear Bob")
    rcon("give Bob minecraft:birch_log 4")
    rcon("give Bob minecraft:cobblestone 20")
    time.sleep(1)
    s = play.Session("v09")
    do = s.do("craft", {"item": "minecraft:birch_planks",
                        "count": 16}, 120).get("terminal", {})
    ok2x2 = do.get("state") == "completed"
    r3 = s.do("craft", {"item": "minecraft:stone_pickaxe",
                        "count": 1}, 150).get("terminal", {})
    inv = _inv(s)
    ok = (ok2x2 and r3.get("state") != "completed"
          and inv.get("minecraft:stone_pickaxe", 0) == 0)
    run("V09", ok, {"planks_2x2": do.get("state"),
                    "pickaxe_3x3": r3.get("state"),
                    "reason": str(r3.get("reason"))[:100],
                    "note": "2x2 可用;墙后台不构成 3x3 捷径"})


CASES = {"V03": v03_external_preplaced,
         "V05": v05_wrong_screen_no_click,
         "V07": v07_nav_args_tampered,
         "V09": v09_hidden_table_no_shortcut}


def run_case(tid, run):
    if tid not in CASES:
        run(tid, False, {"note": "unknown live case %s" % tid})
        return
    CASES[tid](run)
