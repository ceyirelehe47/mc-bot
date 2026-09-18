# -*- coding: utf-8
"""LIVE-R12-1 阶段B'(干净版): 语义回滚 before-A → 纯启动 → 即停(无任何观察/驱动) →
断言 reconcile 已把精确同 id A 恢复进持久化语义文件。这是"恢复先于端点使用"的最强形式:
启动后没有任何 bot 观察/HTTP 查询参与, 注册表里的 A 只能来自 journal 重放。"""
import json, shutil, sys, time

sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

st = rc.load_state()
A = st["opp_A"]
ORE = tuple(st["cell"])

# 0. 服务器必须已停(由调用方保证)
before = json.load(open(r"D:\code\mc-experiment\r12-semantic-before-A.json", encoding="utf-8"))
assert not any(o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]
               for o in before.get("resource_opportunities", [])), "before-A 不干净"

# 1. 回滚语义(重放 crash 窗口: durable birth + durable graph + 陈旧语义快照)
shutil.copyfile(r"D:\code\mc-experiment\r12-semantic-before-A.json", rc.SEM)
cur = json.load(open(rc.SEM, encoding="utf-8"))
assert not any(o.get("id") == A for o in cur.get("resource_opportunities", []))
print(f"[1] 语义已回滚({len(cur.get('resource_opportunities', []))} 机会, 无 A)")

# 2. 纯启动 → 即停
proc = rc.start_server(r"D:\code\mc-experiment\r12-live1-server-b2.log")
print("[2] started (bridge ready = HTTP 已可暴露; 此前未做任何查询)")
time.sleep(2)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live1-server-b2.log")
print("[2] stopped (未做任何 observe/tp/graph 调用)")

# 3. 断言: 持久化语义文件含精确 A
after = json.load(open(rc.SEM, encoding="utf-8"))
cell = [o for o in after.get("resource_opportunities", [])
        if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert [o["id"] for o in cell] == [A], f"恢复结果不是精确 A: {cell}"
newborn = [o["id"] for o in after.get("resource_opportunities", [])
           if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2] and o["id"] != A]
assert not newborn, f"铸出新化身: {newborn}"
print(f"[3] PASS: 纯启动后持久化注册表恢复精确同 id A={A}, 无 B")
rc.save_state(live1_b2_passed=True)
