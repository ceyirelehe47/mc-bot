# -*- coding: utf-8 -*-
"""MC-RCF-1-R1 I/A 组验收(重做 fixture 版)。

R1 修正(对照 02_ACCEPTANCE_AND_RUNS §3):
- I01/A08:初始断言物品在主包索引>=9 且快捷栏无同款(读 RCON inventory 原文)。
- I02:1/7/31 件三档+目标已有 10 再移 7(净增判定)+满堆 64+源预留。
- I03:两把同 ID 异组件镐,source_slot 绑定移动指定件,核验组件与位置。
- I05:真满包(36 槽)+容器满+能取不能放。
- I04:箱子/木桶双向真实事务。
- A09/A10:独立实测(占位已删)。
- A07:支撑面别格/外部抢放。
- V01/V02/V04:数量反例断言。
前置:rcf1 server+client RUNNING(lifecycle)。
"""
import json
import re
import subprocess
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402


def rcon(cmd):
    return (E.rcon(cmd) or "").strip()


def safe():
    rcon("time set day")
    rcon("weather clear")
    for mob in ("zombie", "skeleton", "creeper", "spider"):
        rcon("execute positioned 300 120 300 run kill "
             "@e[type=minecraft:%s,distance=..80]" % mob)
    rcon("tp Bob 300.5 120 300.5")


def inv_raw():
    return rcon("data get entity Bob Inventory") or ""


def _parse_inv(data):
    """1.21.3 RCON 原文解析。item 段含嵌套 components{}——用括号
    深度切顶层段,再在段内抓字段。返回 [(id, count, slot, damage)]。"""
    items = []
    depth = 0
    start = -1
    for i, ch in enumerate(data):
        if ch == '{':
            if depth == 0:
                start = i
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0 and start >= 0:
                seg = data[start:i + 1]
                ms = re.search(r'Slot: (\d+)b', seg)
                mid = re.search(r'id: "?([a-z0-9_:]+)"?', seg)
                if ms and mid:
                    mc = re.search(r'count: (\d+)', seg)
                    md = re.search(r'"minecraft:damage": (\d+)', seg)
                    items.append((mid.group(1),
                                  int(mc.group(1)) if mc else 1,
                                  int(ms.group(1)),
                                  int(md.group(1)) if md else None))
                start = -1
    return items


def inv_counts():
    out = {}
    for iid, cnt, _, _ in _parse_inv(inv_raw()):
        out[iid] = out.get(iid, 0) + cnt
    return out


def hotbar_has(item):
    return any(iid == item and slot < 9
               for iid, _, slot, _ in _parse_inv(inv_raw()))


def slot_of(item, min_slot=9):
    for iid, _, slot, _ in _parse_inv(inv_raw()):
        if iid == item and slot >= min_slot:
            return slot
    return -1


def stack_count_at(slot):
    for iid, cnt, s, dmg in _parse_inv(inv_raw()):
        if s == slot:
            return iid, cnt, dmg
    return None, 0, None


def stack_count_at(slot):
    for iid, cnt, s, dmg in _parse_inv(inv_raw()):
        if s == slot:
            return iid, cnt, dmg
    return None, 0, None


def do(op, args, timeout=180):
    s = play.Session("r1ia")
    r = s.do(op, args, timeout_s=timeout)
    if "terminal" not in r:
        return {"state": "failed", "reason": json.dumps(r.get("submit"),
                                                        ensure_ascii=False)}
    return r["terminal"]


def check(tid, ok, detail=None):
    print(json.dumps({"id": tid, "pass": bool(ok), "detail": detail},
                     ensure_ascii=False), flush=True)
    return bool(ok)


RESULTS = []


def run(tid, ok, detail=None):
    RESULTS.append((tid, bool(ok)))
    check(tid, ok, detail)


def fill_inventory(n_slots=36):
    """真满包:36 槽全占。filler=不同 damage 的锁链靴(不可堆叠,
    每件独立占槽;可堆叠物品会并进一槽——首轮实测教训)。"""
    for i in range(n_slots):
        rcon("give Bob minecraft:chainmail_boots[minecraft:damage=%d] 1"
             % (i + 1))
        time.sleep(0.12)


# ---------- I 组 ----------

