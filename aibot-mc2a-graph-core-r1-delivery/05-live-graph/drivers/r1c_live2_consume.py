# -*- coding: utf-8 -*-
"""LIVE-R1-2: 机会经图执行 -> inventory 证明 -> durable consumed receipt -> DONE."""
import importlib.util, json, time, urllib.parse, sys

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

OPP = "ore_fed47d71dd5c302187c43490fefa9ad1"
WORLD = "80980dea-4a25-46fa-ab97-eeefcc8b4b39"
REF = f"mc://{WORLD}/minecraft%3Aoverworld/opportunity/{OPP}"
PLAN = "r1c-iron-2"

lease = gl.get_lease()
q_ref = urllib.parse.quote(REF, safe="")
print("ref(编码后):", q_ref[:80], "...")

# 1. plan
r = gl.call("POST", f"/v1/graphs/opportunity?plan_key={PLAN}&ref={q_ref}", lease)
graph_id = r["data"]["graph_id"]
print("planned:", json.dumps(r["data"], ensure_ascii=False))

# 2. 等节点 READY 即 run-next
for _ in range(10):
    ins = gl.call("GET", f"/v1/graphs/{graph_id}")["data"]
    if ins.get("state") in ("READY", "PLANNED"):
        break
    time.sleep(1)
print("inspect:", json.dumps(ins, ensure_ascii=False)[:400])

# 3. run-next (dispatch 经 BridgeKernel.submit)
r2 = gl.call("POST", f"/v1/graphs/{graph_id}/run-next", lease,
             None, {"X-Request-Id": f"r1c-runnext-{int(time.time()*1000)}"})
print("run-next:", json.dumps(r2, ensure_ascii=False)[:400])

# 4. 轮询图终态
deadline = time.time() + 180
while time.time() < deadline:
    gl.call("POST", "/v1/lease/renew", None, None, None) if False else None
    ins = gl.call("GET", f"/v1/graphs/{graph_id}")["data"]
    st = ins.get("state")
    nodes = ins.get("nodes", [])
    reasons = [n.get("reason") for n in nodes]
    if st in ("DONE", "STALE", "FAILED", "CANCELLED"):
        print("终态:", st, "reasons:", reasons)
        break
    time.sleep(3)
else:
    print("TIMEOUT state:", ins.get("state"), "reasons:", [n.get("reason") for n in ins.get("nodes", [])])

print("FINAL:", json.dumps(ins, ensure_ascii=False)[:1200])
print("GRAPH_ID=" + graph_id)
