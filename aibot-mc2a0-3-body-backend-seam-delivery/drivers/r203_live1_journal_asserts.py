# -*- coding: utf-8 -*-
"""LIVE-1 journal 离线断言: 对 LIVE-1 阶段A 服务器优雅停止后写下的 journal 帧验证 A11-A13。

journal 源: world_play.pre-mc2a03-play(并发会话在 LIVE-1 收尾后将含本轮证据的 world_play
整体改名备份, 内容字节即 LIVE-1 阶段A 服务器 21:16 优雅停止时的状态, sha256 归档如下)。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import r203_common as common

OLD_UUID = "faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b"
JOURNAL = Path(r"D:\code\mc-experiment\mc-server-mc1ca\world_play.pre-mc2a03-play\aibot\external-body-bob.journal")

result = {"gate": "LIVE-1-journal-offline", "asserts": []}


def check(name: str, ok: bool, detail: str = "") -> None:
    result["asserts"].append({"name": name, "ok": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + ("  " + detail if detail else ""))


def main() -> None:
    frames = common.read_journal(JOURNAL)
    digest = common.sha256(JOURNAL)
    common.write_json(common.OUT / "04-live-binding" / "journal-frames-phaseA.json", frames[-30:])
    print(f"journal frames={len(frames)} sha256={digest}")

    status = json.load(open(common.OUT / "04-live-binding" / "status-phaseA-before-observe.json",
                           encoding="utf-8"))
    epoch_a = status["body_session_epoch"]

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
        detail12 += f" previous={payload.get('previous', {}).get('body_id')} current={payload.get('current', {}).get('body_id')}"
    check("A12 body_changed 事件帧记录旧 UUID 与新逻辑绑定", ok12, detail12)

    exec_frames = [f for f in frames if f.get("kind") == "execution"]
    say_accepted = [f for f in exec_frames
                    if f.get("operation") == "say" and f.get("state") == "accepted"]
    say_ids = {f.get("execution_id") for f in say_accepted}
    say_done = [f for f in exec_frames
                if f.get("execution_id") in say_ids and f.get("state") == "completed"]
    check("A13 say execution 帧含四绑定字段且到 completed",
          any(f.get("body_id") == "bob" and f.get("backend_kind") == "server_fake_player"
              and f.get("body_instance_id") == OLD_UUID
              and f.get("body_session_epoch") == epoch_a for f in say_accepted)
          and len(say_done) >= 1,
          f"say_accepted={len(say_accepted)} say_completed={len(say_done)}")
    check("A14 journal 终帧为 runtime_stopped(优雅停止)",
          frames[-1].get("kind") == "runtime_stopped", f"last={frames[-1].get('kind')}")

    result["ok"] = all(a["ok"] for a in result["asserts"])
    result["journal_sha256"] = digest
    result["journal_frames"] = len(frames)
    result["epoch_a"] = epoch_a
    result["old_uuid"] = OLD_UUID
    common.write_json(common.OUT / "04-live-binding" / "LIVE-1-journal-offline-result.json", result)
    print("journal-offline", "PASS" if result["ok"] else "FAIL")


if __name__ == "__main__":
    main()
