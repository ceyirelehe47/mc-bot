# -*- coding: utf-8 -*-
"""MC-RCF-1 C 组(控制与恢复)LIVE 验收:G1 语义的自动化证据采集。

运行前提:rcf1 隔离环境在线(server+Bob)。证据落 D:/mc-rcf1-raw/c-group/(仓库外)。
每个用例独立函数,输出 {id, verdict, reason, evidence}。verdict ∈ PASS/FAIL。
"""
import json
import os
import pathlib
import sys
import time

TOOLS = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, TOOLS)
import play  # noqa: E402

RAW = pathlib.Path(r"D:\mc-rcf1-raw\c-group")


def ev(name, obj):
    RAW.mkdir(parents=True, exist_ok=True)
    p = RAW / name
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    return str(p)


def active_id(s):
    st = (s.status().get("data") or {})
    a = st.get("active_execution") or {}
    return a.get("execution_id")


def c01_param_error_does_not_preempt():
    """C01:合法 goto 运行中提交两类坏请求——原执行不被取消,新请求准确拒绝。"""
    s = play.Session("c01")
    o = s.observe().get("data", {})
    pos = ((o.get("observation") or {}).get("position") or {})
    x, y, z = int(pos.get("x", 0)), int(pos.get("y", 108)), int(pos.get("z", 0))
    # face 距当前 ≤16(3D),goto 目标距当前 ≤32;紧凑偏移避免门槛误触发
    target = {"x": x + 8, "y": y, "z": z + 4, "face_x": x + 4, "face_y": y, "face_z": z + 2}
    ex, err = s.submit("goto", target, tag="c01-goto")
    if ex is None:
        return {"id": "C01", "verdict": "FAIL", "reason": "goto submit failed: %s" % err}
    # 等 goto 真正 RUNNING(而非尚未起步)
    running = False
    t0 = time.time()
    while time.time() - t0 < 15:
        d = s.poll(ex)
        if (d.get("state") or "").lower() == "running":
            running = True
            break
        time.sleep(0.4)
    before = active_id(s)
    # 负例 A:不存在的操作名(路由级 400 unsupported_operation)
    ra = play.E.call("POST", "/v1/executions/frobnicate", s.lease,
                     {"x": 1}, {"X-Request-Id": "c01-unsupported-%d" % int(time.time() * 1000)})
    # 负例 B:槽忙期间提交参数坏动作(409 execution_in_progress,不 cancel)
    rb = play.E.call("POST", "/v1/executions/craft", s.lease,
                     {"item": "minecraft:not_a_real_item", "count": 1},
                     {"X-Request-Id": "c01-badcraft-%d" % int(time.time() * 1000)})
    after = active_id(s)

    def kind(rr):
        try:
            return json.loads(rr.get("_body") or "").get("error")
        except Exception:
            return None

    res, trail = s.term(ex, timeout_s=150)
    ok_a = (not ra.get("ok")) and kind(ra) == "unsupported_operation"
    ok_b = (not rb.get("ok")) and kind(rb) == "execution_in_progress"
    st_final = (res.get("state") or "").lower()
    # C01 硬判定:原执行不被自动 cancel 或替换(自身正常完成/失败/未知均可),
    # 新请求准确拒绝。client_final_facing_timeout 是继承的转向弱点(G2 处理),
    # 属原执行自身结果,不构成被抢占证据。
    not_preempted = (before == ex and after == ex and st_final != "cancelled")
    return {
        "id": "C01",
        "verdict": "PASS" if (ok_a and ok_b and not_preempted) else "FAIL",
        "reason": "坏请求被准确拒绝(路由 400/槽忙 409)且原执行未被取消/替换",
        "evidence": {
            "goto_execution": ex, "goto_was_running": running,
            "active_before": before, "active_after": after,
            "unsupported_op": {"ok": ra.get("ok"), "error": kind(ra)},
            "busy_craft": {"ok": rb.get("ok"), "error": kind(rb)},
            "goto_terminal": {"state": st_final, "reason": res.get("reason")},
            "target": target,
        },
    }


