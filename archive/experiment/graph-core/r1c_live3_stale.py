# -*- coding: utf-8 -*-
"""LIVE-R1-3: 派发后外部移除矿石 -> durable stale receipt -> 图 STALE, 绝不 DONE。"""
import importlib.util, json, time, subprocess, sys

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

RCON = r"D:\code\mc-experiment\rcon.py"
def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()

WORLD = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"
ORE_POS = (564, 68, 129)   # 距 Bob(550,68,129) 14 格, 站立位干净
PLAN = "r1c-iron-3"

print(rcon(f"setblock {ORE_POS[0]} {ORE_POS[1]} {ORE_POS[2]} iron_ore"))
print(rcon("tp Bob 562 68 129"))          # 把 Bob 带到矿旁完成感知注册
lease = gl.get_lease()
gl.call("GET", "/v1/observe", lease)       # 显式观察触发注册
time.sleep(1)

# 从注册表确认新机会 id
import pathlib
reg = json.load(open(r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-semantics-bob.json", encoding="utf-8"))
cand = [o for o in reg["resource_opportunities"]
        if o.get("x") == ORE_POS[0] and o.get("y") == ORE_POS[1] and o.get("z") == ORE_POS[2]]
if not cand:
    print("未注册:", [ (o.get('x'),o.get('z')) for o in reg['resource_opportunities']][-5:]); sys.exit(1)
OPP = cand[-1]["id"]
print("opportunity:", OPP)
print(rcon("tp Bob 550 68 129"))          # 送回出发点, 制造走动窗口

REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{OPP}"
import urllib.parse
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph_id = r["data"]["graph_id"]
print("planned:", graph_id, r["data"]["state"])

r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r1c-stale-{int(time.time()*1000)}"})
print("run-next:", r2["data"]["graph"]["state"])

# 立刻外部移除矿石(在 bot 到达前)
time.sleep(2)
print("拆矿:", rcon(f"setblock {ORE_POS[0]} {ORE_POS[1]} {ORE_POS[2]} air"))

deadline = time.time() + 180
ins = {}
while time.time() < deadline:
    ins = gl.call("GET", f"/v1/graphs/{graph_id}")["data"]
    st = ins.get("state")
    if st in ("DONE", "STALE", "FAILED", "CANCELLED"):
        break
    time.sleep(3)
nodes = ins.get("nodes", [])
print("终态:", st, "节点reason:", [n.get("reason") for n in nodes])
print("GRAPH_ID=" + graph_id, "OPP=" + OPP)
assert st == "STALE", f"期望 STALE, 实际 {st}"
print("LIVE-R1-3 PASS: STALE, 绝非 DONE")