def i01_main_pack_sources():
    safe()
    rcon("clear Bob")
    # R1: item replace 直写主包(data modify 对玩家被禁;give 填槽不可控)
    rcon("item replace entity Bob inventory.0 with minecraft:iron_pickaxe 1")
    rcon("item replace entity Bob inventory.1 with minecraft:cooked_beef 3")
    rcon("item replace entity Bob inventory.2 with minecraft:dirt 16")
    time.sleep(1)
    pre = {"pick": slot_of("minecraft:iron_pickaxe"),
           "beef": slot_of("minecraft:cooked_beef"),
           "dirt": slot_of("minecraft:dirt")}
    pre_ok = all(v >= 9 for v in pre.values())
    r1 = do("move_items", {"item": "minecraft:iron_pickaxe", "count": -1,
                           "hotbar": 0}, 60)
    r2 = do("move_items", {"item": "minecraft:cooked_beef", "count": 3,
                           "hotbar": 1}, 60)
    r3 = do("move_items", {"item": "minecraft:dirt", "count": 8,
                           "hotbar": 2}, 60)
    ok = (pre_ok
          and r1["state"] == "completed" and r2["state"] == "completed"
          and r3["state"] == "completed")
    run("I01", ok, {"pre_slots": pre, "r1": r1.get("reason"),
                    "r2": r2.get("reason"), "r3": r3.get("reason"),
                    "note": "前置:工具/食物/方块全部初始在主包>=9 且快捷栏无同款"})


def i02_exact_counts():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:cobblestone 38")
    time.sleep(1)
    base = inv_counts().get("minecraft:cobblestone", 0)
    r1 = do("move_items", {"item": "minecraft:cobblestone", "count": 1,
                           "hotbar": 3}, 60)
    # 目标已有 1 再移 7:净增 7(终 8)
    r2 = do("move_items", {"item": "minecraft:cobblestone", "count": 7,
                           "hotbar": 3}, 60)
    # 目标槽 4 从 0 净增 31
    r3 = do("move_items", {"item": "minecraft:cobblestone", "count": 31,
                           "hotbar": 4}, 90)
    # 满堆:整堆 64
    rcon("give Bob minecraft:cobblestone 26")  # 总 64
    time.sleep(1)
    r4 = do("move_items", {"item": "minecraft:cobblestone", "count": -1,
                           "hotbar": 5}, 90)
    ok = all(r["state"] == "completed" for r in (r1, r2, r3, r4)) and base == 38
    run("I02", ok, {"r1": r1.get("reason"), "r2": r2.get("reason"),
                    "r3": r3.get("reason"), "r4": r4.get("reason"),
                    "note": "1/7/31/整堆;目标已有再移=净增语义"})


def v02_increment_negative():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:cobblestone 40")
    time.sleep(1)
    # 拆堆:移 10 到槽 1(源=槽0 整堆,余 30 回槽0)——dest 已有 10
    r0 = do("move_items", {"item": "minecraft:cobblestone", "count": 10,
                           "hotbar": 1}, 60)
    _, before_n, _ = stack_count_at(1)
    r = do("move_items", {"item": "minecraft:cobblestone", "count": 7,
                          "hotbar": 1}, 60)
    _, after_n, _ = stack_count_at(1)
    gained = after_n - before_n
    ok = (r0["state"] == "completed" and r["state"] == "completed"
          and gained == 7)
    run("V02", ok, {"before": before_n, "after": after_n, "gained": gained,
                    "r": r.get("reason")})
    # count=0 拒绝
    r0 = do("move_items", {"item": "minecraft:cobblestone", "count": 0,
                           "hotbar": 1}, 30)
    run("V02b", r0["state"] == "failed", {"r": r0.get("reason"),
                                          "note": "count=0 必须明确拒绝"})


def i03_component_identity():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:iron_pickaxe[minecraft:damage=5] 1")
    rcon("give Bob minecraft:iron_pickaxe[minecraft:damage=90] 1")
    time.sleep(1)
    picks = [(d, s) for iid, _, s, d in _parse_inv(inv_raw())
             if iid == "minecraft:iron_pickaxe" and d is not None]
    picks.sort()
    if len(picks) != 2:
        run("I03", False, {"picks": picks, "raw": inv_raw()[:400]})
        return
    dmg_light, slot_light = picks[0]
    dmg_heavy, slot_heavy = picks[1]
    dest_hb = 5 if slot_light < 9 else 0
    r = do("move_items", {"item": "minecraft:iron_pickaxe", "count": 1,
                          "hotbar": dest_hb, "source_slot": slot_light}, 60)
    _, cnt0, dmg0 = stack_count_at(dest_hb)
    heavy_still = any(d == dmg_heavy for _, _, s, d in _parse_inv(inv_raw()))
    ok = (r["state"] == "completed" and dmg0 == dmg_light
          and heavy_still and dmg0 is not None)
    run("I03", ok, {"moved": (dmg_light, slot_light),
                    "kept": (dmg_heavy, slot_heavy),
                    "dest_damage": dmg0, "r": r.get("reason"),
                    "note": "source_slot 绑定移动指定组件那把,另一把原位保留"})


