# -*- coding: utf-8 -*-
"""LIVE-R12-3: 停服态注入 pre-R1.2 旧式 id(无 birth)→启动收养(id 不变+journal 恰 1 条 birth)
→再重启幂等(无第 2 条)→Graph plan/inspect 用同 id→正常 stale 终结收尾。"""
import importlib.util, json, subprocess, sys, time, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)
sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

ORE = (562, 68, 127)
LEGACY = "ore_0123456789abcdef0123456789abcdef"
WORLD = rc.load_state()["world"]

def journal_births_of(oid):
    out = subprocess.run([sys.executable, r"D:\code\mc-experiment\read_lifecycle_receipts.py", rc.JOURNAL],
                         capture_output=True, text=True).stdout
    rows = [json.loads(l) for l in out.splitlines()]
    return [r for r in rows if r["fields"].get("kind") == "resource_opportunity_birth" and r["fields"].get("opportunity_id") == oid], rows

# 1. 启动布置真矿(bot 遣远不观察) → 停服
proc = rc.start_server(r"D:\code\mc-experiment\r12-live3-server-a.log")
lease = gl.get_lease()
print(rc.rcon("tp Bob 532 68 127")); time.sleep(1)
print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore")); time.sleep(2)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live3-server-a.log")
reg = json.load(open(rc.SEM, encoding="utf-8"))
assert not any(o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]
               for o in reg.get("resource_opportunities", [])), "装置格应无机会(bot 未观察)"
print("[1] 矿已布置, 装置格无机会")

# 2. 停服态注入 legacy id + 确认 journal 无其 birth
r = subprocess.run([sys.executable, r"D:\code\mc-experiment\inject_legacy_opportunity.py",
                    rc.SEM, LEGACY, "minecraft:overworld",
                    str(ORE[0]), str(ORE[1]), str(ORE[2]), "minecraft:iron_ore",
                    "561", "68", "127"], capture_output=True, text=True)
assert r.returncode == 0, r.stderr
births, _ = journal_births_of(LEGACY)
assert not births, f"升级前 journal 已有 birth({LEGACY})"
print(f"[2] 已注入 legacy {LEGACY} (journal 无其 birth)")

# 3. 首次 R1.2 启动 → 收养: id 不变 + journal 恰 1 条 birth(同 id)
proc = rc.start_server(r"D:\code\mc-experiment\r12-live3-server-b.log")
time.sleep(2)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live3-server-b.log")
after = json.load(open(rc.SEM, encoding="utf-8"))
ids = [o["id"] for o in after.get("resource_opportunities", []) if o.get("id") == LEGACY]
assert ids == [LEGACY], f"legacy id 被改写: {ids}"
births1, rows1 = journal_births_of(LEGACY)
assert len(births1) == 1, f"收养 birth 数 {len(births1)} != 1"
print(f"[3] 首启收养: id 不变, journal 恰 1 条 birth(legacy) seq={births1[0]['seq']}")

# 4. 再重启 → 幂等: 无第 2 条 birth
proc = rc.start_server(r"D:\code\mc-experiment\r12-live3-server-c.log")
time.sleep(2)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live3-server-c.log")
births2, rows2 = journal_births_of(LEGACY)
assert len(births2) == 1, f"第二次重启后 birth 数 {len(births2)} != 1 (重复收养)"
still = json.load(open(rc.SEM, encoding="utf-8"))
assert any(o.get("id") == LEGACY for o in still.get("resource_opportunities", [])), "legacy 不在注册表"
print("[4] 再重启幂等: 仍恰 1 条 birth, id 不变")

# 5. Graph plan/inspect 用同 legacy id
proc = rc.start_server(r"D:\code\mc-experiment\r12-live3-server-d.log")
lease = gl.get_lease()
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{LEGACY}"
g = gl.call("POST", f"/v1/graphs/opportunity?plan_key=r12-legacy&ref={urllib.parse.quote(REF, safe='')}", lease)["data"]["graph_id"]
ins = gl.call("GET", f"/v1/graphs/{g}", lease)["data"]
assert LEGACY in json.dumps(ins), "Graph 不引用 legacy id"
print(f"[5] Graph {g[:22]}… state={ins.get('state')} 引用 legacy id")
rc.save_state(live3_graph=g)

# 6. 清理: stone 替换矿格 → bot 观察 → stale(legacy) 终结(收养 id 正常关闭)
print(rc.rcon("tp Bob 561 68 127")); time.sleep(2)
print(rc.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} minecraft:stone")); time.sleep(3)
gl.call("GET", "/v1/observe", lease); time.sleep(2)
final = json.load(open(rc.SEM, encoding="utf-8"))
left = [o["id"] for o in final.get("resource_opportunities", []) if o.get("id") == LEGACY]
assert not left, "legacy 未正常关闭"
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live3-server-d.log")
births3, rows3 = journal_births_of(LEGACY)
stales = [x for x in rows3 if x["fields"].get("kind") == "resource_opportunity_stale" and x["fields"].get("opportunity_id") == LEGACY]
assert len(births3) == 1 and stales, f"收尾异常 birth={len(births3)} stale={len(stales)}"
print(f"[6] 正常关闭: birth(legacy) seq={births3[0]['seq']} < stale(legacy) seq={stales[0]['seq']}, 注册表已清")
rc.save_state(live3_passed=True, opp_legacy=LEGACY)
print("PASS LIVE-R12-3")
