# -*- coding: utf-8 -*-
"""LIVE-R12-1 阶段A: 启动→bot 遣远→放矿→before-A 快照→tp 回观察铸 A→plan+run-next→优雅停服。"""
import importlib.util, json, shutil, sys, time, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)

sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

ORE = (562, 68, 127)
STAND = (561, 68, 127)
FAR = (532, 68, 127)
PLAN = "r12-iron-1"

# 1. 启动(R1.2 代码; 启动 reconcile 会收养遗留 active 化身)
proc = rc.start_server(r"D:\code\mc-experiment\r12-live1-server-a.log")
print("[1] server ready")

# 2. bot 遣远(30格外, 观察半径外)再布置装置, 保证快照时 bot 未观察装置格
print(rc.rcon(f"tp Bob {FAR[0]} {FAR[1]} {FAR[2]}"))
time.sleep(1)
print(rc.rcon(f"setblock {STAND[0]} {STAND[1]} {STAND[2]} air"))
print(rc.rcon(f"setblock {STAND[0]} {STAND[1]-1} {STAND[2]} stone"))
print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
print(rc.rcon("give Bob minecraft:iron_pickaxe"))
time.sleep(2)

# 3. before-A 快照(bot 未观察装置格, 快照必不含装置格机会)
rc.wait_quiet(rc.SEM)
shutil.copyfile(rc.SEM, r"D:\code\mc-experiment\r12-semantic-before-A.json")
before = json.load(open(r"D:\code\mc-experiment\r12-semantic-before-A.json", encoding="utf-8"))
hits = [o for o in before.get("resource_opportunities", [])
        if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert not hits, f"装置格在 before-A 快照中已有机会: {hits}"
print(f"[3] before-A 快照干净({len(before.get('resource_opportunities', []))} 个活动机会, 无装置格)")

# 4. tp 到矿旁观察 → 化身 A 诞生
print(rc.rcon(f"tp Bob {STAND[0]} {STAND[1]} {STAND[2]}"))
lease = gl.get_lease()
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
reg = json.load(open(rc.SEM, encoding="utf-8"))
cand = [o for o in reg["resource_opportunities"] if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert len(cand) == 1, f"矿格机会数 {len(cand)} != 1: {cand}"
A = cand[0]["id"]
print(f"[4] 化身 A 诞生: {A} status={cand[0]['status']}")
assert A.startswith("ore_") and A[4:].count("_") >= 1, f"A 非化身格式: {A}"

# 5. 30 格外派发: plan + run-next(bot 走路中, 未到矿)
print(rc.rcon(f"tp Bob {FAR[0]} {FAR[1]} {FAR[2]}"))
time.sleep(1)
WORLD = open(rc.SEM.replace("external-semantics-bob.json", "world-id")).read().strip()
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{A}"
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={urllib.parse.quote(REF, safe='')}", lease)
gid = r["data"]["graph_id"]
print(f"[5] planned {gid} state={r['data']['state']}")
for _ in range(10):
    ins = gl.call("GET", f"/v1/graphs/{gid}", lease)["data"]
    if ins.get("state") in ("READY", "PLANNED"):
        break
    time.sleep(1)
r2 = gl.call("POST", f"/v1/graphs/{gid}/run-next", lease, None,
             {"X-Request-Id": f"r12l1-runnext-{int(time.time()*1000)}"})
print(f"[5] run-next → {r2['data']['graph']['state']}")
time.sleep(3)

# 6. 优雅停服(crash 窗口: birth+journal+graph 已 fsync; 语义快照稍后被回滚)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live1-server-a.log")
print("[6] stopped")

rc.save_state(opp_A=A, graph_id=gid, cell=list(ORE), world=WORLD, plan=PLAN)
print("STATE:", json.dumps({"A": A, "graph": gid}))