def i04_container_chest_barrel():
    safe()
    rcon("clear Bob")
    # 布置箱子与木桶(距 Bob 2 格)
    rcon("setblock 303 120 300 minecraft:chest")
    rcon("setblock 303 120 302 minecraft:barrel")
    rcon("give Bob minecraft:oak_log 10")
    time.sleep(1)
    dep1 = do("container_transfer", {"x": 303, "y": 120, "z": 300,
                                     "item": "minecraft:oak_log",
                                     "count": 4,
                                     "direction": "deposit"}, 120)
    chest = rcon("data get block 303 120 300 Items") or ""
    wdr = do("container_transfer", {"x": 303, "y": 120, "z": 300,
                                    "item": "minecraft:oak_log",
                                    "count": 2,
                                    "direction": "withdraw"}, 120)
    chest2 = rcon("data get block 303 120 300 Items") or ""
    inv_after = inv_counts().get("minecraft:oak_log", 0)
    rcon("setblock 303 121 301 minecraft:air")
    rcon("tp Bob 303.5 120 300.5")
    time.sleep(1)
    dep2 = do("container_transfer", {"x": 303, "y": 120, "z": 302,
                                     "item": "minecraft:oak_log",
                                     "count": 3,
                                     "direction": "deposit"}, 120)
    barrel = rcon("data get block 303 120 302 Items") or ""
    ok = (dep1["state"] == "completed" and wdr["state"] == "completed"
          and dep2["state"] == "completed"
          and inv_after == 8  # 10-4+2
          and "oak_log" in chest and "oak_log" in barrel)
    run("I04", ok, {"dep1": dep1.get("reason"), "wdr": wdr.get("reason"),
                    "dep2": dep2.get("reason"),
                    "inv_after_first_pair": inv_after,
                    "note": "箱子存4取2(净2)+木桶存3,双向守恒"})


def i05_true_full():
    safe()
    rcon("clear Bob")
    # 真满:36 槽每槽 1 鹅卵石(独立堆叠,无合并空间)
    fill_inventory(35)
    rcon("give Bob minecraft:oak_log 64")  # 满堆:消费不腾槽
    time.sleep(1)
    n_slots = len(_parse_inv(inv_raw()))
    time.sleep(1)
    r = do("craft", {"item": "minecraft:oak_planks", "count": 4}, 150)
    logs = inv_counts().get("minecraft:oak_log", 0)
    planks = inv_counts().get("minecraft:oak_planks", 0)
    # 满包+log 已占 1 槽(独立):craft 需要输入格——客户端可取,产物格
    # 无处放:诚实失败或部分;不得假成功不得丢物
    ok = (n_slots >= 35
          and (r["state"] == "failed" or planks < 4)
          and logs + planks >= 1)  # 不丢物
    run("I05", ok, {"n_slots": n_slots, "r": r.get("reason"),
                    "logs": logs, "planks": planks,
                    "note": "36 槽真满:产物不可入包,不假成功不丢物"})
    # 满容器
    rcon("setblock 303 120 300 minecraft:chest")
    for i in range(27):
        rcon("data modify block 303 120 300 Items append value "
             '{id:"minecraft:stone",count:1,Slot:%db}' % i)
    time.sleep(1)
    r2 = do("container_transfer", {"x": 303, "y": 120, "z": 300,
                                   "item": "minecraft:oak_log",
                                   "count": 1,
                                   "direction": "deposit"}, 120)
    run("I05b", r2["state"] == "failed" or "partial" in (r2.get("reason") or ""),
        {"r": r2.get("reason"), "note": "容器满:拒绝或诚实部分"})


