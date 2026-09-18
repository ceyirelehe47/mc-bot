# -*- coding: utf-8 -*-
"""LIVE-R11-2: 同化身执行期 stale/loss -> Graph STALE(不是 FAILED/DONE)。

1. 新矿 C 注册(独立位置)
2. 为 C plan 图并 run-next(普通 graphRunNext -> BridgeKernel.submit 路径)
3. 机器人到达前外部移除矿
4. 物理任务以类型化失败结束; 图必须终态 STALE, reason 前缀 execution_failed_terminal_unsatisfied:
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
JOURNAL = r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-body-bob.journal"
ORE = (560, 68, 131)   # 与 R11-1 的 (560,68,129) 错开 2 格
PLAN = "r11-stale-exec-2"

def opp_at(pos):
    reg = json.load(open(SEM, encoding="utf-8"))
    return [o["id"] for o in reg.get("resource_opportunities", [])
            if o.get("x") == pos[0] and o.get("y") == pos[1] and o.get("z") == pos[2]]

# 0. 站位(已验证地形 558,68,129, 距 C 约 2.8 格注册) + 清格 + 保证有镐
print("tp:", rcon("tp Bob 558 68 129"))
print("clear:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} air"))
print("pickaxe:", rcon("give Bob minecraft:iron_pickaxe 1"))
time.sleep(1)
lease = gl.get_lease()

# 1. C 注册
print("place_iron:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
gl.call("GET", "/v1/observe", lease)
time.sleep(2)
gl.call("GET", "/v1/observe", lease)
time.sleep(1)
c_list = opp_at(ORE)
assert len(c_list) == 1, f"expect exactly one C, got {c_list}"
C = c_list[0]
print("incarnation_C=" + C)

# 1b. 拉到 30 格(R11-1 A5 验证过), 派发后任务稳定走 seenFrom 接近分支,
#     到达可见距离时矿已被移除 -> state 不匹配 -> durable stale 收据 -> STALE
print("tp_far:", rcon("tp Bob 530 68 129"))
time.sleep(1)

# 2. plan + run-next
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{C}"
q_ref = urllib.parse.quote(REF, safe="")
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph_id = r["data"]["graph_id"]
print("planned:", graph_id, "state=", r["data"].get("state"))
assert r["data"].get("state") == "READY", r["data"]

r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
             {"X-Request-Id": f"r11-l2-{int(time.time()*1000)}"})
print("run-next accepted")

# 3. 机器人已在路上 -> 立刻外部移除矿
time.sleep(1)
print("remove_ore:", rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} air"))

# 4. 轮询图终态
ins = {}
deadline = time.time() + 120
while time.time() < deadline:
    gl.call("POST", "/v1/lease/renew", lease)
    ins = gl.call("GET", f"/v1/graphs/{graph_id}", lease)["data"]
    if ins.get("state") in ("DONE", "STALE", "FAILED", "CANCELLED"):
        break
    time.sleep(3)
print("graph_state=", ins.get("state"))
print("nodes=", json.dumps(ins.get("nodes", []), ensure_ascii=False)[:600])

reasons = [n.get("reason") or "" for n in ins.get("nodes", [])]
assert ins.get("state") == "STALE", f"expected STALE, got {ins.get('state')}"
assert any(r.startswith("execution_failed_terminal_unsatisfied:") for r in reasons), reasons
assert not any(r == "execution_failed" for r in reasons), reasons
print("GRAPH_STALE_NOT_FAILED: OK")

# 5. journal 里 C 的结构化 stale 收据
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\r11_journal_read.py", JOURNAL, C],
                     capture_output=True, text=True).stdout
print(out.strip())
assert "resource_opportunity_stale" in out and C in out, "C stale receipt missing"
print("STALE_RECEIPT_DURABLE: OK")
print("LIVE-R11-2 ALL ASSERTIONS PASS")
print("GRAPH_ID=" + graph_id)
