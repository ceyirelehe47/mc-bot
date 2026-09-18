# -*- coding: utf-8 -*-
"""LIVE-1 阶段B: 正常重启后的 session 轮换(基于阶段A journal 的 b 服副本)。"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

OUT = common.OUT / "04-live-binding"
OLD_UUID = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b"

result = {"gate": "LIVE-1-phaseB", "asserts": []}


def check(name: str, ok: bool, detail: str = "") -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))
    if not ok:
        result["ok"] = False
        common.write_json(OUT / "LIVE-1-phaseB-result.json", result)
        raise SystemExit("phaseB failed: " + name)


def main() -> None:
    http = common.load_http()
    frames_before = common.read_journal(common.JOURNAL)
    epoch_a = json.load(open(OUT / "status-phaseA-before-observe.json", encoding="utf-8"))["body_session_epoch"]
    seq_before = frames_before[-1]["_seq"]

    proc = common.start_server(OUT / "live1-phaseB-server.log")
    try:
        status = http.call("GET", "/v1/status")["data"]
        common.write_json(OUT / "status-phaseB-after-restart.json", status)
        epoch_b = status.get("body_session_epoch", "")
        check("B1 重启后 body_id 仍为 bob", status.get("body_id") == "bob")
        check("B2 重启后 body_session_epoch 轮换", bool(epoch_b) and epoch_b != epoch_a,
              f"epoch_a={epoch_a} epoch_b={epoch_b}")
        check("B3 重启后 instance 仍为旧 profile UUID", status.get("body_instance_id") == OLD_UUID)
        check("B4 重启后 needs_reconcile=true", status.get("needs_reconcile") is True)
        execution = status.get("execution") or {}
        check("B5 无旧物理执行被重放(status 无 active execution)",
              not execution.get("execution_id"), f"execution={execution.get('execution_id', '')}")
        # runbook: 故意的 observe 清 reconcile 后, 新 request id 才可继续工作(此处只验证清除语义)
        http.call("GET", "/v1/observe")
        status2 = http.call("GET", "/v1/status")["data"]
        check("B6 observe 后 needs_reconcile 清除", status2.get("needs_reconcile") is False)
    finally:
        common.stop_server(proc)

    frames_after = common.read_journal(common.JOURNAL)
    common.write_json(OUT / "journal-frames-phaseB.json", frames_after[-20:])
    sessions = [f for f in frames_after if f.get("kind") == "body_session_changed"]
    check("B7 body_session_changed 事件帧存在", len(sessions) >= 1,
          f"session_changed_frames={len(sessions)}")
    new_bindings = [f for f in frames_after if f.get("kind") == "body_binding"
                    and f.get("_seq", 0) > seq_before]
    check("B8 新 body_binding 帧发布新 epoch",
          any(f.get("body_id") == "bob" and f.get("body_session_epoch") == epoch_b
              for f in new_bindings), f"new_bindings={len(new_bindings)}")
    new_exec = [f for f in frames_after if f.get("kind") == "execution"
                and f.get("_seq", 0) > seq_before and f.get("state") == "accepted"]
    check("B9 重启窗口无自动重放的 mutation(无新增 accepted execution 帧)",
          len(new_exec) == 0, f"new_accepted={len(new_exec)}")
    check("B10 journal 终帧为 runtime_stopped", frames_after[-1].get("kind") == "runtime_stopped")

    result["ok"] = all(a["ok"] for a in result["asserts"])
    result["epoch_a"] = epoch_a
    result["epoch_b"] = epoch_b
    common.write_json(OUT / "LIVE-1-phaseB-result.json", result)
    print("LIVE-1 phaseB", "PASS" if result["ok"] else "FAIL")


if __name__ == "__main__":
    main()