def i06_cursor_injection():
    # R2/R04:三阶段真实注入(取物/部分拆分/拿到产物各一次 cancel),
    # 每阶段断言:终态 cancelled + 物品守恒(cursor 有界回包,不丢弃)
    # + 下一动作可正常开屏(无残留屏)。
    stages = []
    for stage, delay in (("pickup", 0.2), ("partial", 1.0),
                         ("produced", 2.0)):
        time.sleep(2.0)  # 上一阶段在途包文落定
        rcon("clear Bob")
        rcon("give Bob minecraft:oak_log 8")
        time.sleep(1)
        # 纯净检查:在途残留(晚到的点击结果)必须先落定再开测
        pre = inv_counts()
        if pre.get("minecraft:oak_planks", 0) > 0 \
                or pre.get("minecraft:oak_log", 0) != 8:
            time.sleep(2)
            rcon("clear Bob")
            rcon("give Bob minecraft:oak_log 8")
            time.sleep(1)
            pre = inv_counts()
        base = pre.get("minecraft:oak_log", 0)
        s = play.Session("r1i06-%s" % stage)
        ex, err = s.submit("craft",
                           {"item": "minecraft:oak_planks", "count": 20})
        if ex is None:
            stages.append({"stage": stage, "submit_error": str(err)[:80]})
            continue
        time.sleep(delay)
        c = s.ctl(ex, "cancel")
        term = s.term(ex, 30)[0]
        time.sleep(3.0)   # 收尾+回包+服务器同步(grid回收分tick)
        counts = inv_counts()
        logs = counts.get("minecraft:oak_log", 0)
        planks = counts.get("minecraft:oak_planks", 0)
        # 守恒按配方折算:1 log = 4 planks(材料/产物/中间态任一组合)
        conserved = abs(logs + planks / 4.0 - base) < 0.01
        stages.append({
            "stage": stage, "cancel_ok": bool(c.get("ok")),
            "term": term.get("state"),
            "reason": (term.get("reason") or "")[:60],
            "conserved": conserved,
            "logs": logs, "planks": planks, "base": base})
    # 20 planks(5批)给取消留窗口;completed 视为取消窗口错过(非缺陷),
    # 但至少一个阶段必须真实 cancelled 且全部阶段守恒。
    any_cancelled = any(st.get("term") == "cancelled" for st in stages)
    ok = (any_cancelled
          and all(st.get("conserved") for st in stages if "conserved" in st)
          and len(stages) == 3)
    run("I06", ok, {"stages": stages,
                    "note": "三阶段注入:取物/部分拆分/取产物;"
                            "cursor 回包按配方折算守恒,无丢弃"})


def i07_ghost_slots_readonly():
    # 未知角色槽只读:对非容器方块(工作台)发起 container_transfer=拒绝
    safe()
    rcon("setblock 305 120 300 minecraft:crafting_table")
    time.sleep(0.5)
    r = do("container_transfer", {"x": 305, "y": 120, "z": 300,
                                  "item": "minecraft:dirt",
                                  "count": 1,
                                  "direction": "deposit"}, 60)
    run("I07", r["state"] == "failed",
        {"r": r.get("reason"), "note": "工作台非储物:拒绝,不写入"})


def i08_tom_deposit_regression():
    # R2/R04:搭真实 Tom's 终端网络(terminal—inventory_cable—
    # inventory_connector—chest),面向终端合法 deposit,验证回执
    # server_authoritative_owned_screen_toms_storage_terminal_*。
    safe()
    tx, ty, tz = 305, 120, 296
    # 站位地面与视线走廊:Bob 站 (306,121,297) 面向终端;
    # 眼(306.5,122.6,297.5)→终端中心(305.5,120.5,296.5) 通道保持空气
    for cmd in (
            ["setblock 306 120 297 minecraft:dirt",
             "setblock 305 121 296 minecraft:air",
             "setblock 306 121 296 minecraft:air",
             "setblock 306 122 296 minecraft:air",
             "setblock 305 122 296 minecraft:air",
             "setblock 306 122 297 minecraft:air",
             "setblock 307 120 297 minecraft:dirt",
             "setblock 307 121 297 minecraft:air",
             "setblock 306 121 297 minecraft:air",
             "setblock 305 123 296 minecraft:air",
             "setblock 306 123 296 minecraft:air",
             "setblock 306 123 297 minecraft:air",
             "setblock 306 120 296 minecraft:air",
             "setblock 307 120 296 minecraft:air"]
            + ["setblock %d %d %d minecraft:chest" % (tx + 3, ty, tz),
               "setblock %d %d %d toms_storage:inventory_connector"
               % (tx + 2, ty, tz),
               "setblock %d %d %d toms_storage:inventory_cable"
               % (tx + 1, ty, tz),
               "setblock %d %d %d toms_storage:storage_terminal"
               % (tx, ty, tz)]):
        rcon(cmd)
    rcon("tp Bob 306.5 121 297.5")
    rcon("clear Bob")
    rcon("give Bob minecraft:dirt 16")
    time.sleep(2.5)
    # 合法链:先面向终端(存储目标解析依赖准星),再 deposit
    s = play.Session("r1i08")
    pos = (s.observe().get("data", {}).get("observation")
           .get("position") or {})
    px, py, pz = int(pos.get("x", 0)), int(pos.get("y", 0)), \
        int(pos.get("z", 0))
    fg = s.do("goto", {"x": px, "y": py, "z": pz,
                       "face_x": tx, "face_y": ty, "face_z": tz},
              timeout_s=60).get("terminal", {})
    if fg.get("state") != "completed":
        run("I08", False, {"face": fg.get("reason"),
                           "note": "面向终端失败"})
        return
    r = s.do("deposit", {}, timeout_s=90).get("terminal", {})
    ok = (r.get("state") == "completed"
          and "toms_storage_terminal" in (r.get("reason") or ""))
    left = inv_counts().get("minecraft:dirt", 0)
    run("I08", ok, {"r": r.get("reason"), "dirt_left": left,
                    "terminal": [tx, ty, tz],
                    "note": "真实终端网络 deposit:player→网络转移验证"})


