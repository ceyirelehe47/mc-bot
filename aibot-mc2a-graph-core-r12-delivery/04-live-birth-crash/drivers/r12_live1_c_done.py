# -*- coding: utf-8 -*-
"""LIVE-R12-1 阶段C: 启动→再观察装置格(id 仍 A, 不铸 B)→Graph 存活引用 A(SUSPENDED)→
普通派发 run-next→submit→挖矿拾取→DONE→停服→journal: birth(A)<consumed(A)。"""
import importlib.util, json, subprocess, sys, time

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)
sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

st = rc.load_state()
A, gid = st["opp_A"], st["graph_id"]

# 1. 启动 + 再观察同一物理矿: id 仍为 A
proc = rc.start_server(r"D:\code\mc-experiment\r12-live1-server-c.log")
lease = gl.get_lease()
print(rc.rcon("tp Bob 561 68 127"))
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
after = json.load(open(rc.SEM, encoding="utf-8"))
ids = [o["id"] for o in after.get("resource_opportunities", [])
       if o.get("x") == 562 and o.get("y") == 68 and o.get("z") == 127]
assert ids == [A], f"再观察后装置格化身变化: {ids}"
print(f"[1] 再观察: id 仍为 A={A}, 未铸 B")

# 2. Graph 存活且引用 A(重启后 RUNNING→SUSPENDED)
ins = gl.call("GET", f"/v1/graphs/{gid}", lease)["data"]
assert A in json.dumps(ins), "graph 不再引用 A"
print(f"[2] graph state={ins.get('state')} 引用 A; subject/nodes object_id 含 A: {A in json.dumps(ins)}")
assert ins.get("state") == "SUSPENDED", f"期望重启后 SUSPENDED, 实得 {ins.get('state')}"

# 3. 普通派发: 30 格外 → run-next → bot 走路 → 挖 → 拾取 → DONE
print(rc.rcon("tp Bob 532 68 127"))
time.sleep(1)
r = gl.call("POST", f"/v1/graphs/{gid}/run-next", lease, None,
            {"X-Request-Id": f"r12l1c-{int(time.time()*1000)}"})
print("[3] run-next →", r["data"]["graph"]["state"])
deadline = time.time() + 240
while time.time() < deadline:
    ins = gl.call("GET", f"/v1/graphs/{gid}", lease)["data"]
    if ins.get("state") in ("DONE", "STALE", "FAILED", "CANCELLED"):
        break
    time.sleep(3)
print("[3] 终态:", ins.get("state"))
assert ins.get("state") == "DONE", f"期望 DONE, 实得 {ins.get('state')}"

# 4. 停服 → journal: birth(A) 与 consumed(A) 收据序列
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live1-server-c.log")
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\read_lifecycle_receipts.py", rc.JOURNAL],
                     capture_output=True, text=True).stdout
open(r"D:\code\mc-experiment\r12-journal-final.txt", "w").write(out)
rows = [json.loads(l) for l in out.splitlines()]
births = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_birth" and r["fields"].get("opportunity_id") == A]
consumed = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_consumed" and r["fields"].get("opportunity_id") == A]
assert births and consumed, f"收据链不完整 birth={len(births)} consumed={len(consumed)}"
assert births[0]["seq"] < consumed[0]["seq"]
print(f"[4] journal: birth(A) seq={births[0]['seq']} < consumed(A) seq={consumed[0]['seq']}")
rc.save_state(live1_done=True)
print("PASS LIVE-R12-1")
