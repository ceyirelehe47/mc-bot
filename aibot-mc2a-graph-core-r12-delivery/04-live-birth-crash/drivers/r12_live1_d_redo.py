# -*- coding: utf-8 -*-
"""LIVE-R12-1 阶段D: 原图 FAILED(execution_failed=跨重启执行作废, R1.1 既定语义, 非快照陈旧所致)。
按 R1-3 同款"同一机会重新成图"路径: 新图(同 object_id=A) → run-next → submit → 挖矿拾取 → DONE
→ 停服 → journal: birth(A) < consumed(A)。"""
import importlib.util, json, subprocess, sys, time, urllib.parse

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec); spec.loader.exec_module(gl)
sys.path.insert(0, r"D:\code\mc-experiment")
import r12_common as rc

st = rc.load_state()
A = st["opp_A"]

proc = rc.start_server(r"D:\code\mc-experiment\r12-live1-server-d.log")
lease = gl.get_lease()

# 1. 同一机会 A 重新成图(R1-3 同款: 同 object_id 新 graph)
REF = f"mc://{st['world']}/minecraft%3Aoverworld/opportunity/{A}"
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key=r12-iron-1e&ref={urllib.parse.quote(REF, safe='')}", lease)
gid = r["data"]["graph_id"]
print(f"[1] 重新成图 {gid} state={r['data']['state']}")
for _ in range(10):
    ins = gl.call("GET", f"/v1/graphs/{gid}", lease)["data"]
    if ins.get("state") in ("READY", "PLANNED"):
        break
    time.sleep(1)
assert A in json.dumps(ins), "新图不引用 A"

# 2. 普通派发: bot 可能在 532(远处, 走路分支)或 561(矿旁工位)。启动后先等寻路节流窗口
# 过去; 若 run-next 因 pathfinding_throttled 失败, 换 plan_key 重新成图再试。
time.sleep(10)
gid_final = None
for attempt in range(4):
    lease = gl.get_lease()  # 长轮询期间租约会过期, 每次尝试前重取(内部先试续期)
    key = f"r12-iron-1e{attempt}"
    REF = f"mc://{st['world']}/minecraft%3Aoverworld/opportunity/{A}"
    r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={key}&ref={urllib.parse.quote(REF, safe='')}", lease)
    g = r["data"]["graph_id"]
    for _ in range(10):
        ins = gl.call("GET", f"/v1/graphs/{g}", lease)["data"]
        if ins.get("state") in ("READY", "PLANNED"):
            break
        time.sleep(1)
    assert A in json.dumps(ins), "新图不引用 A"
    r2 = gl.call("POST", f"/v1/graphs/{g}/run-next", lease, None,
                 {"X-Request-Id": f"r12l1d-{attempt}-{int(time.time()*1000)}"})
    print(f"[2] attempt{attempt} {g[:22]}… run-next → {r2['data']['graph']['state']}")
    deadline = time.time() + 240
    while time.time() < deadline:
        try:
            ins = gl.call("GET", f"/v1/graphs/{g}", lease)["data"]
        except RuntimeError:
            lease = gl.get_lease()
            continue
        if ins.get("state") in ("DONE", "STALE", "FAILED", "CANCELLED"):
            break
        time.sleep(3)
    print("[2] 终态:", ins.get("state"), "节点:", [(n.get("state"), n.get("reason")) for n in ins.get("nodes", [])])
    if ins.get("state") == "DONE":
        gid_final = g
        break
    if ins.get("state") == "FAILED" and any(n.get("reason") == "execution_failed" for n in ins.get("nodes", [])):
        print("[2] execution_failed(疑似 throttled), 等待后重试")
        time.sleep(8)
        continue
    break
assert gid_final, f"未能达 DONE: {ins.get('state')}"
gid = gid_final

# 3. 停服 → journal: birth(A) < consumed(A)
rc.stop_server(proc, r"D:\code\mc-experiment\r12-live1-server-d.log")
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\read_lifecycle_receipts.py", rc.JOURNAL],
                     capture_output=True, text=True).stdout
open(r"D:\code\mc-experiment\r12-journal-final.txt", "w").write(out)
rows = [json.loads(l) for l in out.splitlines()]
births = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_birth" and r["fields"].get("opportunity_id") == A]
consumed = [r for r in rows if r["fields"].get("kind") == "resource_opportunity_consumed" and r["fields"].get("opportunity_id") == A]
assert births and consumed, f"收据链不完整 birth={len(births)} consumed={len(consumed)}"
assert births[0]["seq"] < consumed[0]["seq"]
print(f"[3] journal: birth(A) seq={births[0]['seq']} < consumed(A) seq={consumed[0]['seq']}")
rc.save_state(live1_done=True, redo_graph=gid)
print("PASS LIVE-R12-1 (identity 恢复 + 重新成图经普通 submit 达 DONE)")
