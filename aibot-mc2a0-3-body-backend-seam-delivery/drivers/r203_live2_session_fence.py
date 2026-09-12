# -*- coding: utf-8 -*-
"""LIVE-2: 物理会话替换围栏。

通道说明(如实记录): runbook 原文用 /aibot despawn+spawn 做进程内替换,但 overlay 的
ExternalBodyAccess.permitsLegacyOperation(apply_to_aibot.py 注入 BotAuthorizationGate 的
治理边界)对保留身体一票否决 legacy ADMIN——despawn 回执 "[AIBot] 找不到该 Bot 或无权限。"
本身就是该治理边界的正确证据(归档 P0)。因此实机围栏改经**重启通道**更换物理载具:
goto RUNNING 中优雅停服->重启(新实体/新 session epoch)->旧执行 outcome_unknown+
租约吊销+needs_reconcile+无重放+reconcile 后新 request 可开工。进程内换 session 的
围栏逻辑由 BridgeCoreTest(fake backend binding 轮换)89 断言覆盖。
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

OUT = common.OUT / "05-live-session-fence"
RUN = str(int(time.time()))

result = {"gate": "LIVE-2", "run": RUN, "asserts": []}


def check(name: str, ok: bool, detail: str = "", *, fatal: bool = False) -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))
    if not ok and fatal:
        result["ok"] = False
        common.write_json(OUT / "LIVE-2-result.json", result)
        raise SystemExit("LIVE-2 fatal: " + name)


def main() -> None:
    http = common.load_http()
    OUT.mkdir(parents=True, exist_ok=True)
    frames_before = common.read_journal(common.JOURNAL)
    seq_before = frames_before[-1]["_seq"]

    # ---------- 第 1 段: 启动->lease->goto RUNNING->despawn 治理证据->停服 ----------
    proc = common.start_server(OUT / "live2-server-a.log")
    goto_id = ""
    epoch_1 = ""
    try:
        http.call("GET", "/v1/observe")
        lease = http.get_lease()
        status_1 = http.call("GET", "/v1/status")["data"]
        epoch_1 = status_1["body_session_epoch"]
        check("P1 初始 control_active=true", status_1.get("control_active") is True)

        observe = http.call("GET", "/v1/observe")["data"]
        scene = observe.get("observation")
        obs_scene = scene if isinstance(scene, dict) else json.loads(scene or "{}")
        pos = obs_scene.get("position") or obs_scene.get("self", {}).get("block_position") or {}
        bx, by, bz = int(pos.get("x", 0)), int(pos.get("y", 64)), int(pos.get("z", 0))
        goal = {"x": bx + 40, "y": by, "z": bz, "allow_terrain_changes": True}
        submit = http.call("POST", "/v1/executions/goto", lease, json.dumps(goal),
                           headers={"X-Request-Id": f"live2-goto-fence-{RUN}"})
        goto_id = submit["data"]["execution_id"]
        deadline = time.time() + 30
        state = ""
        while time.time() < deadline:
            ex = http.call("GET", f"/v1/executions/{goto_id}")["data"]
            state = ex.get("state")
            if state == "running":
                break
            time.sleep(0.5)
        check("P2 goto 进入 RUNNING", state == "running", f"state={state}", fatal=True)
        common.write_json(OUT / "goto-running.json", ex)

        # 治理边界证据: legacy despawn 对保留身体拒绝
        despawn_receipt = common.rcon("aibot despawn Bob")
        print("despawn receipt:", repr(despawn_receipt))
        check("P0 legacy despawn 被治理边界拒绝(保留身体不走 legacy ADMIN)",
              "无权限" in despawn_receipt or "找不到" in despawn_receipt, despawn_receipt)
        common.write_json(OUT / "despawn-governance-receipt.json",
                          {"receipt": despawn_receipt, "note": "runbook 进程内通道被 overlay 治理边界封锁"})

        still = http.call("GET", f"/v1/executions/{goto_id}")["data"]
        check("P3 despawn 拒绝后执行不受影响(仍 running)", still.get("state") == "running",
              f"state={still.get('state')}")
    finally:
        common.stop_server(proc)

    # ---------- 第 2 段: 重启=物理载具更换, 断言围栏三要素 ----------
    proc = common.start_server(OUT / "live2-server-b.log")
    try:
        status_2 = http.call("GET", "/v1/status")["data"]
        common.write_json(OUT / "status-after-replacement.json", status_2)
        epoch_2 = status_2.get("body_session_epoch", "")
        check("P4 逻辑 body_id 仍为 bob", status_2.get("body_id") == "bob")
        check("P5 body_session_epoch 轮换(新物理载具)", bool(epoch_2) and epoch_2 != epoch_1,
              f"epoch_1={epoch_1} epoch_2={epoch_2}")
        check("P6 租约被吊销(control_active=false)", status_2.get("control_active") is False)
        check("P7 needs_reconcile=true", status_2.get("needs_reconcile") is True)

        ex_final = http.call("GET", f"/v1/executions/{goto_id}")["data"]
        common.write_json(OUT / "goto-interrupted.json", ex_final)
        check("P8 被打断执行 outcome_unknown", ex_final.get("state") == "outcome_unknown",
              f"state={ex_final.get('state')}")
        check("P9 打断 reason 为围栏语义(重启通道=server_stopping;进程内通道=body_session_changed,后者由 BridgeCoreTest 覆盖)",
              ex_final.get("reason") in ("body_session_changed", "server_stopping_no_automatic_replay"),
              f"reason={ex_final.get('reason')}")
        check("P10 执行收据保留 admit 时绑定(epoch_1)",
              ex_final.get("body_id") == "bob"
              and ex_final.get("backend_kind") == "server_fake_player"
              and ex_final.get("body_session_epoch") == epoch_1,
              f"epoch={ex_final.get('body_session_epoch')}")

        time.sleep(4)
        ex_still = http.call("GET", f"/v1/executions/{goto_id}")["data"]
        check("P11 静置后仍 outcome_unknown(无自动重启)", ex_still.get("state") == "outcome_unknown")

        # reconcile + 新租约 + 新 request 开工
        http.call("GET", "/v1/observe")
        status_r = http.call("GET", "/v1/status")["data"]
        check("P12 observe 后 needs_reconcile 清除", status_r.get("needs_reconcile") is False)
        lease2 = http.get_lease()
        say = http.call("POST", "/v1/executions/say", lease2,
                        json.dumps({"message": "2a03-live2-new-work-after-fence"}),
                        headers={"X-Request-Id": f"live2-say-new-work-{RUN}"})
        say_id = say["data"]["execution_id"]
        deadline = time.time() + 30
        while time.time() < deadline:
            receipt = http.call("GET", f"/v1/executions/{say_id}")["data"]
            if receipt.get("terminal"):
                break
            time.sleep(1)
        common.write_json(OUT / "say-new-work.json", receipt)
        check("P13 围栏后新 request 的 say 完成", receipt.get("state") == "completed",
              f"state={receipt.get('state')}")
        check("P14 新收据绑定到新 epoch_2", receipt.get("body_session_epoch") == epoch_2,
              f"epoch={receipt.get('body_session_epoch')}")
    finally:
        common.stop_server(proc)

    frames_after = common.read_journal(common.JOURNAL)
    common.write_json(OUT / "journal-frames-live2.json", frames_after[-40:])
    sessions = [f for f in frames_after if f.get("kind") == "body_session_changed"
                and f.get("_seq", 0) > seq_before]
    check("P15 body_session_changed 事件帧存在", len(sessions) >= 1, f"frames={len(sessions)}")
    new_bindings = [f for f in frames_after if f.get("kind") == "body_binding"
                    and f.get("_seq", 0) > seq_before]
    check("P16 新 body_binding 帧存在", any(f.get("body_session_epoch") == epoch_2
                                            for f in new_bindings),
          f"new_bindings={len(new_bindings)}")
    accepted = [f for f in frames_after if f.get("kind") == "execution"
                and f.get("state") == "accepted" and f.get("_seq", 0) > seq_before]
    check("P17 journal 无围栏期间的重放(accepted 仅 goto+say)",
          len(accepted) == 2 and {f.get("operation") for f in accepted} == {"goto", "say"},
          f"accepted={[f.get('operation') for f in accepted]}")

    result["ok"] = all(a["ok"] for a in result["asserts"])
    result["epoch_1"] = epoch_1
    result["epoch_2"] = epoch_2
    common.write_json(OUT / "LIVE-2-result.json", result)
    print("LIVE-2", "PASS" if result["ok"] else "FAIL", f'({len(result["asserts"])} asserts)')


if __name__ == "__main__":
    main()
