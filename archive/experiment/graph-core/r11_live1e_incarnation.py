# -*- coding: utf-8 -*-
"""LIVE-R11-1 part A5(最终装置): 矿放回 A 的原始格(560,68,129)——同层邻格站面完整,
工位扫描可解; 30 格外派发, 2 秒停服 -> 干净 RUNNING->SUSPENDED。

教训链总结: (1)无镐 -> 机会 BLOCKED, 派发即拒; (2)悬空齐胸高矿没有同层站面,
adjacentStandPos 必 null -> no_reachable_work_pose; (3)矿进观察半径后任务立即做工位扫描。
"""
import importlib.util, json, time, subprocess, sys, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

RCON = r"D:\code\mc-experiment\rcon.py"
def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()

WORLD = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"
SEM = r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-semantics-bob.json"
OLD = (560, 69, 129)   # 悬空矿 C(终结掉)
ORE = (560, 68, 129)   # A 的原始格, 同层站面 (559,68,129) 地面完整
PLAN = "r11-incarnation-f"
A_ID = "ore_3467bfcc851f_8ef944cd84a44b77"

def opp_at(pos):
    reg = json.load(open(SEM, encoding="utf-8"))
    return [o for o in reg.get("resource_opportunities", [])
            if (o.get("x"), o.get("y"), o.get("z")) == pos]

lease = gl.get_lease()

# 0. 终结悬空矿 C(石头替换 -> 观察 -> 移除), 同格放回铁矿(新化身 D2)
print("tp:", rcon("tp Bob 558 68 129"))
print("old_stone:", rcon(f"setblock {OLD[0]} {OLD[1]} {OLD[2]} stone"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(1)
assert opp_at(OLD) == [], f"C not terminalized: {opp_at(OLD)}"
print("C_terminalized: OK")
print("old_clear:", rcon(f"setblock {OLD[0]} {OLD[1]} {OLD[2]} air"))
print("place_iron:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(1)
ents = opp_at(ORE)
assert len(ents) == 1, f"expect one incarnation at A's cell, got {ents}"
D2 = ents[0]["id"]
print("incarnation_final=" + D2)
assert D2 != A_ID, "SAME ID AS A -- FAIL"
print("FINAL != A: OK; status=", ents[0].get("status"))
assert ents[0].get("status") == "ACTIONABLE", ents[0]

# 1. 30 格外派发, 2 秒停服(远未进观察半径, 任务稳定在走路分支)
print("tp_far:", rcon("tp Bob 530 68 129"))
time.sleep(1)
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{D2}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph = r["data"]; graph_id = graph["graph_id"]
print("planned:", graph_id, "state=", graph.get("state"))
assert graph.get("state") == "READY", graph

r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r11-l1e-{int(time.time()*1000)}"})
print("run-next accepted:", r2["data"]["graph"]["state"])
time.sleep(2)
print("stop:", rcon("stop") or "(sent)")
time.sleep(10)
json.dump({"graph_id": graph_id, "A": A_ID, "B": D2, "cell": list(ORE)},
          open(r"D:\code\mc-experiment\r11_live1_state.json", "w", encoding="utf-8"))
print(json.dumps({"graph_id": graph_id, "final_incarnation": D2}, indent=2))
