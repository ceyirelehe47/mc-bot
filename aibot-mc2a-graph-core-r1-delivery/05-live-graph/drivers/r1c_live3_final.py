# -*- coding: utf-8 -*-
"""LIVE-R1-3 终版: 14 格可达矿 + run-next 0.3s 后停服 -> SUSPENDED -> 矿位换石 -> durable stale -> STALE。"""
import importlib.util, json, time, subprocess, sys, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

RCON = r"D:\code\mc-experiment\rcon.py"
def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()

WORLD = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"
ORE = (564, 68, 129)
PLAN = "r1c-iron-3d"

phase = sys.argv[1] if len(sys.argv) > 1 else "setup"

if phase == "setup":
    print(rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} iron_ore"))
    print(ron_ := rcon("tp Bob 562 68 129"))
    lease = gl.get_lease()
    gl.call("GET", "/v1/observe", lease)
    time.sleep(2)
    print(rcon("tp Bob 550 68 129"))
    time.sleep(3)
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
                 {"X-Request-Id": f"r1c-3d-{int(time.time()*1000)}"})
    print("run-next:", r2["data"]["graph"]["state"])
    time.sleep(0.3)
    print("停服:", rcon("stop") or "(已发出)")
    time.sleep(8)
    open(r"D:\code\mc-experiment\r1c_live3_state.json", "w").write(
        json.dumps({"graph_id": graph_id, "opp": OPP, "ore": ORE}))
    print("state saved")

elif phase == "verify-suspended":
    st = json.load(open(r"D:\code\mc-experiment\r1c_live3_state.json"))
    g = gl.call("GET", "/v1/graphs/" + st["graph_id"])["data"]
    print("重启后图状态:", g["state"], [n["reason"] for n in g["nodes"]])
    d = gl.call("GET", "/v1/status")["data"]
    print("活跃执行(应为None):", d.get("active_execution"))
    assert g["state"] == "SUSPENDED", f"期望 SUSPENDED 实际 {g['state']}"

elif phase == "stale":
    st = json.load(open(r"D:\code\mc-experiment\r1c_live3_state.json"))
    ore = st["ore"]
    print(rcon(f"setblock {ore[0]} {ore[1]} {ore[2]} stone"))
    print(rcon("tp Bob 562 68 129"))
    lease = gl.get_lease()
    gl.call("GET", "/v1/observe", lease)
    deadline = time.time() + 30
    while time.time() < deadline:
        g = gl.call("GET", "/v1/graphs/" + st["graph_id"])["data"]
        if g["state"] in ("STALE", "DONE", "FAILED"):
            break
        time.sleep(2)
    print("终态:", g["state"], [n["reason"] for n in g["nodes"]])
    assert g["state"] == "STALE", f"期望 STALE 实际 {g['state']}"
    # 收据与 claim
    ev = gl.call("GET", "/v1/events?epoch=" + gl.call("GET", "/v1/status")["data"]["event_epoch"] + "&after=0&limit=1000")["data"]
    receipts = [e for e in ev["events"] if e["kind"].startswith("resource_opportunity_")
                and st["opp"] in (e.get("payload") or "")]
    for e in receipts:
        print("收据:", e["sequence"], e["kind"], (e.get("payload") or "")[:200])
    assert any(e["kind"] == "resource_opportunity_stale" for e in receipts), "缺 stale 收据"
    print("LIVE-R1-3 PASS: durable stale -> reconcile -> STALE, 绝非 DONE")
