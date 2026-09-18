# -*- coding: utf-8 -*-
"""LIVE-3(v2): 行为等价垂直切片(say/goto pause-resume/Graph mine 到 DONE/自然树 gather)。

v2 装置修正(如实记录 v1 三处装置缺陷: goto 目标溺水/mine 执行被租约过期暂停/树未探到):
- 铺石路 FAR->STAND 保证 goto 可达; 造 naturalTree 几何树(GameTest 同款);
- 清空 bot 全部 36 格, 消除旧库存(oak_logx8/raw_ironx2)的配额假阳性;
- 装置期与长轮询期持续续租(lease 过期会 pause 物理执行)。
"""
from __future__ import annotations

import json
import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

OUT = common.OUT / "06-live-behavior"
RUN = str(int(time.time()))
ORE = (569, 68, 127)  # v5: 566/567 均有挂账化身,换全新格;STAND=矿邻格隔 1 格
STAND = (568, 68, 127)
FAR = (536, 68, 127)
TREE = (566, 68, 131)  # 矿格南 4 格, naturalTree 几何: dirt 底+4 原木+顶四向叶
PLAN_KEY = f"2a03-live3-mine-{ORE[0]}-{ORE[2]}-{RUN}"

result = {"gate": "LIVE-3", "run": RUN, "asserts": []}


def check(name: str, ok: bool, detail: str = "", *, fatal: bool = False) -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))
    if not ok and fatal:
        result["ok"] = False
        common.write_json(OUT / "LIVE-3-result.json", result)
        raise SystemExit("LIVE-3 fatal: " + name)


def observe_scene(http) -> dict:
    observe = http.call("GET", "/v1/observe")["data"]
    scene = observe.get("observation")
    return scene if isinstance(scene, dict) else json.loads(scene or "{}")


def wait_terminal(http, execution_id: str, budget: float = 240.0, lease_holder: list = None) -> dict:
    deadline = time.time() + budget
    data = {}
    n = 0
    while time.time() < deadline:
        data = http.call("GET", f"/v1/executions/{execution_id}")["data"]
        if data.get("terminal"):
            return data
        n += 1
        if lease_holder and n % 8 == 0:
            try:
                http.get_lease()
            except Exception as error:
                print("lease renew during poll:", error)
        time.sleep(1)
    return data


