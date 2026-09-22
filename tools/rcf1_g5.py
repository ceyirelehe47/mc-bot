# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 G5 自然过夜 harness(S01/S02):LLM 经同一语义接口驱动。

R3/F03 修复(对照审查 ff29078):
- 删除 armed 后 RCON 传送/给物/修墙等一切特权推进;armed 后本
  harness 结构上不含 rcon 调用(_ARMED 门卫强制)。
- 两处事先声明的自然场景(scenes 清单);准备期只允许:到场
  (tp 一次)、起始时间(time set day)、清包——不建洞、不摆方块。
- 避难事实由客户端可见感知 + 实际身体格推导(shelter_facts),
  inside_enclosure 不再可传入 true。
- 上层规划=会话 LLM:每个 act 行记录模型通道、决策说明、观察、
  完整回执——固定 Python 剧本只能算技能,不算 S 组。

用法(LLM 逐步驱动):
  python tools/rcf1_g5.py prep s01 <scene_key>
  python tools/rcf1_g5.py obs s01
  python tools/rcf1_g5.py shelter-facts s01
  python tools/rcf1_g5.py act s01 <op> '<json>' --note "决策理由"
  python tools/rcf1_g5.py dusk-baseline s01
  python tools/rcf1_g5.py night s01
  python tools/rcf1_g5.py verify-morning s01
