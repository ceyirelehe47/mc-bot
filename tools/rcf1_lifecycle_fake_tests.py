# -*- coding: utf-8 -*-
"""R1-L L01-L10 假进程验收:独立命名空间 + 真 OS 进程并发。

运行:python tools/rcf1_lifecycle_fake_tests.py
绝不触碰真实生命周期状态/预算/进程/凭证:全部经 RCF1_LIFECYCLE_NS_ROOT
重定向到本测试的临时目录;真实后端从不拉起 Minecraft。
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

HERE = pathlib.Path(__file__).parent.absolute()
TOOL = str(HERE / "rcf1_lifecycle.py")
PY = sys.executable

REAL_NS = pathlib.Path(r"D:\mc-rcf1-raw\lifecycle")


def _ns_env(ns):
    e = dict(os.environ)
    e["RCF1_LIFECYCLE_NS_ROOT"] = str(ns)
    e["RCF1_LIFECYCLE_FAKE"] = "1"
    return e


def _cli(env, *args, timeout=180):
    return subprocess.run([PY, TOOL, *args], env=env,
                          capture_output=True, text=True, timeout=timeout)


def _count_live(env, role):
    """数命名空间内活实例(经 status 的 marker 扫描)。"""
    st = json.loads(_cli(env, "status").stdout)
    rec = st.get(role) or {}
    return (1 if rec.get("verified") else 0), st


def _mk_ns():
    ns = pathlib.Path(tempfile.mkdtemp(prefix="rcf1-ltest-"))
    return ns


def _fake_prefix(ns):
    """与 rcf1_lifecycle._marker_prefix 同算法:假进程 marker 前缀
    由 NS_ROOT 派生,跨测试命名空间互不可见。"""
    import hashlib
    token = hashlib.sha1(
        str(ns.resolve()).lower().encode("utf-8")).hexdigest()[:8]
    return "rcf1fake%s-" % token


RESULTS = []

def check(tid, ok, detail=""):
    RESULTS.append((tid, bool(ok), str(detail)[:300]))
    print(json.dumps({"id": tid, "pass": bool(ok), "detail": str(detail)[:300]},
                     ensure_ascii=False), flush=True)



def _cleanup_fake_orphans():
    """套件级清理:杀本测试脚本拉起的遗留假进程(精确命令行匹配)。"""
    import json as _j
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" "
         "| Select-Object ProcessId,CommandLine | ConvertTo-Json"],
        capture_output=True, text=True).stdout
    try:
        rows = _j.loads(out) if out.strip() else []
    except _j.JSONDecodeError:
        rows = []
    if isinstance(rows, dict):
        rows = [rows]
    for r in rows:
        cl = r.get("CommandLine") or ""
        if "_rcf1_fake_proc" in cl and "rcf1.instance.marker=rcf1fake" in cl:
            subprocess.run(["powershell", "-NoProfile", "-Command",
                            "Stop-Process -Id %d -Force" % r["ProcessId"]],
                           capture_output=True)


def l01_concurrent():
    ns = _mk_ns()
    env = _ns_env(ns)
    # 4 个 OS 进程并发 start(真跨进程)
    procs = [subprocess.Popen([PY, TOOL, "start", "server", "--timeout", "60"],
                              env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True)
             for _ in range(4)]
    outs = [p.communicate(timeout=180)[0] for p in procs]
    codes = [p.returncode for p in procs]
    n, st = _count_live(env, "server")
    check("L01", n == 1 and all(c == 0 for c in codes),
          {"live": n, "codes": codes, "outs": [o.strip()[:120] for o in outs]})
    # 连续 start:幂等同一实例
    r2 = _cli(env, "start", "server")
    n2, _ = _count_live(env, "server")
    check("L01b", r2.returncode == 0 and n2 == 1, r2.stdout.strip()[:120])
    _cli(env, "stop", "server", "--timeout", "6")
    shutil.rmtree(ns, ignore_errors=True)


def l02_slow_start():
    ns = _mk_ns()
    env = _ns_env(ns)
    env["RCF1_FAKE_READY_S"] = "6"
    # 慢启动:start 阻塞等 ready 期间并发 start
    p1 = subprocess.Popen([PY, TOOL, "start", "server", "--timeout", "90"],
                          env=env, stdout=subprocess.PIPE, text=True)
    time.sleep(2.5)
    r2 = _cli(env, "start", "server", timeout=90)
    o1 = p1.communicate(timeout=120)[0]
    n, st = _count_live(env, "server")
    check("L02", n == 1 and p1.returncode == 0,
          {"live": n, "first": o1.strip()[:120], "second": r2.stdout.strip()[:120],
           "second_code": r2.returncode})
    _cli(env, "stop", "server", "--timeout", "6")
    shutil.rmtree(ns, ignore_errors=True)


def l03_crash_windows():
    # 场景A:意图已写、进程已拉起、state 未写(管理器 spawn 后崩溃)
    ns = _mk_ns()
    env = _ns_env(ns)
    ns.joinpath("intents").mkdir(parents=True, exist_ok=True)
    marker = _fake_prefix(ns) + "server-abc123deadbeef"
    proc = subprocess.Popen(
        [PY, str(HERE / "_rcf1_fake_proc.py"), "--role", "server",
         "-Drcf1.instance.marker=%s" % marker],
        env={k: v for k, v in os.environ.items()
             if not k.startswith("RCF1_FAKE")},
        stdout=subprocess.DEVNULL)
    (ns / "intents" / "server.json").write_text(json.dumps(
        {"marker": marker, "cmd": [], "ts": time.time()}), encoding="utf-8")
    time.sleep(3)  # CIM CommandLine 注册延迟(实测 1s 不够,scan 看不到)
    r = _cli(env, "start", "server", timeout=60)
    n, st = _count_live(env, "server")
    adopted = (st.get("server") or {}).get("status") in ("ADOPTED", "RUNNING")
    # 后继必须接管原实例,不创建第二个:live==1 且 pid==原 pid
    check("L03a", n == 1 and adopted and st["server"].get("pid") == proc.pid,
          {"live": n, "status": (st.get("server") or {}).get("status"),
           "pid": st.get("server", {}).get("pid"), "orig": proc.pid,
           "rc": r.returncode, "out": r.stdout.strip()[:150]})
    _cli(env, "stop", "server", "--timeout", "6")
    # 场景B:两个未登记实例 → 冲突阻断,不选不杀
    ns2 = _mk_ns()
    env2 = _ns_env(ns2)
    pfx = _fake_prefix(ns2)
    ps = []
    for m in (pfx + "server-1111111111111111", pfx + "server-2222222222222222"):
        ps.append(subprocess.Popen(
            [PY, str(HERE / "_rcf1_fake_proc.py"), "--role", "server",
             "-Drcf1.instance.marker=%s" % m],
            env={k: v for k, v in os.environ.items()
                 if not k.startswith("RCF1_FAKE")},
            stdout=subprocess.DEVNULL))
    time.sleep(3)  # 同上:CIM 延迟
    r2 = _cli(env2, "start", "server", timeout=30)
    n2, st2 = _count_live(env2, "server")
    check("L03b", r2.returncode != 0 and n2 == 0 and "conflict" in (r2.stderr + r2.stdout).lower(),
          {"rc": r2.returncode, "live_verified": n2,
           "err": (r2.stderr or r2.stdout).strip()[:150],
           "procs_alive": sum(p.poll() is None for p in ps)})
    for p in ps:
        p.kill()
    shutil.rmtree(ns, ignore_errors=True)
    shutil.rmtree(ns2, ignore_errors=True)


def l04_corrupt_state():
    ns = _mk_ns()
    env = _ns_env(ns)
    ns.mkdir(parents=True, exist_ok=True)
    for payload, tag in ((b"{trunc", "truncated"),
                          (b"", "empty"),
                          (b"not json at all", "garbage")):
        (ns / "state.json").write_bytes(payload)
        r = _cli(env, "start", "server", timeout=20)
        ok = r.returncode != 0 and "corrupt" in (r.stderr + r.stdout).lower()
        check("L04-" + tag, ok, (r.stderr or r.stdout).strip()[:150])
    # 截断的失败计数
    (ns / "state.json").unlink()
    (ns / "failures.json").write_bytes(b"{half")
    r = _cli(env, "start", "server", timeout=20)
    check("L04-failfile", r.returncode != 0,
          (r.stderr or r.stdout).strip()[:150])
    shutil.rmtree(ns, ignore_errors=True)


def l05_enum_fail():
    ns = _mk_ns()
    env = _ns_env(ns)
    env["RCF1_LIFECYCLE_CIM_FAIL"] = "1"
    r = _cli(env, "start", "server", timeout=20)
    check("L05", r.returncode != 0 and "enumeration" in (r.stderr + r.stdout).lower(),
          (r.stderr or r.stdout).strip()[:150])
    # 陌生端口占用由实机 L09 段覆盖;枚举失败零新增已证(start 被拒)
    shutil.rmtree(ns, ignore_errors=True)


def l06_pid_reuse():
    ns = _mk_ns()
    env = _ns_env(ns)
    r = _cli(env, "start", "server", timeout=60)
    st = json.loads(_cli(env, "status").stdout)
    pid = (st.get("server") or {}).get("pid")
    # 篡改 created → 身份不匹配 → start 拒绝并记失败
    state = json.loads((ns / "state.json").read_text(encoding="utf-8"))
    state["server"]["created"] = int(state["server"].get("created") or 0) + 12345
    (ns / "state.json").write_text(json.dumps(state), encoding="utf-8")
    r2 = _cli(env, "start", "server", timeout=30)
    n, _ = _count_live(env, "server")
    refused = any(w in (r2.stderr + r2.stdout).lower()
                  for w in ("mismatch", "conflict", "identity"))
    check("L06", r2.returncode != 0 and refused,
          {"rc": r2.returncode, "err": (r2.stderr or r2.stdout).strip()[-150:],
           "pid": pid,
           "note": "PID 复用→identity mismatch 或 untracked conflict 均为合法拒绝"})
    _cli(env, "stop", "server", "--timeout", "6")
    shutil.rmtree(ns, ignore_errors=True)


def l07_stop_fail_keeps_budget():
    ns = _mk_ns()
    env = _ns_env(ns)
    _cli(env, "start", "server", timeout=60)
    env2 = dict(env)
    env2["RCF1_LIFECYCLE_STOP_FAIL"] = "1"
    r = _cli(env2, "stop", "server", "--timeout", "2", timeout=45)
    st = json.loads(_cli(env, "status").stdout)
    fails_after = (st.get("consecutive_failures") or {}).get("server", 0)
    # stop 失败后:状态 STOP_FAILED,预算≥1,start 被拒(预算或阻断)
    r2 = _cli(env, "start", "server", timeout=30)
    check("L07", "STOP_FAILED" in json.dumps(r.stdout) and fails_after >= 1
          and r2.returncode != 0,
          {"stop_out": r.stdout.strip()[:100], "fails": fails_after,
           "restart_rc": r2.returncode})
    # 清场:真实强杀(不注入)
    _cli(env, "stop", "server", "--timeout", "6", timeout=60)
    shutil.rmtree(ns, ignore_errors=True)


def l08_budget_bypass():
    ns = _mk_ns()
    env = _ns_env(ns)
    env["RCF1_FAKE_DIE_S"] = "1"   # spawn 后即死 → start 失败
    for i in range(3):
        _cli(env, "start", "server", "--timeout", "15", timeout=60)
    st = json.loads(_cli(env, "status").stdout)
    locked = (st.get("locked") or {}).get("server")
    # 换 run 目录/换脚本名/stop 都不清预算
    env2 = dict(env)
    env2["RCF1_LIFECYCLE_NS_ROOT"] = str(ns)  # 同预算文件
    r_stop = _cli(env2, "stop", "all", timeout=10)
    st2 = json.loads(_cli(env2, "status").stdout)
    r_retry = _cli(env2, "start", "server", timeout=45)
    check("L08a", locked and (st2.get("locked") or {}).get("server")
          and r_retry.returncode != 0,
          {"locked1": locked, "locked2": (st2.get("locked") or {}).get("server"),
           "retry_rc": r_retry.returncode, "stop": r_stop.stdout.strip()[:80]})
    # unlock 无 reason 拒绝;有 reason 清零但保留 total
    r3 = _cli(env2, "unlock")
    r4 = _cli(env2, "unlock", "--reason", "repair evidence: test fix")
    st3 = json.loads(_cli(env2, "status").stdout)
    fails_raw = json.loads((ns / "failures.json").read_text(encoding="utf-8"))
    check("L08b", r3.returncode != 0 and r4.returncode == 0
          and not (st3.get("locked") or {}).get("server")
          and fails_raw.get("total_failures", 0) >= 3,
          {"unlock_no_reason_rc": r3.returncode, "unlock_rc": r4.returncode,
           "total": fails_raw.get("total_failures"),
           "consec": (st3.get("consecutive_failures") or {}).get("server")})
    shutil.rmtree(ns, ignore_errors=True)


def l09_no_revive():
    ns = _mk_ns()
    env = _ns_env(ns)
    _cli(env, "start", "server", timeout=60)
    _cli(env, "stop", "server", "--timeout", "6", timeout=60)
    t0 = time.time()
    revived = False
    while time.time() - t0 < 8:  # 假后端两个巡检周期等价(无真实看门狗;实机 60s 段另证)
        n, st = _count_live(env, "server")
        if n:
            revived = True
            break
        time.sleep(2)
    check("L09(fake)", not revived, {"revived": revived,
          "note": "实机 60s 段在 R1-L 实机确认中补"})
    shutil.rmtree(ns, ignore_errors=True)


def l10_real_state_untouched():
    real_snap = None
    if REAL_NS.exists():
        try:
            real_snap = {p.name: p.read_bytes()
                         for p in REAL_NS.iterdir() if p.is_file()}
        except OSError:
            real_snap = None
    # 跑一轮完整假流程
    ns = _mk_ns()
    env = _ns_env(ns)
    env["RCF1_FAKE_READY_S"] = "2"
    _cli(env, "start", "server", timeout=60)
    _cli(env, "start", "client", timeout=60)
    _cli(env, "stop", "all", "--timeout", "6")
    if REAL_NS.exists():
        try:
            after = {p.name: p.read_bytes()
                     for p in REAL_NS.iterdir() if p.is_file()}
        except OSError:
            after = None
        same = (real_snap == after) or (real_snap is None and after is None)
    else:
        same = not ns.exists() or str(ns) != str(REAL_NS)
    check("L10", same and str(ns) != str(REAL_NS),
          {"real_untouched": same, "ns": str(ns)})
    shutil.rmtree(ns, ignore_errors=True)


def l11_stop_no_record_with_orphan():
    """R2/R06:state 无记录 + 命名空间有孤儿 → BLOCKED,不伪报 STOPPED。"""
    ns = _mk_ns()
    env = _ns_env(ns)
    orphan = subprocess.Popen(
        [PY, str(HERE / "_rcf1_fake_proc.py"), "--role", "client",
         "-Drcf1.instance.marker=%sclient-7777aaaa8888bbbb"
         % _fake_prefix(ns)],
        env={k: v for k, v in os.environ.items()
             if not k.startswith("RCF1_FAKE")},
        stdout=subprocess.DEVNULL)
    time.sleep(1)
    # CIM CommandLine 注册延迟不定:轮询直到命名空间扫描确实看到孤儿
    deadline = time.time() + 20
    seen = None
    while time.time() < deadline:
        st = json.loads(_cli(env, "status").stdout)
        seen = (st.get("client") or {}).get("namespace_extras")
        if seen:
            break
        time.sleep(2)
    r = _cli(env, "stop", "client", "--timeout", "6", timeout=60)
    alive = orphan.poll() is None
    check("L11", "BLOCKED" in r.stdout and alive and seen,
          {"out": r.stdout.strip()[:160], "orphan_alive": alive,
           "extras_seen": seen})
    orphan.kill()
    orphan.wait(timeout=15)
    shutil.rmtree(ns, ignore_errors=True)


def l12_verified_plus_extra_instance():
    """R2/R06:已验证一个合法记录后仍扫描同角色额外实例,提前返回=冲突。"""
    ns = _mk_ns()
    env = _ns_env(ns)
    _cli(env, "start", "server", timeout=60)
    extra = subprocess.Popen(
        [PY, str(HERE / "_rcf1_fake_proc.py"), "--role", "server",
         "-Drcf1.instance.marker=%sserver-9999bbbbccccdddd"
         % _fake_prefix(ns)],
        env={k: v for k, v in os.environ.items()
             if not k.startswith("RCF1_FAKE")},
        stdout=subprocess.DEVNULL)
    time.sleep(1)
    # CIM 延迟:轮询直到 status 确实看到额外实例再断言 start 拒绝
    deadline = time.time() + 20
    seen = None
    while time.time() < deadline:
        st0 = json.loads(_cli(env, "status").stdout)
        seen = (st0.get("server") or {}).get("namespace_extras")
        if seen:
            break
        time.sleep(2)
    r = _cli(env, "start", "server", timeout=45)
    n, st = _count_live(env, "server")
    check("L12", seen and r.returncode != 0
          and "extra" in (r.stderr + r.stdout).lower(),
          {"rc": r.returncode, "out": (r.stdout or "").strip()[:160],
           "err": (r.stderr or "").strip()[:120], "extras_seen": seen})
    _cli(env, "stop", "server", "--timeout", "6", timeout=60)
    extra.kill()
    extra.wait(timeout=15)
    shutil.rmtree(ns, ignore_errors=True)


def l13_cross_role_budget_isolated():
    """R2/R06:server 连败锁死后,client start 成功不得清 server 预算。"""
    ns = _mk_ns()
    env = _ns_env(ns)
    env["RCF1_FAKE_DIE_S"] = "1"
    for i in range(3):
        _cli(env, "start", "server", "--timeout", "15", timeout=60)
    st = json.loads(_cli(env, "status").stdout)
    server_locked = (st.get("locked") or {}).get("server")
    # 同一环境下启动 client(不受 DIE 影响——DIE 只对 fake ready 流程)
    env2 = dict(env)
    env2.pop("RCF1_FAKE_DIE_S", None)
    rc = _cli(env2, "start", "client", timeout=60)
    st2 = json.loads(_cli(env, "status").stdout)
    check("L13", server_locked and rc.returncode == 0
          and (st2.get("locked") or {}).get("server")
          and (st2.get("consecutive_failures") or {}).get("server", 0) >= 3,
          {"server_locked": server_locked, "client_rc": rc.returncode,
           "server_fails_after": (st2.get("consecutive_failures")
                                  or {}).get("server")})
    _cli(env2, "stop", "client", "--timeout", "6", timeout=60)
    shutil.rmtree(ns, ignore_errors=True)

    shutil.rmtree(ns, ignore_errors=True)


def l14_stop_with_record_scope_tail_verification():
    """MC-RCF-1-R3 F06:有记录 stop 尾部核验——记录实例停掉后同角色
    还有额外实例时必须 BLOCKED 且保留;额外实例消失后再 stop 才
    报 STOPPED(scope verified 0)。"""
    ns = _mk_ns()
    env = _ns_env(ns)
    _cli(env, "start", "server", timeout=60)
    extra = subprocess.Popen(
        [PY, str(HERE / "_rcf1_fake_proc.py"), "--role", "server",
         "-Drcf1.instance.marker=%sserver-8888aaaabbbbcccc"
         % _fake_prefix(ns)],
        env={k: v for k, v in os.environ.items()
             if not k.startswith("RCF1_FAKE")},
        stdout=subprocess.DEVNULL)
    time.sleep(3)
    r = _cli(env, "stop", "server", "--timeout", "6", timeout=90)
    out = r.stdout.strip()
    st = json.loads(_cli(env, "status").stdout)
    failed = (st.get("server") or {}).get("status") == "STOP_FAILED"
    blocked_tail = ("same-role instances remain" in out
                    and "STOPPED" not in out)
    extra_alive = extra.poll() is None
    extra.kill()
    extra.wait(timeout=15)
    r2 = _cli(env, "stop", "server", "--timeout", "6", timeout=60)
    out2 = r2.stdout.strip()
    clean_stop = ("scope verified 0" in out2
                  or "namespace clean" in out2
                  or "already gone" in out2)
    check("L14", blocked_tail and extra_alive and not failed and clean_stop,
          {"tail_out": out[:140], "extra_alive": extra_alive,
           "state": (st.get("server") or {}).get("status"),
           "restop": out2[:140]})
    shutil.rmtree(ns, ignore_errors=True)


def main():
    _cleanup_fake_orphans()
    for fn in (l01_concurrent, l02_slow_start, l03_crash_windows,
               l04_corrupt_state, l05_enum_fail, l06_pid_reuse,
               l07_stop_fail_keeps_budget, l08_budget_bypass,
               l09_no_revive, l10_real_state_untouched,
               l11_stop_no_record_with_orphan,
               l12_verified_plus_extra_instance,
               l13_cross_role_budget_isolated,
               l14_stop_with_record_scope_tail_verification):
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            check(fn.__name__, False, "EXC %r" % exc)
    _cleanup_fake_orphans()
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    # R3D:门行(lifecycle.jsonl;子编号 → 基础 id + attempt)
    import os as _os
    import json as _json
    import re as _re
    raw_root = _os.environ.get("RCF1_RAW", r"D:\mc-rcf1-raw")
    attempts = {}
    with open(_os.path.join(raw_root, "lifecycle.jsonl"), "w",
              encoding="utf-8") as fh:
        for tid, ok, detail in RESULTS:
            m = _re.match(r"(L\d+)([a-z]?)", tid)
            base = m.group(1) if m else tid
            attempts[base] = attempts.get(base, 0) + 1
            fh.write(_json.dumps({
                "id": base, "attempt": attempts[base],
                "pass": bool(ok),
                "reason": detail[:160]}, ensure_ascii=False) + "\n")
    print("SUMMARY %d/%d" % (passed, len(RESULTS)))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
