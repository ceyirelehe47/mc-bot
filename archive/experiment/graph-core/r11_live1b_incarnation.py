# -*- coding: utf-8 -*-
"""LIVE-R11-1 重做(part A2): 悬空齐胸高矿 + 更短停服间隔, 保证图停在 RUNNING->SUSPENDED。

前次教训: (560,68,129) 是地面脚齐高几何, 机器人到达即 no_reachable_work_pose 类型化失败
-> FAILED, 污染 SUSPENDED 断言。本次矿放到 (560,69,129) 悬空齐胸高(邻格可站可平视)。
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
OLD_CELL = (560, 68, 129)   # 上一轮 B 所在(脚齐高, 失败几何)
ORE = (560, 69, 129)        # 悬空齐胸高
PLAN = "r11-incarnation-c"

def opp_at(pos):
    reg = json.load(open(SEM, encoding="utf-8"))
    return [o["id"] for o in reg.get("resource_opportunities", [])
            if o.get("x") == pos[0] and o.get("y") == pos[1] and o.get("z") == pos[2]]

lease = gl.get_lease()

# 0. 清掉旧 B(脚齐高格子替换为空气, 经观察终结) + 放悬空齐胸高新矿
print("tp:", rcon(f"tp Bob {OLD_CELL[0]-2} {OLD_CELL[1]} {OLD_CELL[2]}"))
print("replace_old_stone:", rcon(f"setblock {OLD_CELL[0]} {OLD_CELL[1]} {OLD_CELL[2]} stone"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(1)
assert opp_at(OLD_CELL) == [], f"old B not terminalized: {opp_at(OLD_CELL)}"
print("old_B_terminalized: OK")
print("clear_stone:", rcon(f"setblock {OLD_CELL[0]} {OLD_CELL[1]} {OLD_CELL[2]} air"))
time.sleep(1)

print("place_chest_iron:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
print("below_air:", rcon(f"setblock {ORE[0]} {ORE[1]-1} {ORE[2]} air"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(1)
c_list = opp_at(ORE)
assert len(c_list) == 1, f"expect exactly one C, got {c_list}"
C = c_list[0]
print("incarnation_C=" + C)

# 1. 拉开 10 格再 plan + run-next, 2 秒后停服(机器人在路上, 图停在 RUNNING)
print("tp_away:", rcon("tp Bob 550 68 129"))
time.sleep(1)
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{C}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph = r["data"]; graph_id = graph["graph_id"]
print("planned:", graph_id, "state=", graph.get("state"))
assert graph.get("state") == "READY", graph

r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r11-l1b-{int(time.time()*1000)}"})
print("run-next accepted:", r2["data"]["graph"]["state"])
time.sleep(2)
print("stop:", rcon("stop") or "(sent)")
time.sleep(10)
json.dump({"graph_id": graph_id, "A": "ore_3467bfcc851f_8ef944cd84a44b77",
           "B": C, "cell": list(ORE)},
          open(r"D:\code\mc-experiment\r11_live1_state.json", "w", encoding="utf-8"))
print(json.dumps({"graph_id": graph_id, "C": C}, indent=2))
