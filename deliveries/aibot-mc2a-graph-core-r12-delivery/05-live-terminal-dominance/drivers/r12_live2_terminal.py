# -*- coding: utf-8 -*-
"""LIVE-R12-2: 机会 C 诞生→快照含 C→真 stale 终态(stone 替换经正常观察路径)→停服→注入含 C 旧快照
→重启(纯启动即停)→断言 C 不被旧 birth 复活; journal 证 stale(C) durable。"""
import importlib.util, json, shutil, subprocess, sys, time

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)
sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

ORE = (562, 68, 127)
STAND = (561, 68, 127)

# 1. 启动 → 布置矿(bot 遣远) → 观察铸 C
proc = rc.start_server(r"D:\code\mc-experiment\r12-live2-server-a.log")
lease = gl.get_lease()
print(rc.rcon("tp Bob 532 68 127")); time.sleep(1)
print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore")); time.sleep(2)

# 2. 快照含 C(观察前)与观察后(含 C)
rc.wait_quiet(rc.SEM)
shutil.copyfile(rc.SEM, r"D:\code\mc-experiment\r12-semantic-with-C.json")
print(rc.rcon(f"tp Bob {STAND[0]} {STAND[1]} {STAND[2]}"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(2)
reg = json.load(open(rc.SEM, encoding="utf-8"))
cand = [o for o in reg["resource_opportunities"] if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert len(cand) == 1, f"矿格机会 {len(cand)} != 1"
C = cand[0]["id"]
print(f"[1] 化身 C 诞生: {C}")
rc.wait_quiet(rc.SEM)
shutil.copyfile(rc.SEM, r"D:\code\mc-experiment\r12-semantic-with-C-observed.json")

# 3. 真 stale 终态: stone 替换矿格(bot 同格观察, 经正常 observeVisibleBlock 路径)
print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} minecraft:stone")); time.sleep(3)
gl.call("GET", "/v1/observe", lease); time.sleep(2)
reg2 = json.load(open(rc.SEM, encoding="utf-8"))
left = [o["id"] for o in reg2["resource_opportunities"] if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert not left, f"stale 终态后 C 仍在注册表: {left}"
print(f"[2] 真 stale 终态: C 已出表(R1.2 语义: stale 收据 durable 先于移除)")

# 4. 停服 → 验 journal stale(C) → 注入含 C 旧快照(观察后版本, 即含 C 的"陈旧"快照)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live2-server-a.log")
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\read_lifecycle_receipts.py", rc.JOURNAL],
                     capture_output=True, text=True).stdout
rows = [json.loads(l) for l in out.splitlines()]
stales = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_stale" and r["fields"].get("opportunity_id") == C]
births = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_birth" and r["fields"].get("opportunity_id") == C]
assert births and stales, f"C 收据链不完整 birth={len(births)} stale={len(stales)}"
assert births[0]["seq"] < stales[0]["seq"]
open(r"D:\code\mc-experiment\r12-journal-C.txt", "w").write(out)
print(f"[3] journal: birth(C) seq={births[0]['seq']} < stale(C) seq={stales[0]['seq']} (durable)")

shutil.copyfile(r"D:\code\mc-experiment\r12-semantic-with-C-observed.json", rc.SEM)
inj = json.load(open(rc.SEM, encoding="utf-8"))
assert any(o.get("id") == C for o in inj.get("resource_opportunities", [])), "注入失败"
print(f"[4] 已注入含 C 的旧语义快照(出生后观察版)")

# 5. 重启(纯启动即停, 无任何查询) → 断言 C 不复活
proc = rc.start_server(r"D:\code\mc-experiment\r12-live2-server-b.log")
time.sleep(2)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live2-server-b.log")
after = json.load(open(rc.SEM, encoding="utf-8"))
resurrected = [o["id"] for o in after.get("resource_opportunities", []) if o.get("id") == C]
cell = [o["id"] for o in after.get("resource_opportunities", []) if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert not resurrected, f"C 被旧 birth 复活: {resurrected}"
assert not cell, f"装置格被复活: {cell}"
print("[5] PASS: 重启后 C 不复活(terminal 收据主导旧语义快照, 装置格为空)")
rc.save_state(live2_passed=True, opp_C=C)
