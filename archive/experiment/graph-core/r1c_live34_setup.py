# -*- coding: utf-8 -*-
"""LIVE-R1-3 + R1-4 停服前布置: 新矿注册 + plan(r1c-iron-3b) + run-next + 优雅停服。"""
import importlib.util, json, time, subprocess, sys, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

RCON = r"D:\code\mc-experiment\rcon.py"
def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()

WORLD = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"
ORE = (560, 68, 129)
PLAN = "r1c-iron-3b"

print(rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
print(rcon("tp Bob 558 68 129"))
lease = gl.get_lease()
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
print(rcon("tp Bob 550 68 129"))
time.sleep(1)

reg = json.load(open(r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-semantics-bob.json", encoding="utf-8"))
cand = [o for o in reg["resource_opportunities"] if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
if not cand:
    print("注册失败"); sys.exit(1)
OPP = cand[-1]["id"]
print("OPP=" + OPP)

REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{OPP}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph_id = r["data"]["graph_id"]
print("planned:", graph_id, r["data"]["state"])
r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r1c-3b-{int(time.time()*1000)}"})
print("run-next:", r2["data"]["graph"]["state"])
time.sleep(4)  # bot 在路上, 但还没到
print("停服:", rcon("stop") or "(已发出)")
time.sleep(8)
print(json.dumps({"graph_id": graph_id, "opp": OPP}))
