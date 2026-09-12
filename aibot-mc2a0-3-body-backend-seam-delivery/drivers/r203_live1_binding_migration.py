# -*- coding: utf-8 -*-
"""LIVE-1: 稳定逻辑身份 + 旧 UUID journal 迁移 + 重启 session 轮换。

阶段A: 首次启动(迁移) -> status 断言(observe 之前) -> 历史图加载 -> observe 清 reconcile
       -> say 有界操作 -> 收据四字段断言 -> 停服 -> journal 迁移断言
阶段B: 正常重启 -> status 断言(bob 不变/epoch 轮换/needs_reconcile/无重放) -> 停服 -> journal 终局断言
"""
from __future__ import annotations

import json
import shutil
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

OUT = common.OUT / "04-live-binding"
OLD_UUID = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b"
UUID_CHARS = set("0123456789abcdef-")

result: dict = {"gate": "LIVE-1", "asserts": []}


def check(name: str, ok: bool, detail: str = "") -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))
    if not ok:
        common.write_json(OUT / "LIVE-1-result.json", result)
        raise SystemExit("LIVE-1 assertion failed: " + name)


def is_uuidish(value: str) -> bool:
    return len(value) == 36 and set(value) <= UUID_CHARS


def main() -> None:
    http = common.load_http()
    OUT.mkdir(parents=True, exist_ok=True)
    backup = common.AIBOT_ROOT / "external-body-bob.journal.pre-2a03"
    if not backup.exists():
        shutil.copy2(common.JOURNAL, backup)
        print("journal backup:", backup)

    # ---------- 阶段 A: 首次启动(旧 UUID journal 迁移) ----------
    proc = common.start_server(OUT / "live1-phaseA-server.log")
    try:
        status = http.call("GET", "/v1/status")["data"]
        common.write_json(OUT / "status-phaseA-before-observe.json", status)
        check("A1 body_id=bob(逻辑身份,不再是 UUID)", status.get("body_id") == "bob",
              f'body_id={status.get("body_id")}')
        check("A2 backend_kind=server_fake_player", status.get("backend_kind") == "server_fake_player",
              f'backend_kind={status.get("backend_kind")}')
        check("A3 body_instance_id=旧 profile UUID", status.get("body_instance_id") == OLD_UUID,
              f'body_instance_id={status.get("body_instance_id")}')
        epoch_a = status.get("body_session_epoch", "")
        check("A4 body_session_epoch 非空", bool(epoch_a), f"epoch={epoch_a}")
        check("A5 needs_reconcile=true(observe 之前)", status.get("needs_reconcile") is True)
        check("A6 observe 前 event_sequence 已推进(迁移帧已写)", int(status.get("event_sequence", 0)) > 989,
              f'event_sequence={status.get("event_sequence")}')

        graphs = http.call("GET", "/v1/graphs")["data"]
        graph_ids = [g.get("graph_id") or g.get("id") for g in (graphs.get("graphs") or [])]
        check("A7 历史图仍加载", len(graph_ids) >= 1, f"graphs={len(graph_ids)}")

        observe = http.call("GET", "/v1/observe")["data"]
        common.write_json(OUT / "observe-phaseA.json", observe)
        status2 = http.call("GET", "/v1/status")["data"]
        check("A8 observe 清除 needs_reconcile", status2.get("needs_reconcile") is False)

        lease = http.get_lease()
        submit = http.call("POST", "/v1/executions/say", lease,
                           json.dumps({"message": "2a03-live1-binding-check"}),
                           headers={"X-Request-Id": "live1-say-binding-check"})
        execution_id = submit["data"]["execution_id"]
        print("say execution:", execution_id)
        deadline = time.time() + 60
        receipt = None
        while time.time() < deadline:
            receipt = http.call("GET", f"/v1/executions/{execution_id}")["data"]
            if receipt.get("terminal"):
                break
            time.sleep(1)
        check("A9 say 到达终态 completed", receipt.get("state") == "completed",
              f'state={receipt.get("state")} reason={receipt.get("reason")}')
        check("A10 收据四绑定字段与 status 完全一致",
              receipt.get("body_id") == status2.get("body_id")
              and receipt.get("backend_kind") == status2.get("backend_kind")
              and receipt.get("body_instance_id") == status2.get("body_instance_id")
              and receipt.get("body_session_epoch") == status2.get("body_session_epoch"),
              json.dumps({k: receipt.get(k) for k in
                          ("body_id", "backend_kind", "body_instance_id", "body_session_epoch")}))
        common.write_json(OUT / "say-execution-phaseA.json", receipt)
    finally:
        common.stop_server(proc)

    frames = common.read_journal(common.JOURNAL)
    common.write_json(OUT / "journal-frames-phaseA.json", frames[-30:])
    bindings = [f for f in frames if f.get("kind") == "body_binding"]
    check("A11 新 body_binding 帧存在且四字段齐",
          any(f.get("body_id") == "bob" and f.get("backend_kind") == "server_fake_player"
              and f.get("body_instance_id") == OLD_UUID and f.get("body_session_epoch") == epoch_a
              for f in bindings),
          f"binding_frames={len(bindings)}")
    changed = [f for f in frames if f.get("kind") == "body_changed"]
    ok12 = False
    detail12 = f"body_changed_frames={len(changed)}"
    if changed:
        payload = json.loads(changed[-1].get("payload", "{}"))
        ok12 = (payload.get("previous", {}).get("body_id") == OLD_UUID
                and payload.get("current", {}).get("body_id") == "bob"
                and payload.get("current", {}).get("backend_kind") == "server_fake_player")
        detail12 += f" previous={payload.get('previous',{}).get('body_id')} current={payload.get('current',{}).get('body_id')}"
    check("A12 body_changed 事件帧记录旧 UUID 与新逻辑绑定", ok12, detail12)
    exec_frames = [f for f in frames if f.get("kind") == "execution"]
    say_frames = [f for f in exec_frames if f.get("state") == "completed"
                  and f.get("operation") == "say"]
    check("A13 say execution 帧含四绑定字段",
          any(f.get("body_id") == "bob" and f.get("backend_kind") == "server_fake_player"
              and f.get("body_instance_id") == OLD_UUID
              and f.get("body_session_epoch") == epoch_a for f in say_frames),
          f"say_frames={len(say_frames)}")

    # ---------- 阶段 B: 正常重启(session 轮换) ----------
    proc = common.start_server(OUT / "live1-phaseB-server.log")
    try:
        status_b = http.call("GET", "/v1/status")["data"]
        common.write_json(OUT / "status-phaseB-after-restart.json", status_b)
        epoch_b = status_b.get("body_session_epoch", "")
        check("B1 重启后 body_id 仍为 bob", status_b.get("body_id") == "bob")
        check("B2 重启后 body_session_epoch 轮换", epoch_b != epoch_a and bool(epoch_b),
              f"epoch_a={epoch_a} epoch_b={epoch_b}")
        check("B3 重启后 needs_reconcile=true", status_b.get("needs_reconcile") is True)
        check("B4 无旧物理执行被重放(status 无 active execution)",
              not status_b.get("execution") or not status_b.get("execution", {}).get("execution_id"),
              f'execution={status_b.get("execution")}')
    finally:
        common.stop_server(proc)

    frames_b = common.read_journal(common.JOURNAL)
    common.write_json(OUT / "journal-frames-phaseB.json", frames_b[-30:])
    sessions = [f for f in frames_b if f.get("kind") == "body_session_changed"]
    check("B5 body_session_changed 事件帧存在", len(sessions) >= 1,
          f"session_changed_frames={len(sessions)}")
    exec_after = [f for f in frames_b
                  if f.get("kind") == "execution" and f.get("_seq", 0) > frames[-1]["_seq"]
                  and f.get("operation")]
    check("B6 重启后无自动重放的 mutation(无新增 operation execution 帧)",
          len(exec_after) == 0, f"new_exec_frames={len(exec_after)}")

    result["ok"] = all(a["ok"] for a in result["asserts"])
    result["epoch_a"] = epoch_a
    result["epoch_b"] = epoch_b
    result["old_uuid"] = OLD_UUID
    common.write_json(OUT / "LIVE-1-result.json", result)
    print("LIVE-1", "PASS" if result["ok"] else "FAIL", f'({len(result["asserts"])} asserts)')


if __name__ == "__main__":
    main()
