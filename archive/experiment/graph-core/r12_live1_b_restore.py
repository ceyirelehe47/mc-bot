# -*- coding: utf-8 -*-
"""LIVE-R12-1 阶段B: journal 验 birth(A)→语义回滚 before-A→重启→断言同 id 恢复(先于端点使用)→再观察不铸 B。"""
import importlib.util, json, shutil, subprocess, sys, time

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)
sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

st = rc.load_state()
A, gid, ORE, STAND = st["opp_A"], st["graph_id"], tuple(st["cell"]), (561, 68, 127)

# 1. 停服态验证 journal: birth(A) 收据存在(durable)
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\read_lifecycle_receipts.py", rc.JOURNAL],
                     capture_output=True, text=True).stdout
births = [json.loads(l) for l in out.splitlines()
          if json.loads(l)["fields"].get("kind") == "resource_opportunity_birth"
          and json.loads(l)["fields"].get("opportunity_id") == A]
assert births, f"journal 无 birth({A})"
print(f"[1] birth(A) durable: seq={births[0]['seq']} x={births[0]['fields']['x']},{births[0]['fields']['y']},{births[0]['fields']['z']} block={births[0]['fields']['block_id']}")
open(r"D:\code\mc-experiment\r12-journal-birth-A.txt", "w").write(out)

# 2. 语义回滚: before-A 快照(不含 A)覆盖; journal+graph 保留 → 精确 crash 窗口
shutil.copyfile(r"D:\code\mc-experiment\r12-semantic-before-A.json", rc.SEM)
rollback = json.load(open(rc.SEM, encoding="utf-8"))
assert not any(o.get("id") == A for o in rollback.get("resource_opportunities", [])), "回滚失败: A 仍在语义文件"
print(f"[2] 语义已回滚到 before-A(A 不在), journal/task-graphs 保留")

# 3. 重启真实运行时
proc = rc.start_server(r"D:\code\mc-experiment\r12-live1-server-b.log")
print("[3] restarted")

# 4. 端点一可用即断言: 注册表含 A(非新铸 B)
lease = gl.get_lease()
obs = gl.call("GET", "/v1/observe", lease)["data"]
ops = obs.get("resource_opportunities", [])
ids_at_cell = [o["id"] for o in ops if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert ids_at_cell == [A], f"端点暴露时装置格不是精确 A: {ids_at_cell}"
all_new = [o["id"] for o in ops if o["id"] != A and o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert not all_new, f"铸出新化身: {all_new}"
print(f"[4] HTTP 可用首查: 装置格恢复为精确 A={A} (lifecycle reconcile 先于端点)")

# 5. Graph 存活且引用 A
ins = gl.call("GET", f"/v1/graphs/{gid}", lease)["data"]
subj = ins.get("subject") or ins.get("nodes", [{}])[0].get("object_id") or ins.get("object_id")
assert A in json.dumps(ins), f"graph 不再引用 A"
assert ins.get("state") in ("SUSPENDED", "PLANNED", "READY", "RUNNING"), f"graph 状态异常: {ins.get('state')}"
print(f"[5] graph {gid[:20]}… state={ins.get('state')} 仍引用 A")

# 6. 再观察同一物理矿: id 仍为 A(不铸 B)
print(rc.rcon(f"tp Bob {STAND[0]} {STAND[1]} {STAND[2]}"))
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
obs2 = gl.call("GET", "/v1/observe", lease)["data"]
ids2 = [o["id"] for o in obs2.get("resource_opportunities", []) if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert ids2 == [A], f"再观察后化身变化: {ids2}"
print(f"[6] 再观察: id 仍为 A, 无 B")

rc.save_state(live1_b_passed=True)
proc_info = "server-left-running"
print("PASS LIVE-R12-1 part B;", proc_info)
json.dump({"A": A, "graph": gid, "state": ins.get("state")},
          open(r"D:\code\mc-experiment\r12-live1-b-result.json", "w"))