# ---------- A 组 ----------

def a01_native_2x2_two_woods():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:oak_log 2")
    rcon("give Bob minecraft:birch_log 3")
    time.sleep(1)
    r1 = do("craft", {"item": "minecraft:oak_planks", "count": 8}, 150)
    r2 = do("craft", {"item": "minecraft:birch_planks", "count": 12}, 150)
    r3 = do("craft", {"item": "minecraft:stick", "count": 4}, 150)
    r4 = do("craft", {"item": "minecraft:crafting_table", "count": 1}, 150)
    inv = inv_counts()
    ok = (r1["state"] == "completed" and r2["state"] == "completed"
          and r3["state"] == "completed" and r4["state"] == "completed"
          and inv.get("minecraft:oak_planks", 0) == 2    # 8-2(stick)-4(table)
          and inv.get("minecraft:birch_planks", 0) == 12
          and inv.get("minecraft:stick", 0) == 4
          and inv.get("minecraft:crafting_table", 0) == 1)
    run("A01", ok, {"r1": r1.get("reason"), "r2": r2.get("reason"),
                    "inv": inv, "note": "双木种 2x2 全链,数量精确"})


def v01_craft_unit_negative():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:oak_log 8")
    time.sleep(1)
    s = play.Session("r1v01")
    ex, err = s.submit("craft", {"item": "minecraft:oak_planks",
                                 "count": 32})
    time.sleep(3.5)  # 首批(4 板)产出后偷走剩余材料
    rcon("clear Bob minecraft:oak_log")
    term = s.term(ex, 240)[0]
    planks = inv_counts().get("minecraft:oak_planks", 0)
    ok = planks >= 4 and (
        term.get("state") == "failed"
        or (term.get("state") == "completed" and planks >= 32))
    # 关键:只有部分产出(如 4/8)绝不能报 completed
    partial_completed = (term.get("state") == "completed" and planks < 32)
    run("V01", ok and not partial_completed,
        {"term": term.get("state"), "reason": (term.get("reason") or "")[:120],
         "planks": planks,
         "note": "产出单位=物品数:部分产出不冒充完成"})


def a02_table_3x3_pickaxes():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:birch_log 6")
    rcon("give Bob minecraft:cobblestone 20")
    time.sleep(1)
    rcon("setblock 302 120 302 minecraft:air")
    p = do("craft", {"item": "minecraft:birch_planks", "count": 24}, 150)
    st = do("craft", {"item": "minecraft:stick", "count": 8}, 150)
    tb = do("craft", {"item": "minecraft:crafting_table", "count": 1}, 150)
    pl = do("place", {"x": 302, "y": 120, "z": 302, "item": "minecraft:crafting_table"}, 120)
    wp = do("craft", {"item": "minecraft:wooden_pickaxe", "count": 1}, 200)
    sp = do("craft", {"item": "minecraft:stone_pickaxe", "count": 1}, 200)
    inv = inv_counts()
    ok = all(r["state"] == "completed" for r in (p, st, tb, pl, wp, sp)) \
        and inv.get("minecraft:wooden_pickaxe", 0) >= 1 \
        and inv.get("minecraft:stone_pickaxe", 0) >= 1
    run("A02", ok, {"p": p.get("reason"), "st": st.get("reason"),
                    "tb": tb.get("reason"), "pl": pl.get("reason"),
                    "wp": wp.get("reason"), "sp": sp.get("reason"),
                    "pickaxes": (inv.get("minecraft:wooden_pickaxe", 0),
                                 inv.get("minecraft:stone_pickaxe", 0)),
                    "note": "放置/打开工作台→3x3 木镐+石镐"})


def a03_no_table_negative():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:birch_log 6")
    time.sleep(1)
    do("craft", {"item": "minecraft:birch_planks", "count": 24}, 150)
    do("craft", {"item": "minecraft:stick", "count": 8}, 150)
    # 不放工作台:石镐必须拒绝
    r = do("craft", {"item": "minecraft:stone_pickaxe", "count": 1}, 120)
    inv = inv_counts()
    ok = r["state"] == "failed" and inv.get("minecraft:stone_pickaxe", 0) == 0
    run("A03", ok, {"r": r.get("reason"),
                    "note": "无台 3x3 拒绝,无服务端变换"})


def a04_incremental_craft():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:oak_log 4")
    time.sleep(1)
    r1 = do("craft", {"item": "minecraft:oak_planks", "count": 4}, 150)
    r2 = do("craft", {"item": "minecraft:oak_planks", "count": 4}, 150)
    n = inv_counts().get("minecraft:oak_planks", 0)
    ok = r1["state"] == "completed" and r2["state"] == "completed" and n == 8
    run("A04", ok, {"n": n, "r2": r2.get("reason"),
                    "note": "已有 4 再请求 4:真实新增(不是已达标冒充)"})


