# -*- coding: utf-8 -*-
"""LIVE-R11-1 part A: 同格同方块化身隔离(停服前)。

1. 放铁矿 -> 感知注册 incarnation_A
2. 格替换为石头 -> 真实 cell-replaced stale 收据(A 终结, 移出注册表)
3. 重新放同格铁矿 -> incarnation_B(必须 A != B)
4. 为 B plan 图(必须 READY, 不能被 A 的旧收据解析掉)
5. run-next 派发后立刻优雅停服 -> 期望 SUSPENDED
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
ORE = (560, 68, 129)
PLAN = "r11-incarnation-b"

def opp_at(pos):
    reg = json.load(open(SEM, encoding="utf-8"))
    return [o["id"] for o in reg.get("resource_opportunities", [])
            if o.get("x") == pos[0] and o.get("y") == pos[1] and o.get("z") == pos[2]]

# 0. 站位 + 清格
print("tp:", rcon(f"tp Bob {ORE[0]-2} {ORE[1]} {ORE[2]}"))
print("clear:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} air"))
time.sleep(1)
lease = gl.get_lease()

# 1. incarnation_A 注册
print("place_iron:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(1)
a_list = opp_at(ORE)
assert len(a_list) == 1, f"expect exactly one A, got {a_list}"
A = a_list[0]
print("incarnation_A=" + A)

# 2. 格替换 -> A 终结(stale 收据)
print("replace_stone:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} stone"))
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(1)
assert opp_at(ORE) == [], f"A still active after cell replacement: {opp_at(ORE)}"
print("A_terminalized: registry no longer contains A")

# 3. 同格重放铁矿 -> B
print("replace_iron:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(1)
b_list = opp_at(ORE)
assert len(b_list) == 1, f"expect exactly one B, got {b_list}"
B = b_list[0]
print("incarnation_B=" + B)
assert A != B, "SAME ID REUSED ACROSS INCARNATIONS — FAIL"
print("ASSERT A != B: OK")

# 4. 拉开距离(R1-3 验证过的站位)再为 B plan 图
print("tp_away:", rcon("tp Bob 550 68 129"))
time.sleep(1)
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{B}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph = r["data"]
graph_id = graph["graph_id"]
print("planned:", graph_id, "state=", graph.get("state"))
assert graph.get("state") == "READY", f"B graph not READY (poisoned by A receipt?): {graph}"

# 5. 派发 + 立刻优雅停服(机器人在路上)
r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r11-l1a-{int(time.time()*1000)}"})
print("run-next accepted:", json.dumps(r2["data"], ensure_ascii=False)[:300])
time.sleep(4)
print("stop:", rcon("stop") or "(sent)")
time.sleep(10)
json.dump({"graph_id": graph_id, "A": A, "B": B},
          open(r"D:\code\mc-experiment\r11_live1_state.json", "w", encoding="utf-8"))
print(json.dumps({"graph_id": graph_id, "A": A, "B": B}, indent=2))
