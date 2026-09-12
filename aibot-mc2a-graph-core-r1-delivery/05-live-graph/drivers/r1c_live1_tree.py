# -*- coding: utf-8 -*-
"""LIVE-R1-1: 真实敌对 Safety 位移 + 存活支撑栈 -> 重入 -> 完整收割 + 零脚手架。"""
import importlib.util, json, re, subprocess, sys, threading, time

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

LOG = r"D:\code\mc-experiment\mc-server-mc1ca\server-r1c-o.log"
RCON = r"D:\code\mc-experiment\rcon.py"

def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()

def log_text():
    return open(LOG, encoding="utf-8", errors="replace").read()

TX, TZ = 530, 121          # 树位
STAND = (528, 121)         # 观察证台(Bob acquire 用)

# --- 场景 ---
rcon("tp Bob 524.5 67.5 121.5")
rcon("effect give Bob minecraft:instant_health 1 5 true")
rcon("effect give Bob minecraft:saturation 1 20 true")
rcon("clear Bob")
rcon("kill @e[type=minecraft:item]")
rcon("kill @e[type=minecraft:zombie]")
rcon("give Bob minecraft:iron_axe 1")
rcon("give Bob minecraft:dirt 16")
rcon("gamerule doDaylightCycle false")
rcon("time set day")
# 整平 13x13 平台(地面 y=66 实心, 67 起清空), 保证重入相邻基地必然存在
rcon("fill 524 66 115 536 66 127 stone")
rcon("fill 524 67 115 536 79 127 air")
# 造树: 泥土(530,66) + 8 根原木 y=67..74 + 顶部一圈树叶 y=75
rcon(f"setblock {TX} 66 {TZ} dirt")
for y in range(67, 75):
    rcon(f"setblock {TX} {y} {TZ} oak_log")
for dx, dz in ((0,-1),(0,1),(-1,0),(1,0),(0,0)):
    rcon(f"setblock {TX+dx} 75 {TZ+dz} oak_leaves")
# 4 格观察证台(眼位看清 8 根)
for y in (67, 68, 69, 70):
    rcon(f"setblock {STAND[0]} {y} {STAND[1]} dirt")
rcon(f"tp Bob {STAND[0]+0.5} 71 {STAND[1]+0.5}")

out = {}
def run_gather():
    r = subprocess.run([sys.executable, r"D:\code\mc-experiment\mc2a0_live.py", "exec",
                        "gather", '{"item":"minecraft:oak_log","count":1}', "420"],
                       capture_output=True, text=True, timeout=480, cwd="D:/code/mc-experiment")
    out["text"] = (r.stdout or "") + (r.stderr or "")

# --- 提交 gather, 等 workset(8 logs) ---
mark = len(log_text())
threading.Thread(target=run_gather).start()
deadline = time.time() + 90
while time.time() < deadline:
    text = log_text()[mark:]
    m = re.search(r'tree_workset_acquired.*?logs=(\d+)', text)
    if m:
        print("acquired logs=" + m.group(1))
        # GameTest 同款: 拆高位证台并把 bot 送回地面, 否则 4 格落差令寻路拒绝全部基地
        for y in (67, 68, 69, 70):
            rcon(f"setblock {STAND[0]} {y} {STAND[1]} air")
        rcon("tp Bob 526.5 67 121.5")
        break
    if "task_failed" in text:
        print("EARLY FAIL:", re.findall(r'task_failed.*', text)[-1]); sys.exit(1)
    time.sleep(0.5)
else:
    print("NO ACQUIRE"); sys.exit(1)

# --- 等两根支撑(存活栈) -> 召僵尸 ---
deadline = time.time() + 180
while time.time() < deadline:
    text = log_text()[mark:]
    placed = len(re.findall(r'tree_support_placed', text))
    if placed >= 2:
        time.sleep(1.5)
        break
    if "task_failed" in text or "tree_cleanup_debt" in text:
        print("FAIL before displacement:", re.findall(r'task_failed.*|.*tree_cleanup_debt.*', text)[-1]); sys.exit(1)
    time.sleep(0.4)
else:
    print("NO 2 SUPPORTS"); sys.exit(1)
print("supports placed:", placed, "(低位位移窗口)")
# 召僵尸贴脸(bot 低位, 近战可及)
bobpos = re.findall(r'tree_support_placed.*?pos=(\d+), (-?\d+), (\d+)', log_text()[mark:])
sx, sy, sz = bobpos[-1]
rcon(f"summon minecraft:zombie {int(sx)+1}.5 {int(sy)+1}.5 {int(sz)+0.5}")
print(f"zombie summoned near ({sx},{sy},{sz})")

# --- 等 gather 终态(重入一发生即清僵尸: 它已完成真实位移使命) ---
zombie_cleared = False
threat_seen_at = None
deadline = time.time() + 420
while time.time() < deadline:
    text = log_text()[mark:]
    if not zombie_cleared:
        if threat_seen_at is None and "task_paused" in text and "threat: HOSTILE" in text:
            threat_seen_at = time.time()
        displaced_now = ("path_drop_down" in text and threat_seen_at) or "tree_support_reentered" in text
        timeout_no_reach = threat_seen_at and time.time() - threat_seen_at > 6
        if displaced_now or timeout_no_reach:
            rcon("kill @e[type=minecraft:zombie]")
            zombie_cleared = True
            print("位移证据" + ("出现" if displaced_now else "超时(够不着)"), ", 立即清除僵尸")
    if "tree_workset_complete" in text:
        rcon("kill @e[type=minecraft:zombie]")
        print("WORKSET COMPLETE")
        break
    if re.search(r'task_failed', text):
        print("task_failed:", re.findall(r'task_failed.*', text)[-1]); break
    time.sleep(1)
time.sleep(3)

# --- 证据收集 ---
text = log_text()[mark:]
ev = {
 "acquired": re.findall(r'tree_workset_acquired.*', text),
 "placed": len(re.findall(r'tree_support_placed', text)),
 "removed": len(re.findall(r'tree_support_removed', text)),
 "reentered": re.findall(r'tree_support_reentered.*', text),
 "paused": re.findall(r'task_paused.*', text),
 "resumed": re.findall(r'task_resumed.*', text),
 "debt": re.findall(r'tree_cleanup_debt[^\s]*', text),
 "target_changed": re.findall(r'tree_access_target_changed_with_owned_supports', text),
 "pose_lost": re.findall(r'tree_access_pose_lost_with_owned_supports', text),
 "threat": re.findall(r'threat_detected.*decision=combat.*', text),
 "complete": re.findall(r'tree_workset_complete.*', text),
 "failed": re.findall(r'task_failed.*', text),
}
print(json.dumps(ev, ensure_ascii=False, indent=1))
# 零脚手架: 树列与邻列 67..78 全空
leftover = []
for y in range(66, 78):
    for xz in ((TX, TZ), (TX-1, TZ), (TX+1, TZ), (TX, TZ-1), (TX, TZ+1), STAND):
        q = rcon(f"execute if block {xz[0]} {y} {xz[1]} oak_log run seed")
        q2 = rcon(f"execute if block {xz[0]} {y} {xz[1]} dirt run seed")
        time.sleep(0.3)
        if "Seed" in q: leftover.append(("oak_log", xz[0], y, xz[1]))
        if "Seed" in q2: leftover.append(("dirt", xz[0], y, xz[1]))
print("残留方块:", leftover)
open(r"D:\code\mc-experiment\r1c_live1_result.json", "w", encoding="utf-8").write(
    json.dumps(ev, ensure_ascii=False, indent=1))