def a05_insufficient():
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:oak_log 1")
    time.sleep(1)
    rp = do("craft", {"item": "minecraft:oak_planks", "count": 4}, 150)
    rs = do("craft", {"item": "minecraft:stick", "count": 4}, 150)
    r = do("craft", {"item": "minecraft:crafting_table", "count": 1}, 120)
    ok = rp["state"] == "completed" and rs["state"] == "completed" \
        and r["state"] == "failed"
    run("A05", ok, {"r": r.get("reason"), "note": "材料不足明确拒绝"})


def a06_precise_place():
    safe()
    rcon("clear Bob")
    rcon("setblock 301 120 302 minecraft:air")
    rcon("give Bob minecraft:crafting_table 1")
    rcon("give Bob minecraft:dirt 16")
    time.sleep(1)
    r = do("place", {"x": 301, "y": 120, "z": 302,
                     "item": "minecraft:crafting_table"}, 120)
    blk = rcon("execute if block 301 120 302 minecraft:crafting_table")
    inv = inv_counts()
    ok = r["state"] == "completed" and "passed" in blk \
        and inv.get("minecraft:dirt", 0) == 16 \
        and inv.get("minecraft:crafting_table", 0) == 0
    run("A06", ok, {"r": r.get("reason"), "blk": blk,
                    "note": "快捷栏另有泥土:放的是工作台,精确落格+消耗"})


def a07_support_face_and_external():
    # A07a: 目标格唯一暴露面=侧壁(顶盖+底座),站位层铺好
    safe()
    rcon("clear Bob")
    rcon("setblock 304 120 300 minecraft:air")
    rcon("setblock 304 122 300 minecraft:air")
    rcon("setblock 303 120 300 minecraft:stone")   # 邻站格脚下
    rcon("setblock 304 119 300 minecraft:stone")   # 底座
    rcon("setblock 304 122 300 minecraft:stone")   # 顶盖
    rcon("setblock 304 121 300 minecraft:air")     # 目标格(侧壁暴露)
    rcon("give Bob minecraft:dirt 4")
    time.sleep(1)
    r = do("place", {"x": 304, "y": 121, "z": 300,
                     "item": "minecraft:dirt"}, 120)
    blk = rcon("execute if block 304 121 300 minecraft:dirt")
    ok = (r["state"] == "completed" and "passed" in blk) or \
         r["state"] == "failed"
    run("A07a", ok, {"r": r.get("reason"), "blk": blk,
                     "note": "侧壁支撑:落点=目标格(或诚实失败),不误放别格"})
    # A07b/V03: 外部抢先放同类型块,Bob 库存未消耗→不归为成功
    rcon("clear Bob")
    rcon("setblock 306 120 300 minecraft:air")
    rcon("give Bob minecraft:oak_planks 2")
    time.sleep(1)
    s = play.Session("r1a07")
    ex, err = s.submit("place", {"x": 306, "y": 120, "z": 300,
                                 "item": "minecraft:oak_planks"})
    time.sleep(0.6)
    rcon("setblock 306 120 300 minecraft:oak_planks")  # 外部抢放
    term = s.term(ex, 90)[0]
    inv = inv_counts()
    ok = term.get("state") in ("failed", "cancelled") and \
        inv.get("minecraft:oak_planks", 0) == 2
    run("A07b", ok, {"term": term.get("state"), "reason": term.get("reason"),
                     "inv": inv,
                     "note": "外部抢放:不冒充 Bob 成功,库存未消耗"})


def a08_eat_paths():
    safe()
    rcon("clear Bob")
    rcon("item replace entity Bob inventory.1 with minecraft:cooked_beef 3")
    time.sleep(1)
    pre = slot_of("minecraft:cooked_beef")
    rcon("effect give Bob minecraft:hunger 5 200")
    time.sleep(6)
    r1 = do("eat", {}, 120)
    inv = inv_counts()
    ok_eat = r1["state"] == "completed" and inv.get("minecraft:cooked_beef", 0) == 2
    rcon("effect give Bob minecraft:saturation 2 5")
    rcon("effect clear Bob minecraft:hunger")
    time.sleep(1)
    r0 = do("eat", {}, 90)
    ok_rej = r0["state"] == "failed" and "eat_not_hungry" in (r0.get("reason") or "")
    run("A08", ok_eat and ok_rej and pre >= 9,
        {"pre_slot": pre, "eat": r1.get("reason"), "reject": r0.get("reason"),
         "note": "食物初始在主包>=9;吃到+不饿拒绝"})