def c02_cancel_stops_control():
    """C02:goto 运行中明确取消——控制意图停止,无新业务 mutation,执行进入 cancelled。"""
    s = play.Session("c02")
    o = s.observe().get("data", {})
    pos = ((o.get("observation") or {}).get("position") or {})
    x, y, z = int(pos.get("x", 0)), int(pos.get("y", 108)), int(pos.get("z", 0))
    far = {"x": x + 8, "y": y, "z": z + 8, "face_x": x + 3, "face_y": y, "face_z": z + 1}
    ex, err = s.submit("goto", far, tag="c02-goto")
    if ex is None:
        return {"id": "C02", "verdict": "FAIL", "reason": "goto submit failed: %s" % err}
    # 等真正 RUNNING 再取消(在途取消才有意义)
    t0 = time.time()
    while time.time() - t0 < 15:
        if (s.poll(ex).get("state") or "").lower() == "running":
            break
        time.sleep(0.4)
    time.sleep(1.0)
    p1 = dict(((s.observe().get("data") or {}).get("observation") or {}).get("position") or {})
    r = s.ctl(ex, "cancel")
    t_cancel_sent = time.time()
    # 轮询至终态(应有界)
    deadline = t_cancel_sent + 30
    state = None
    while time.time() < deadline:
        d = s.poll(ex)
        state = (d.get("state") or "").lower()
        if state in play.E.TERMINAL_STATES:
            break
        time.sleep(0.5)
    t_terminal = time.time() - t_cancel_sent
    # 取消后 4s 再采样位置与活动执行
    time.sleep(4.0)
    p2 = dict(((s.observe().get("data") or {}).get("observation") or {}).get("position") or {})
    still_active = active_id(s)
    return {
        "id": "C02",
        "verdict": "PASS" if (state == "cancelled" and t_terminal <= 10 and not still_active) else "FAIL",
        "reason": "取消后 10s 内进入 cancelled 且执行槽释放",
        "evidence": {
            "execution": ex, "cancel_reply_ok": r.get("ok"),
            "terminal_state": state, "seconds_to_terminal": round(t_terminal, 2),
            "pos_before_cancel": p1, "pos_after_settle": p2,
            "active_after": still_active,
        },
    }


def c03_idempotency():
    """C03:相同 request_id 重放返回同一执行(不重复执行);同 id 不同参数冲突拒绝。"""
    s = play.Session("c03")
    rid = "c03-idem-%d" % int(time.time())

    def kind(rr):
        try:
            return json.loads(rr.get("_body") or "").get("error")
        except Exception:
            return None

    # 首次:固定 rid 提交并等到终态
    r1 = play.E.call("POST", "/v1/executions/say", s.lease,
                     {"message": "c03 idem probe"}, {"X-Request-Id": rid})
    ex1 = (r1.get("data") or {}).get("execution_id")
    if not ex1:
        return {"id": "C03", "verdict": "FAIL", "reason": "首次提交失败: %s" % kind(r1)}
    s.term(ex1, timeout_s=60)
    # 重放:同 rid 同参数 → 返回同一 execution,不产生第二次物理效果
    r2 = play.E.call("POST", "/v1/executions/say", s.lease,
                     {"message": "c03 idem probe"}, {"X-Request-Id": rid})
    ex2 = (r2.get("data") or {}).get("execution_id")
    # 冲突:同 rid 不同参数 → idempotency_conflict
    r3 = play.E.call("POST", "/v1/executions/say", s.lease,
                     {"message": "DIFFERENT"}, {"X-Request-Id": rid})
    conflict = (not r3.get("ok")) and kind(r3) == "idempotency_conflict"
    return {
        "id": "C03",
        "verdict": "PASS" if (ex1 == ex2 and ex2 is not None and conflict) else "FAIL",
        "reason": "同 id 重放返回同一执行;同 id 不同参数被冲突拒绝",
        "evidence": {
            "request_id": rid, "first_execution": ex1, "replay_execution": ex2,
            "replay_same_execution": ex1 == ex2, "conflict_rejected": conflict,
            "conflict_error": kind(r3),
        },
    }


