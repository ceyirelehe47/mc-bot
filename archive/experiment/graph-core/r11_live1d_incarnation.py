# -*- coding: utf-8 -*-
"""LIVE-R11-1 part A4: 远距派发让任务进入 APPROACH 走路分支, 停服得干净 SUSPENDED。

教训链: (1)脚齐高矿到点 no_reachable_work_pose; (2)无镐机会 BLOCKED 秒拒;
(3)观察半径内派发直接做工位扫描, 悬空齐胸高矿的工位判定仍 null -> 秒败。
正解: 机会恢复 ACTIONABLE 后, 机器人拉到 30 格外(不可见)派发 -> startPathTo(seenFrom)
走路分支保持 RUNNING -> 2 秒停服 -> outcome_unknown -> SUSPENDED。
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
ORE = (560, 69, 129)
C = "ore_f596c40b39ba_25c66d9034f74483"
PLAN = "r11-incarnation-e"

def entry():
    reg = json.load(open(SEM, encoding="utf-8"))
    for o in reg.get("resource_opportunities", []):
        if (o.get("x"), o.get("y"), o.get("z")) == ORE:
            return o
    return None

lease = gl.get_lease()

# 0. 近距离重观察: 有界 revalidation 恢复 C -> ACTIONABLE(镐已在包里)
print("tp_near:", rcon("tp Bob 558 68 129"))
gl.call("GET", "/v1/observe", lease); time.sleep(2)
gl.call("GET", "/v1/observe", lease); time.sleep(1)
e = entry()
assert e is not None, "C missing"
print("C status:", e.get("status"), e.get("blocked_reason", ""))
assert e.get("status") == "ACTIONABLE", f"C not ACTIONABLE: {e}"

# 1. 拉到 30 格外(矿不可见)派发 -> 走 seenFrom 接近分支
print("tp_far:", rcon("tp Bob 530 68 129"))
time.sleep(1)
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{C}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph = r["data"]; graph_id = graph["graph_id"]
print("planned:", graph_id, "state=", graph.get("state"))
assert graph.get("state") == "READY", graph

r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r11-l1d-{int(time.time()*1000)}"})
print("run-next accepted:", r2["data"]["graph"]["state"])
time.sleep(3)   # 机器人在 ~28 格外走向 seenFrom, 图保持 RUNNING
print("stop:", rcon("stop") or "(sent)")
time.sleep(10)
json.dump({"graph_id": graph_id, "A": "ore_3467bfcc851f_8ef944cd84a44b77",
           "B": C, "cell": list(ORE)},
          open(r"D:\code\mc-experiment\r11_live1_state.json", "w", encoding="utf-8"))
print(json.dumps({"graph_id": graph_id, "C": C}, indent=2))
