# -*- coding: utf-8 -*-
"""LIVE-R11-1 part B: 重启后对账断言。

前置: part A 已停服(留存 graph_id/A/B)。重启服务器后运行本脚本:
- B 仍是当前活动机会(startup 终态收据对账没有删掉 B)
- B 的图保持 SUSPENDED(不被 A 的旧收据推成 STALE/DONE)
- journal 中 A 的 stale 收据仍在
"""
import importlib.util, json, time, sys

spec = importlib.util.spec_from_file_location("gl", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gl)

SEM = r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-semantics-bob.json"
JOURNAL = r"D:\code\mc-experiment\mc-server-mc1ca\world_play\aibot\external-body-bob.journal"
ORE = (560, 68, 129)

state = json.load(open(r"D:\code\mc-experiment\r11_live1_state.json", encoding="utf-8"))
graph_id, A, B = state["graph_id"], state["A"], state["B"]
ORE = tuple(state.get("cell", [560, 68, 129]))
print("graph_id=", graph_id, "A=", A, "B=", B, "cell=", ORE)

lease = gl.get_lease()

# 1. B 仍活动
reg = json.load(open(SEM, encoding="utf-8"))
at_cell = [o["id"] for o in reg.get("resource_opportunities", [])
           if o.get("x") == ORE[0] and o.get("y") == ORE[1] and o.get("z") == ORE[2]]
assert at_cell == [B], f"restart reconcile must keep B active at cell, got {at_cell}"
print("B_SURVIVES_RESTART: OK")
all_ids = [o["id"] for o in reg.get("resource_opportunities", [])]
assert A not in all_ids, "A resurrected"
print("A_NOT_RESURRECTED: OK")

# 2. B 的图不被 A 的收据终态化
ins = gl.call("GET", f"/v1/graphs/{graph_id}", lease)["data"]
nodes = ins.get("nodes", [])
print("graph_state=", ins.get("state"), "reasons=", [n.get("reason") for n in nodes])
assert ins.get("state") == "SUSPENDED", f"expected SUSPENDED, got {ins.get('state')}"
for n in nodes:
    assert n.get("reason") in ("execution_outcome_unknown_no_replay",
                               "postcondition_unsatisfied"), \
        f"unexpected node reason {n.get('reason')}"
print("GRAPH_NOT_TERMINALIZED_BY_A_RECEIPT: OK")

# 3. journal 里 A 的 stale 收据仍在
import subprocess
out = subprocess.run([sys.executable, r"D:\code\mc-experiment\r11_journal_read.py", JOURNAL, A],
                     capture_output=True, text=True).stdout
print(out.strip())
assert "resource_opportunity_stale" in out and f'"opportunity_id": "{A}"' in out.replace(", ", ", "), \
    "A stale receipt missing from journal"
print("A_RECEIPT_STILL_PRESENT: OK")
print("LIVE-R11-1 ALL ASSERTIONS PASS")