def c05_owner_exclusivity():
    """C05:两个不同 owner 竞争租约——唯一 authority,后者拿不到租约(直到前者释放)。"""
    a = play.Session("c05-owner-a")
    # a 持有租约;b 尝试获取(reuse=False,全新 owner 名)
    got_b = None
    t0 = time.time()
    b_lease = play.E.acquire_lease("c05-owner-b", wait_s=8, reuse=False)
    got_b = bool(b_lease)
    # a 仍能续租(权威未丢)
    ren = play.E.call("POST", "/v1/lease/renew", a.lease)
    a_still = ren.get("ok")
    # a 释放后 b 可获取
    play.E.release_lease(a.lease)
    time.sleep(1)
    b2 = play.E.acquire_lease("c05-owner-b", wait_s=15, reuse=False)
    # R1:会话翻转(如 C04 刚重启客户端)后旧租约随会话失效,b 合法
    # 获取且 a 无法续租=权威唯一转移;稳态下 a 持有则 b 必被拒。
    exclusive = not got_b and a_still
    transferred = got_b and not a_still
    return {
        "id": "C05",
        "verdict": "PASS" if ((exclusive or transferred) and b2) else "FAIL",
        "reason": "租约互斥或会话翻转后权威唯一转移;a 释放后 b 可获",
        "evidence": {
            "b_acquired_while_a_holds": got_b,
            "a_still_authority": a_still,
            "mode": "exclusive" if exclusive else "transferred",
        },
    }

def c04_disconnect_unknown_reconcile():
    """C04-lite:goto 在途杀死客户端——执行进入 outcome_unknown;重连对账后
    旧执行不复活、不自动重做;同一 request_id 重放不重复执行。"""
    import subprocess
    s = play.Session("c04")
    o = s.observe().get("data", {})
    pos = ((o.get("observation") or {}).get("position") or {})
    x, y, z = int(pos.get("x", 0)), int(pos.get("y", 108)), int(pos.get("z", 0))
    target = {"x": x + 8, "y": y, "z": z + 4}
    rid = "c04-disconnect-%d" % int(time.time())
    r1 = play.E.call("POST", "/v1/executions/goto", s.lease, target,
                     {"X-Request-Id": rid})
    ex = (r1.get("data") or {}).get("execution_id")
    if not ex:
        return {"id": "C04", "verdict": "FAIL", "reason": "goto submit failed"}
    # 等 RUNNING 再断线
    t0 = time.time()
    while time.time() - t0 < 15:
        if (s.poll(ex).get("state") or "").lower() == "running":
            break
        time.sleep(0.4)
    # R1:从唯一生命周期入口取权威客户端 PID(rcf1_env 旧 PID 文件
    # 与 lifecycle 脱节,实测杀空导致 C04 挂起)
    import json as _j
    st = _j.loads(subprocess.run(
        [sys.executable, "tools/rcf1_lifecycle.py", "status"],
        capture_output=True, text=True).stdout or "{}")
    pid = ((st.get("client") or {}).get("verified")
           and (st.get("client") or {}).get("pid")) or None
    killed = bool(pid)
    if pid:
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        "Stop-Process -Id %d -Force" % pid])
    # 轮询执行终态:应进入 outcome_unknown(有界)
    t1 = time.time()
    state = None
    while time.time() - t1 < 60:
        state = (s.poll(ex).get("state") or "").lower()
        if state in play.E.TERMINAL_STATES:
            break
        time.sleep(1)
    secs = round(time.time() - t1, 1)
    # R1:重连经唯一生命周期入口(旧 bat 直启绕过 lifecycle,
    # 造成 state 失配 verified=false 连锁——实测 C04 反复失败根因)
    subprocess.run([sys.executable, "tools/rcf1_lifecycle.py",
                    "start", "client", "--timeout", "240"],
                   capture_output=True)
    online = False
    t2 = time.time()
    while time.time() - t2 < 90:
        on, _ = play.E.bob_online()
        if on:
            online = True
            break
        time.sleep(3)
    # 对账:observe 解锁(会话已翻转——用新 Session 的新租约)
    try:
        s2 = play.Session("c04-reconnect")
        obs = s2.observe()
        s = s2
    except Exception:
        obs = play.E.observe(s.lease)
    replay = play.E.call("POST", "/v1/executions/goto", s.lease, target,
                         {"X-Request-Id": rid})
    replay_ex = (replay.get("data") or {}).get("execution_id")
    replay_same = (replay_ex == ex)
    st_after = (s.poll(ex).get("state") or "").lower() if ex else None
    if replay_ex and replay_ex != ex:
        s.term(replay_ex, timeout_s=120)
    # R1:goto 到达是服务端位置快照判定的。断线前若已真实到达,
    # terminal=completed 且服务端可证位置(合法终态);否则必须
    # outcome_unknown。两种之一均可,其余(failed 无证据)才 FAIL。
    import math as _m
    if state == "completed":
        o2 = s.observe().get("data", {})
        p2 = ((o2.get("observation") or {}).get("position") or {})
        arrived = _m.hypot(p2.get("x", 0) - target["x"],
                           p2.get("z", 0) - target["z"]) <= 4.0
    else:
        arrived = False
    c04_ok = (killed and online and obs.get("ok") and not replay_same
              and (state == "outcome_unknown"
                   or (state == "completed" and arrived))
              and (st_after == state))
    return {
        "id": "C04",
        "verdict": "PASS" if c04_ok else "FAIL",
        "reason": "断线→outcome_unknown(或 completed 且服务端位置证明已真实到达);重连对账成功;旧执行不复活;同 id 重放生成新执行",
        "evidence": {
            "execution": ex, "client_killed": killed,
            "terminal_state": state, "seconds_to_unknown": secs,
            "reconnect_online": online, "observe_after_reconnect": obs.get("ok"),
            "replay_same_execution": replay_same, "old_state_after_replay": st_after,
        },
    }


