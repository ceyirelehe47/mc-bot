# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 G5 技能驱动器:LLM 决策的机械执行层。

每个子命令执行一组语义操作并把 LLM 决策行(model_channel+note+
完整回执)追加到 g5r3-<run>.jsonl——与 rcf1_g5.py 的 act 行同格式,
judge_g5 同入口判定。决策由会话 LLM 下达;本层不含隐藏信息通道。
"""
import argparse
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_g5 as G  # noqa: E402
import rcf1_env as E  # noqa: E402

MODEL_CHANNEL = G.MODEL_CHANNEL


def _log_path(run_id):
    return G._log_path(run_id)


def act(run_id, s, op, args, note, timeout=180):
    ex, err = s.submit(op, args, tag="g5r3-%s" % run_id)
    if ex is None:
        receipt = {"state": "failed",
                   "reason": json.dumps(err, ensure_ascii=False)}
    else:
        receipt, _ = s.term(ex, timeout_s=timeout)
    G._append(run_id, "act", {
        "model_channel": MODEL_CHANNEL,
        "decision_note": note, "op": op, "args": args,
        "submit": {"execution_id": ex} if ex else {"error": str(err)[:120]},
        "receipt": receipt, "t_wall": round(time.time(), 3)})
    return receipt


def obs_inv(s):
    import rcf1_facts as F
    return F.inv_counts_of(F.observe_facts(s))


def mine_blocks(run_id, s, block_id, near, need, note, budget_s=300):
    """goto(face)→机会→mine 循环(南向站位;同 rcf1_g4 语义)。"""
    got = 0
    t0 = time.time()
    while got < need and time.time() - t0 < budget_s:
        loc = s.inspect_local(10, "all")
        if not loc.get("ok"):
            time.sleep(2)
            continue
        snap = ((loc.get("data") or {}).get("snapshot") or {})
        if isinstance(snap, str):
            snap = json.loads(snap)
        cands = []
        for b in snap.get("blocks") or []:
            if not str(b.get("block", "")).endswith(
                    block_id.split(":")[-1]):
                continue
            if not b.get("line_of_sight"):
                continue
            p = b.get("position") or {}
            xyz = (int(p.get("x", 0)), int(p.get("y", 0)),
                   int(p.get("z", 0)))
            if near and (abs(xyz[0] - near[0]) > 6
                         or abs(xyz[1] - near[1]) > 8
                         or abs(xyz[2] - near[2]) > 6):
                continue
            cands.append(xyz)
        cands.sort(key=lambda c: (-c[1], abs(c[0] - near[0])
                                  + abs(c[2] - near[2])))
        if not cands:
            time.sleep(2)
            continue
        tgt = cands[0]
        r = act(run_id, s, "goto", {
            "x": tgt[0], "y": max(tgt[1] - 2, 60), "z": tgt[2] - 3,
            "face_x": tgt[0], "face_y": tgt[1], "face_z": tgt[2]},
            "采%s@%s:南向站位面向目标" % (block_id, tgt), timeout=90)
        if r.get("state") != "completed":
            time.sleep(1)
            continue
        opp = None
        t1 = time.time()
        while time.time() - t1 < 12:
            loc2 = s.inspect_local(10, "summary")
            snap2 = ((loc2.get("data") or {}).get("snapshot") or {})
            if isinstance(snap2, str):
                snap2 = json.loads(snap2)
            cont = snap2.get("opportunities") or {}
            entries = (cont.get("entries") if isinstance(cont, dict)
                       else cont) or []
            found = [e for e in entries if isinstance(e, dict)
                     and e.get("block") == block_id
                     and (e.get("x"), e.get("y"), e.get("z")) == tgt]
            if found:
                opp = found[0]
                break
            time.sleep(0.8)
        if not opp:
            continue
        r = act(run_id, s, "mine_opportunity",
                {"id": opp.get("object_id")},
                "挖%s@%s(合法机会)" % (block_id, tgt), timeout=150)
        if r.get("state") == "completed":
            got += 1
    return got


def cmd_mine_wood(a):
    s = play.Session("g5r3-%s" % a.run_id)
    got = mine_blocks(a.run_id, s, a.block, tuple(a.near), a.need,
                      a.note)
    print(json.dumps({"mined": got}))


def cmd_craft(a):
    s = play.Session("g5r3-%s" % a.run_id)
    r = act(a.run_id, s, "craft",
            {"item": a.item, "count": a.count}, a.note, timeout=240)
    print(json.dumps({"state": r.get("state"),
                      "reason": str(r.get("reason"))[:100]}))


def cmd_act(a):
    s = play.Session("g5r3-%s" % a.run_id)
    r = act(a.run_id, s, a.op, json.loads(a.args), a.note,
            timeout=a.timeout)
    print(json.dumps({"state": r.get("state"),
                      "reason": str(r.get("reason"))[:150]}))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd")
    ap.add_argument("run_id")
    ap.add_argument("rest", nargs="*")
    ap.add_argument("--note", default="")
    ap.add_argument("--block", default="minecraft:oak_log")
    ap.add_argument("--near", nargs=3, type=int,
                    default=[0, 96, -70])
    ap.add_argument("--need", type=int, default=1)
    ap.add_argument("--item", default="")
    ap.add_argument("--count", type=int, default=1)
    ap.add_argument("--args", default="{}")
    ap.add_argument("--timeout", type=int, default=180)
    a = ap.parse_args()
    if a.cmd == "mine":
        return cmd_mine_wood(a)
    if a.cmd == "craft":
        return cmd_craft(a)
    if a.cmd == "act":
        return cmd_act(a)
    ap.error("unknown")


if __name__ == "__main__":
    sys.exit(main())