def main() -> None:
    http = common.load_http()
    OUT.mkdir(parents=True, exist_ok=True)
    frames_before = common.read_journal(common.JOURNAL)
    seq_before = frames_before[-1]["_seq"]

    proc = common.start_server(OUT / "live3-server.log")
    try:
        http.call("GET", "/v1/observe")
        lease = http.get_lease()
        epoch = http.call("GET", "/v1/status")["data"]["body_session_epoch"]

        # ---------- 段1: say ----------
        say = http.call("POST", "/v1/executions/say", lease,
                        json.dumps({"message": "2a03-live3-behavior-slice"}),
                        headers={"X-Request-Id": f"live3-say-{RUN}"})
        say_receipt = wait_terminal(http, say["data"]["execution_id"], 30)
        common.write_json(OUT / "say.json", say_receipt)
        check("S1 say 完成且含绑定元数据",
              say_receipt.get("state") == "completed"
              and say_receipt.get("body_id") == "bob"
              and say_receipt.get("backend_kind") == "server_fake_player"
              and say_receipt.get("body_session_epoch") == epoch,
              f"state={say_receipt.get('state')}")

        # ---------- 装置准备: 遣远+铺路+造树+清包 ----------
        print(common.rcon(f"tp Bob {FAR[0]} {FAR[1]} {FAR[2]}"))
        # 铺路 FAR->STAND(y67 石面, y68/69 清空)
        print(common.rcon(f"fill {FAR[0]} 67 {FAR[2]} {STAND[0]} 67 {FAR[2]} minecraft:stone"))
        print(common.rcon(f"fill {FAR[0]} 68 {FAR[2]} {STAND[0]} 69 {FAR[2]} minecraft:air"))
        # naturalTree 几何(与 GameTest 同款): dirt 底+4 原木+顶四向叶
        tx, ty, tz = TREE
        print(common.rcon(f"setblock {tx} {ty-1} {tz} minecraft:dirt"))
        for dy in range(4):
            print(common.rcon(f"setblock {tx} {ty+dy} {tz} minecraft:oak_log"))
        for dx, dz in ((0, -1), (0, 1), (-1, 0), (1, 0)):
            print(common.rcon(f"setblock {tx+dx} {ty+3} {tz+dz} minecraft:oak_leaves"))
        # 矿格清理+放矿(铁律: bot 在 FAR 时放, 距离 30 无感知; 重跑时旧矿已被消耗, 无条件重放)
        print(common.rcon(f"setblock {STAND[0]} {STAND[1]-1} {STAND[2]} minecraft:stone"))
        print(common.rcon(f"setblock {STAND[0]} {STAND[1]} {STAND[2]} minecraft:air"))
        print(common.rcon(f"setblock {STAND[0]} {STAND[1]+1} {STAND[2]} minecraft:air"))
        print(common.rcon(f"kill @e[type=minecraft:item]"))
        print(common.rcon(f"setblock {ORE[0]} {ORE[1]} {ORE[2]} minecraft:iron_ore"))
        # 清空 bot 全部 36 格(消除 oak_logx8/raw_ironx2 旧库存假阳性)
        slots = [f"hotbar.{i}" for i in range(9)] + [f"inventory.{i}" for i in range(27)]
        for slot in slots:
            common.rcon(f"item replace entity Bob {slot} with minecraft:air")
        time.sleep(1)
        print(common.rcon("give Bob minecraft:stone_pickaxe 1"))
        time.sleep(10)  # tp 后寻路节流

        # ---------- 段2: goto(铺好的石路 FAR->STAND) + pause/resume ----------
        lease = http.get_lease()
        goto = http.call("POST", "/v1/executions/goto", lease,
                         json.dumps({"x": STAND[0], "y": STAND[1], "z": STAND[2],
                                     "allow_terrain_changes": True}),
                         headers={"X-Request-Id": f"live3-goto-{RUN}"})
        goto_id = goto["data"]["execution_id"]
        state = ""
        deadline = time.time() + 30
        while time.time() < deadline:
            state = http.call("GET", f"/v1/executions/{goto_id}")["data"].get("state")
            if state == "running":
                break
            time.sleep(0.5)
        check("S2a goto RUNNING", state == "running", f"state={state}")
        http.call("POST", f"/v1/executions/{goto_id}/pause", lease, None,
                  headers={"X-Request-Id": f"live3-goto-pause-{RUN}"})
        time.sleep(2)
        paused = http.call("GET", f"/v1/executions/{goto_id}")["data"]
        check("S2b pause 后 execution paused", paused.get("state") == "paused",
              f"state={paused.get('state')} reason={paused.get('reason')}")
        http.call("POST", f"/v1/executions/{goto_id}/resume", lease, None,
                  headers={"X-Request-Id": f"live3-goto-resume-{RUN}"})
        time.sleep(2)
        resumed = http.call("GET", f"/v1/executions/{goto_id}")["data"]
        check("S2c resume 后同一 execution 回 running",
              resumed.get("state") == "running" and resumed.get("execution_id") == goto_id,
              f"state={resumed.get('state')}")
        goto_receipt = wait_terminal(http, goto_id, 120)
        common.write_json(OUT / "goto.json", goto_receipt)
        check("S2d goto 完成且到达后置条件满足",
              goto_receipt.get("state") == "completed"
              and goto_receipt.get("reason") == "arrival_within_3_blocks_verified",
              f"state={goto_receipt.get('state')} reason={goto_receipt.get('reason')}")

        # ---------- 段3: Graph mine(bot 已在 STAND=矿邻格, 铸化身) ----------
        http.call("GET", "/v1/observe")
        time.sleep(3)
        http.call("GET", "/v1/observe")
        time.sleep(3)
        semantic = json.load(open(common.AIBOT_ROOT / "external-semantics-bob.json", encoding="utf-8"))
        matches = [o for o in semantic.get("resource_opportunities", [])
                   if (o.get("x"), o.get("y"), o.get("z")) == ORE]
        check("S3a 新铸铁矿化身 ACTIONABLE", len(matches) == 1
              and matches[0].get("status") == "ACTIONABLE",
              f"matches={[(m.get('id'), m.get('status')) for m in matches]}")
        opportunity_id = matches[0]["id"]
        baseline = observe_scene(http).get("inventory", {}).get("minecraft:raw_iron", 0)

        world_id = common.AIBOT_ROOT.joinpath("world-id").read_text(encoding="utf-8").strip()
        ref = f"mc://{world_id}/minecraft%3Aoverworld/opportunity/{opportunity_id}"
        lease = http.get_lease()
        # 清理历史非终态图(v1 残留图经重启 reconcile 转 SUSPENDED 仍持有 resource claim,
        # fail-closed 设计;SUSPENDED 也需 cancel 才释放 claim)
        graphs = http.call("GET", "/v1/graphs")["data"]
        stale_graphs = [g for g in (graphs.get("graphs") or [])
                        if g.get("state") not in ("DONE", "FAILED", "STALE", "CANCELLED")]
        for g in stale_graphs:
            cancelled = http.call("POST", f"/v1/graphs/{g['graph_id']}/cancel", lease, None,
                                  headers={"X-Request-Id": f"live3-cleanup-{RUN}"})
            print("cancelled stale graph:", g["graph_id"], g.get("state"))
        if stale_graphs:
            time.sleep(2)
        planned = http.call("POST",
                            f"/v1/graphs/opportunity?plan_key={PLAN_KEY}&ref={urllib.parse.quote(ref, safe='')}",
                            lease)["data"]
        graph_id = planned["graph_id"]
        inspected = http.call("GET", f"/v1/graphs/{graph_id}")["data"]
        check("S3b 图 READY 且节点全 READY",
              inspected.get("state") == "READY"
              and all(not n.get("execution_id") for n in inspected.get("nodes", [])),
              f"graph={graph_id} state={inspected.get('state')}")
        common.write_json(OUT / "graph-planned.json", inspected)

        lease = http.get_lease()
        dispatch = http.call("POST", f"/v1/graphs/{graph_id}/run-next", lease, None,
                             headers={"X-Request-Id": f"live3-mine-run-{RUN}"})["data"]
        common.write_json(OUT / "graph-dispatch.json", dispatch)
        deadline = time.time() + 240
        graph_final = {}
        n = 0
        while time.time() < deadline:
            graph_final = http.call("GET", f"/v1/graphs/{graph_id}")["data"]
            if graph_final.get("state") in ("DONE", "FAILED", "SUSPENDED", "STALE"):
                break
            n += 1
            if n % 8 == 0:
                try:
                    http.get_lease()
                except Exception as error:
                    print("lease renew during graph poll:", error)
            time.sleep(2)
        common.write_json(OUT / "graph-final.json", graph_final)
        check("S3c 图经 run-next->submit->driver 到达 DONE",
              graph_final.get("state") == "DONE", f"state={graph_final.get('state')}")

        final_scene = observe_scene(http)
        common.write_json(OUT / "observe-after-mine.json", final_scene)
        semantic2 = json.load(open(common.AIBOT_ROOT / "external-semantics-bob.json", encoding="utf-8"))
        gone = not any((o.get("x"), o.get("y"), o.get("z")) == ORE
                       for o in semantic2.get("resource_opportunities", []))
        check("S3d durable consumed 收据销账(机会从注册表消失)", gone)
        now_iron = final_scene.get("inventory", {}).get("minecraft:raw_iron", 0)
        check("S3e 本轮实际拾取原矿(raw_iron 增量>=1)",
              now_iron - baseline >= 1, f"raw_iron {baseline}->{now_iron}")

        # ---------- 段4: 自然树 gather oak_log x1 ----------
        # R1 教训①: 走路捡起的地表旧掉落(oak_log)会令 quota 秒满足——先清掉落物再清包
        print(common.rcon("kill @e[type=minecraft:item]"))
        for slot in [f"hotbar.{i}" for i in range(9)] + [f"inventory.{i}" for i in range(27)]:
            common.rcon(f"item replace entity Bob {slot} with minecraft:air")
        print(common.rcon("give Bob minecraft:cobblestone 32"))
        time.sleep(1)
        cleaned = observe_scene(http)
        common.write_json(OUT / "observe-tree-baseline.json", cleaned)
        before_logs = cleaned.get("inventory", {}).get("minecraft:oak_log", 0)
        lease = http.get_lease()
        gather = http.call("POST", "/v1/executions/gather", lease,
                           json.dumps({"item": "minecraft:oak_log", "count": 1}),
                           headers={"X-Request-Id": f"live3-tree-{RUN}"})
        gather_receipt = wait_terminal(http, gather["data"]["execution_id"], 300, [lease])
        common.write_json(OUT / "tree-gather.json", gather_receipt)
        after_scene = observe_scene(http)
        after_logs = after_scene.get("inventory", {}).get("minecraft:oak_log", 0)
        check("S4 树 gather 完成且配额核验(真实砍伐: 原木增量>=1)",
              gather_receipt.get("state") == "completed"
              and gather_receipt.get("reason", "").startswith("inventory_family_or_exact_quota_verified")
              and after_logs - before_logs >= 1,
              f"state={gather_receipt.get('state')} logs {before_logs}->{after_logs}")
        common.write_json(OUT / "observe-after-tree.json", after_scene)
    finally:
        common.stop_server(proc)

    frames = common.read_journal(common.JOURNAL)
    common.write_json(OUT / "journal-frames-live3.json", frames[-60:])
    new_exec = [f for f in frames if f.get("kind") == "execution"
                and f.get("state") == "accepted" and f.get("_seq", 0) > seq_before]
    ops = sorted(f.get("operation") for f in new_exec)
    check("S5 journal 记录 4 个操作执行帧(say/goto/gather/mine_opportunity)且四绑定字段齐",
          set(ops) == {"say", "goto", "gather", "mine_opportunity"}
          and all(f.get("body_id") == "bob" and f.get("backend_kind") == "server_fake_player"
                  and f.get("body_session_epoch") for f in new_exec),
          f"ops={ops}")
    consumed = [f for f in frames if f.get("kind") == "resource_opportunity_consumed"
                and f.get("_seq", 0) > seq_before]
    check("S6 durable consumed 收据帧存在(绑定执行)", len(consumed) >= 1
          and any(f.get("opportunity_id") == opportunity_id for f in consumed),
          f"consumed={len(consumed)}")

    result["ok"] = all(a["ok"] for a in result["asserts"])
    result["epoch"] = epoch
    result["graph_id"] = graph_id
    result["opportunity_id"] = opportunity_id
    result["tree"] = TREE
    common.write_json(OUT / "LIVE-3-result.json", result)
    print("LIVE-3", "PASS" if result["ok"] else "FAIL", f'({len(result["asserts"])} asserts)')


if __name__ == "__main__":
    main()