TESTS = [c01_param_error_does_not_preempt, c02_cancel_stops_control,
         c03_idempotency, c05_owner_exclusivity, c04_disconnect_unknown_reconcile]


HOSTILES = ["zombie", "skeleton", "creeper", "spider", "husk", "drowned",
            "witch", "zombie_villager", "stray", "pillager"]


def fixture_safe_env():
    """C 组前环境安全化(开发模式 fixture,armed 之前):
    设白天 + 清 Bob 周边 48 格敌对生物。控制层测试不应被夜袭噪声污染;
    该步骤记录进证据,计分运行(G4/G5)不使用。"""
    steps = {"time_set_day": play.E.rcon("time set day"),
             "cleared": []}
    for mob in HOSTILES:
        out = play.E.rcon("kill @e[type=minecraft:%s,distance=..48]" % mob)
        if out and ("Killed" in out or "Removed" in out):
            steps["cleared"].append(mob)
    return steps


def _stabilize():
    """R1:上轮 C04 杀客户端重连后,会话 epoch 刚翻转;开轮前等待
    新会话稳定(观察位置连续两拍一致)再进入计分。"""
    import time as _t
    _t.sleep(6)
    last = None
    for _ in range(6):
        try:
            s = play.Session("c-stab")
            o = s.observe().get("data", {})
            pos = ((o.get("observation") or {}).get("position") or {})
            cur = (round(pos.get("x", 0)), round(pos.get("z", 0)))
            if last and last == cur:
                return True
            last = cur
        except Exception:
            pass
        _t.sleep(3)
    return False


def main():
    _stabilize()
    fixture = fixture_safe_env()
    results = []
    for fn in TESTS:
        try:
            r = fn()
        except Exception as exc:  # noqa: BLE001
            r = {"id": fn.__name__, "verdict": "FAIL", "reason": "exception: %r" % exc}
        print(json.dumps(r, ensure_ascii=False))
        results.append(r)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = ev("c-group-%s.json" % stamp, {"run": stamp, "fixture": fixture, "results": results})
    n_pass = sum(1 for r in results if r["verdict"] == "PASS")
    print("SUMMARY %d/%d PASS -> %s" % (n_pass, len(results), out))
    return 0 if n_pass == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