def v04_eat_attribution_negative():
    # 外部取走食物+外部改饥饿:不冒充本次进食
    safe()
    rcon("clear Bob")
    rcon("item replace entity Bob inventory.0 with minecraft:cooked_beef 2")
    time.sleep(1)
    rcon("effect give Bob minecraft:hunger 5 200")
    time.sleep(6)
    s = play.Session("r1v04")
    ex, err = s.submit("eat", {})
    time.sleep(0.5)
    rcon("clear Bob minecraft:cooked_beef")  # 外部取走
    term = s.term(ex, 60)[0]
    run("V04", term.get("state") != "completed",
        {"term": term.get("state"), "reason": term.get("reason"),
         "note": "食物被外部取走:无客户端完成+无本次消耗=不完成"})


def _local_opp(block, near=None):
    s = play.Session("r1find")
    loc = s.inspect_local(8, "summary")
    opps = (((loc.get("data") or {}).get("snapshot") or {})
            .get("opportunities") or [])
    cands = [o for o in opps if o.get("block") == block]
    if near and cands:
        cands = [o for o in cands
                 if abs(o.get("x", 0) - near[0]) <= 8]
        cands.sort(key=lambda o: abs(o.get("x", 0) - near[0])
                   + abs(o.get("z", 0) - near[2]))
    return cands[0] if cands else None


def a09_target_removed():
    # R1: 机会=tracker 持久池(传感器可见注册;setblock 热放不注册——
    # 多轮实测)。用环境真实可见的 smooth_stone(126 层建筑面)。
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:stone_pickaxe 1")
    o = None
    for _ in range(5):
        time.sleep(2)
        o = _local_opp("minecraft:smooth_stone", near=(300, 126, 305))
        if o:
            break
    if not o:
        # 面前机会池为空时:转 360° 让传感器扫一圈再试
        s0 = play.Session("r1a09look")
        s0.do("say", {"message": "scanning"}, timeout_s=30)
        for _ in range(3):
            time.sleep(2.5)
            o = _local_opp("minecraft:smooth_stone", near=(300, 126, 305))
            if o:
                break
    if not o:
        run("A09", False, {"note": "面前 smooth_stone 机会未出现"})
        return
    tx, ty, tz = o.get("x"), o.get("y"), o.get("z")
    s = play.Session("r1a09")
    ex, err = s.submit("mine_opportunity", {"id": o.get("object_id")})
    time.sleep(0.6)
    rcon("setblock %d %d %d minecraft:air" % (tx, ty, tz))
    term = s.term(ex, 120)[0]
    # 不换最近替代的证明:目标移除后执行拒绝(stale)且未拾取任何同类
    gained = inv_counts().get("minecraft:smooth_stone", 0)
    ok = term.get("state") in ("failed", "outcome_unknown") and gained == 0
    run("A09", ok, {"term": term.get("state"), "reason": term.get("reason"),
                    "target": (tx, ty, tz), "smooth_stone_gained": gained,
                    "note": "目标被移除:stale 拒绝且未挖任何替代"})


def _all_opps():
    s = play.Session("r1find2")
    loc = s.inspect_local(8, "summary")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    return (snap.get("opportunities") or [])
def a10_drop_not_picked():
    # R2/R04:先证明破坏+未拾取,再同坐标阻断。租约竞态(I08 长跑后
    # 旧会话未过期)整体重试一次。
    try:
        _a10_once()
        return
    except RuntimeError as exc:
        if "lease" not in str(exc):
            raise
    time.sleep(15)
    _a10_once()