"""
import argparse
import json
import sys
import time

sys.path.insert(0, "tools")
import play  # noqa: E402
import rcf1_env as E  # noqa: E402
import rcf1_facts as F  # noqa: E402
import rcf1_shelter as SH  # noqa: E402

RAW = r"D:\mc-rcf1-raw"
SCENES_PATH = r"D:\code\mc-bot\tools\rcf1_g5_scenes.json"
MODEL_CHANNEL = "zhipu-coding-plan/glm-5.3@omp-session"

_ARMED = {"v": False}


def _rcon_gated(cmd):
    """armed 后 harness 层面禁止任何 rcon(结构性隔离)。"""
    if _ARMED["v"]:
        raise RuntimeError("rcon_forbidden_after_armed")
    return (E.rcon(cmd) or "").strip()


def _log_path(run_id):
    import pathlib
    pathlib.Path(RAW).mkdir(parents=True, exist_ok=True)
    return r"%s\g5r3-%s.jsonl" % (RAW, run_id)


def _append(run_id, kind, data):
    row = {"t_wall": round(time.time(), 3), "kind": kind, "data": data}
    with open(_log_path(run_id), "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    return row


def _tail_state(run_id):
    """armed 标记与场景,从日志尾恢复(跨进程延续)。"""
    armed = False
    scene = None
    try:
        with open(_log_path(run_id), encoding="utf-8") as fh:
            for line in fh:
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if row.get("kind") == "armed":
                    armed = True
                    scene = row["data"].get("scene")
                if row.get("kind") == "prep":
                    scene = scene or row["data"].get("scene")
    except FileNotFoundError:
        pass
    return armed, scene


def cmd_prep(run_id, scene_key):
    if _ARMED["v"] or _tail_state(run_id)[0]:
        raise SystemExit("already armed: preparation refused")
    scenes = json.load(open(SCENES_PATH, encoding="utf-8"))
    scene = scenes[scene_key]
    # 准备期(armed 前)仅:到场、起始时间、清包;不触碰地形。
    cmds = ["tp Bob %s" % scene["tp"], "time set day", "clear Bob"]
    receipts = []
    for c in cmds:
        receipts.append({"cmd_sha": __import__("hashlib").sha256(
            c.encode()).hexdigest()[:12], "out": _rcon_gated(c)[:80]})
    time.sleep(3)
    s = play.Session("g5r3-%s" % run_id)
    obs = F.observe_facts(s)
    _append(run_id, "prep", {
        "scene": scene_key, "scene_decl": scene, "cmds": receipts,
        "observe": obs})
    if obs.get("inventory"):
        _append(run_id, "prep-fail", {"reason": "initial-not-empty",
                                       "inv": obs.get("inventory")})
        print(json.dumps({"prep": "FAIL", "reason": "initial-not-empty"}))
        return 1
    _append(run_id, "armed", {"scene": scene_key})
    print(json.dumps({"prep": "ok", "scene": scene_key,
                      "game_session": obs.get("game_session"),
                      "client_jar": obs.get("client_mod_jar_sha256")}))
    return 0


def cmd_obs(run_id):
    s = play.Session("g5r3-%s" % run_id)
    obs = F.observe_facts(s)
    phase = s.day_phase()
    row = _append(run_id, "obs", {"observe": obs, "day_phase": phase})
    print(json.dumps({"pos": obs.get("position"), "inv": obs.get(
        "inventory"), "health": obs.get("health"),
        "phase": phase, "screen_cursor": (obs.get("screen") or {}).get(
            "cursor_count", None)}, ensure_ascii=False))
    return 0


def cmd_shelter_facts(run_id):
    s = play.Session("g5r3-%s" % run_id)
    obs = F.observe_facts(s)
    pos = obs.get("position") or {}
    bx, by, bz = (int(pos.get("x", 0)), int(pos.get("y", 0)),
                  int(pos.get("z", 0)))
    loc = s.inspect_local(3, "all")
    snap = ((loc.get("data") or {}).get("snapshot") or {})
    if isinstance(snap, str):
        snap = json.loads(snap)
    blocks = snap.get("blocks") or []
    seen = {}
    for b in blocks:
        p = b.get("position") or {}
        seen[(int(p.get("x", 0)), int(p.get("y", 0)),
              int(p.get("z", 0)))] = b.get("block")
    def at(x, y, z):
        return seen.get((x, y, z))
    dirs = {"N": (0, -1), "S": (0, 1), "E": (1, 0), "W": (-1, 0)}
    sides = {d: at(bx + dx, by, bz + dz) for d, (dx, dz) in dirs.items()}
    above = at(bx, by + 2, bz)
    facts = {"schema": "mc.rcf1r3.shelter.v1",
             "body_cell": [bx, by, bz], "above": above, "sides": sides}
    verdict = SH.assess_sheltered(facts)
    plan = SH.plan_shelter_action(facts)
    _append(run_id, "shelter-facts", {
        "facts": facts, "verdict": verdict, "plan": plan,
        "perceived_blocks": len(blocks)})
    print(json.dumps({"body": [bx, by, bz],
                      "above": above, "sides": sides,
                      "verdict": verdict, "plan": plan},
                     ensure_ascii=False))
    return 0


def cmd_act(run_id, op, args_json, note):
    if not _tail_state(run_id)[0]:
        raise SystemExit("not armed: act refused (run prep first)")
    args = json.loads(args_json)
    s = play.Session("g5r3-%s" % run_id)
    pre = F.observe_facts(s)
    t0 = time.time()
    ex_id, err = s.submit(op, args, tag="g5r3-%s" % run_id)
    if ex_id is None:
        receipt = {"state": "failed",
                   "reason": json.dumps(err, ensure_ascii=False)}
        dur = round(time.time() - t0, 2)
    else:
        receipt, _trail = s.term(ex_id, timeout_s=240)
        dur = round(time.time() - t0, 2)
    post = F.observe_facts(s)
    _append(run_id, "act", {
        "model_channel": MODEL_CHANNEL,
        "decision_note": note, "op": op, "args": args,
        "submit": F._strip_secrets(err if ex_id is None
                                   else {"execution_id": ex_id}),
        "receipt": F._strip_secrets(receipt),
        "duration_s": dur,
        "pre_pos": pre.get("position"), "post_pos": post.get("position"),
        "pre_inv": pre.get("inventory"), "post_inv": post.get("inventory"),
        "game_session": post.get("game_session")})
    print(json.dumps({"op": op,
                      "state": receipt.get("state"),
                      "reason": str(receipt.get("reason") or "")[:220],
                      "duration_s": dur}, ensure_ascii=False))
    return 0


def cmd_dusk_baseline(run_id):
    s = play.Session("g5r3-%s" % run_id)
    obs = F.observe_facts(s)
    phase = s.day_phase()
    _append(run_id, "dusk-baseline", {"observe": obs, "day_phase": phase})
    print(json.dumps({"phase": phase, "inv": obs.get("inventory"),
                      "health": obs.get("health"),
                      "pos": obs.get("position")}, ensure_ascii=False))
    return 0


def cmd_night(run_id, max_s=2700):
    """在线过夜观察循环:确定性记录,无业务决策。死亡/失联/重生
    均如实记录(不中断录制);清晨相位连续出现才退出。"""
    if not _tail_state(run_id)[0]:
        raise SystemExit("not armed")
    s = play.Session("g5r3-%s" % run_id)
    t0 = time.time()
    anchor = None
    dawn_streak = 0
    while time.time() - t0 < max_s:
        try:
            obs = F.observe_facts(s)
            phase = s.day_phase()
        except Exception as exc:  # noqa: BLE001
            _append(run_id, "night-obs-error", {"error": repr(exc)[:200]})
            time.sleep(10)
            continue
        pos = obs.get("position") or {}
        row = {"phase": phase, "health": obs.get("health"),
               "pos": pos, "food": obs.get("food"),
               "elapsed": round(time.time() - t0, 1)}
        _append(run_id, "night", row)
        if anchor is None:
            anchor = (pos.get("x"), pos.get("z"))
        if obs.get("health") is not None and obs["health"] <= 0:
            _append(run_id, "night-death", row)
        if anchor and (abs((pos.get("x") or 0) - anchor[0]) > 24
                       or abs((pos.get("z") or 0) - anchor[1]) > 24):
            _append(run_id, "night-position-lost", row)
        if str(phase).lower() in SH.DAWN_PHASES:
            dawn_streak += 1
            if dawn_streak >= 3:
                _append(run_id, "night-end", {"dawn": True,
                                              "elapsed": row["elapsed"]})
                print(json.dumps({"night": "dawn", "elapsed":
                                  row["elapsed"]}))
                return 0
        else:
            dawn_streak = 0
        time.sleep(20)
    _append(run_id, "night-end", {"dawn": False})
    print(json.dumps({"night": "timeout"}))
    return 1


def cmd_verify_morning(run_id):
    s = play.Session("g5r3-%s" % run_id)
    obs = F.observe_facts(s)
    inv = F.inv_counts_of(obs)
    tools_ok = SH.morning_progress_ok(inv)
    facts = {"inv": inv, "tools_ok": tools_ok,
             "pos": obs.get("position"), "health": obs.get("health")}
    _append(run_id, "verify-morning", facts)
    print(json.dumps(facts, ensure_ascii=False))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd")
    ap.add_argument("run_id")
    ap.add_argument("rest", nargs="*")
    ap.add_argument("--note", default="")
    a = ap.parse_args()
    if a.cmd == "prep":
        return cmd_prep(a.run_id, a.rest[0])
    if a.cmd == "obs":
        return cmd_obs(a.run_id)
    if a.cmd == "shelter-facts":
        return cmd_shelter_facts(a.run_id)
    if a.cmd == "act":
        return cmd_act(a.run_id, a.rest[0], a.rest[1], a.note)
    if a.cmd == "dusk-baseline":
        return cmd_dusk_baseline(a.run_id)
    if a.cmd == "night":
        return cmd_night(a.run_id)
    if a.cmd == "verify-morning":
        return cmd_verify_morning(a.run_id)
    ap.error("unknown cmd")


if __name__ == "__main__":
    sys.exit(main())