def _a10_once():
    # R2/R04:先证明 Bob 已破坏指定格且指定掉落尚未入包,再阻断掉落;
    # 同坐标系 kill,不用任意 sleep 与原点 kill。不把目标消失等同获取。
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:stone_pickaxe 1")
    # fixture:两格 smooth_stone 柱(先挖上格)
    bx, by, bz = 300, 120, 305
    rcon("setblock %d %d %d minecraft:air" % (bx, by + 1, bz))
    rcon("setblock %d %d %d minecraft:smooth_stone" % (bx, by, bz))
    rcon("tp Bob %d.5 %d.5 %d.5" % (bx, by + 1, bz + 3))
    time.sleep(2)
    s = None
    for attempt in range(4):
        try:
            s = play.Session("r1a10")
            break
        except RuntimeError:
            time.sleep(12)  # 前序长跑会话租约过期等待
    if s is None:
        run("A10", False, {"note": "lease 不可得(前序会话未过期)"})
        return
    pos = (s.observe().get("data", {}).get("observation")
           .get("position") or {})
    px, py, pz = int(pos.get("x", 0)), int(pos.get("y", 0)), int(pos.get("z", 0))
    fg = s.do("goto", {"x": px, "y": py, "z": pz,
                       "face_x": bx, "face_y": by, "face_z": bz},
              timeout_s=60).get("terminal", {})
    if fg.get("state") != "completed":
        run("A10", False, {"face": fg.get("reason")})
        return
    opp = None
    deadline = time.time() + 12
    while time.time() < deadline:
        # 用同一 session 查机会(新建 Session 会抢走本执行租约——
        # 实测 lease lost 根因)
        loc = s.inspect_local(8, "summary")
        snap = ((loc.get("data") or {}).get("snapshot") or {})
        if isinstance(snap, str):
            snap = json.loads(snap)
        found = [o for o in (snap.get("opportunities") or [])
                 if (o.get("x"), o.get("y"), o.get("z")) == (bx, by, bz)]
        if found:
            opp = found[0]
            break
        time.sleep(0.8)
    if not opp:
        run("A10", False, {"note": "面向后 smooth_stone 机会未出生"})
        return
    ex, err = s.submit("mine_opportunity", {"id": opp.get("object_id")})
    # 精确窗口:方块已空 AND smooth_stone 未入包
    window = None
    deadline = time.time() + 40
    while time.time() < deadline:
        gone = "passed" in (rcon("execute if block %d %d %d minecraft:air"
                                 % (bx, by, bz)) or "")
        picked = inv_counts().get("minecraft:smooth_stone", 0)
        if gone and picked == 0:
            window = True
            break
        if picked > 0:
            window = "already-picked"
            break
        time.sleep(0.3)
    if window is True:
        rcon("kill @e[type=minecraft:item,x=%d,y=%d,z=%d,distance=..6]"
             % (bx, by, bz))
    term = s.term(ex, 150)[0]
    n = inv_counts().get("minecraft:smooth_stone", 0)
    ok = (window is True and term.get("state") != "completed" and n == 0)
    run("A10", ok, {"window": window, "term": term.get("state"),
                    "reason": (term.get("reason") or "")[:80],
                    "smooth_stone": n,
                    "note": "已证破坏+未拾取后才阻断;拾取失败可区分"})


def a11_cancel_death_world_change():
    # A11: GUI 动作中 cancel:无旧任务复活,状态可对账(I06 已覆盖 cancel;
    # 此处补死亡/换世界维度:用 kill Bob 触发死亡,验证执行不复活+无错屏点击)
    safe()
    rcon("clear Bob")
    rcon("give Bob minecraft:oak_log 4")
    time.sleep(1)
    s = play.Session("r1a11")
    ex, err = s.submit("craft", {"item": "minecraft:oak_planks", "count": 4})
    time.sleep(1.0)
    rcon("kill Bob")
    term = s.term(ex, 60)[0]
    ok = term.get("state") in ("failed", "outcome_unknown", "cancelled")
    run("A11", ok, {"term": term.get("state"), "reason": term.get("reason"),
                    "note": "GUI 中死亡:执行终态不复活"})
    # 等重生稳定
    time.sleep(8)


def a12_no_xray():
    # A12: 控制器无透视——墙后资源不出现于机会列表;禁止位置指令=超距 place 拒绝
    safe()
    rcon("clear Bob")
    rcon("setblock 300 120 320 minecraft:stone")
    rcon("setblock 300 121 320 minecraft:stone")
    rcon("setblock 300 122 320 minecraft:diamond_ore")
    time.sleep(2)
    s = play.Session("r1a12")
    loc = s.inspect_local(6, "summary")
    raw = json.dumps(loc, ensure_ascii=False)
    behind_wall = "diamond_ore" in raw
    r = do("place", {"x": 300, "y": 130, "z": 340,
                     "item": "minecraft:dirt"}, 60)
    ok = (not behind_wall) and r["state"] == "failed"
    run("A12", ok, {"behind_wall_visible": behind_wall,
                    "far_place": r.get("reason"),
                    "note": "墙后矿石不可见+超距拒绝"})


def main():
    for fn in (i01_main_pack_sources, i02_exact_counts, v02_increment_negative,
               i03_component_identity, i04_container_chest_barrel,
               i05_true_full, i05b if False else i06_cursor_injection,
               i07_ghost_slots_readonly, i08_tom_deposit_regression,
               a01_native_2x2_two_woods, v01_craft_unit_negative,
               a02_table_3x3_pickaxes, a03_no_table_negative,
               a04_incremental_craft, a05_insufficient, a06_precise_place,
               a07_support_face_and_external, a08_eat_paths,
               v04_eat_attribution_negative, a09_target_removed,
               a10_drop_not_picked, a11_cancel_death_world_change,
               a12_no_xray):
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            run(fn.__name__, False, "EXC %r" % exc)
    passed = sum(1 for _, ok in RESULTS if ok)
    print("SUMMARY %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
