# -*- coding: utf-8 -*-
"""MC-RCF-1 生命周期假进程测试:并发/连续/慢启动/崩溃陈旧/PID复用/停止语义/预算锁定。

全部用轻量 python sleeper 冒充游戏进程(monkeypatch 命令构造),测的是
rcf1_lifecycle 真实的锁、状态、身份核验与预算逻辑。不启动任何 Minecraft。
"""
import json
import os
import pathlib
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rcf1_lifecycle as LC  # noqa: E402

FAKE_READY = LC.SERVER_READY_MARK
SLEEPER = "import sys,time;\n" \
          "arg=[a for a in sys.argv[1:]]\n" \
          "delay=float(arg[0]) if arg and not arg[0].startswith('-D') else 0\n" \
          "time.sleep(delay)\n" \
          "print('%s',flush=True)\n" \
          "time.sleep(600)\n" % FAKE_READY


def fake_server_cmd(marker):
    cmd = [sys.executable, "-c", SLEEPER, "0",
           "-Drcf1.instance.marker=%s" % marker]
    return cmd, {}, LC.SERVER_DIR


def fake_slow_cmd(marker):
    cmd = [sys.executable, "-c", SLEEPER, "8",
           "-Drcf1.instance.marker=%s" % marker]
    return cmd, {}, LC.SERVER_DIR


def reset_state():
    for f in (LC.STATE_FILE, LC.FAIL_FILE):
        f.unlink(missing_ok=True)


def count_procs():
    """只统计 python 假进程(marker + python 可执行);
    真实游戏实例是 java 且带同一 marker——绝不能误杀(实测教训)。"""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" "
         "| Select-Object ProcessId, ExecutablePath, CommandLine | ConvertTo-Json -Compress"],
        capture_output=True).stdout.decode("utf-8", "replace").strip()
    if not out:
        return []
    d = json.loads(out)
    if isinstance(d, dict):
        d = [d]
    return [p for p in d
            if "-Drcf1.instance.marker=" in (p.get("CommandLine") or "")
            and "python" in (p.get("ExecutablePath") or "").lower()]


def kill_all_fake():
    for p in count_procs():
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        "Stop-Process -Id %s -Force" % p["ProcessId"]],
                       capture_output=True)
    deadline = time.time() + 15
    while time.time() < deadline and count_procs():
        time.sleep(1)


RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print("%s %-38s %s %s" % (time.strftime("%H:%M:%S"), name,
                              "PASS" if ok else "FAIL", detail))


def t1_concurrent(patch):
    reset_state()
    LC._server_cmd = fake_server_cmd
    LC._client_cmd = fake_server_cmd  # 防误触真命令
    outs = []
    def worker():
        try:
            outs.append(LC.start("server", timeout_s=60))
        except Exception as e:  # noqa: BLE001
            outs.append({"exc": str(e)})
    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    n = len(count_procs())
    launched = sum(1 for o in outs if isinstance(o, dict) and o.get("status") == "RUNNING")
    dupes = sum(1 for o in outs if isinstance(o, dict) and o.get("status") == "STARTING")
    check("T1 并发启动单实例", n == 1 and launched >= 1 and launched + dupes == 8,
          "procs=%d launched=%d starting=%d" % (n, launched, dupes))
    LC.stop("server")


def t2_sequential():
    LC._server_cmd = fake_server_cmd
    a = LC.start("server", timeout_s=60)
    b = LC.start("server", timeout_s=60)
    n = len(count_procs())
    check("T2 连续启动幂等", n == 1 and b.get("status") == "RUNNING"
          and a.get("pid") == b.get("pid"),
          "procs=%d a=%s b=%s" % (n, a.get("pid"), b.get("pid")))
    LC.stop("server")


def t3_slow_start():
    LC._server_cmd = fake_slow_cmd
    box = {}

    def worker():
        box["first"] = LC.start("server", timeout_s=60)

    th = threading.Thread(target=worker)
    th.start()
    time.sleep(2.5)  # 首个仍在慢启动窗口
    t_call = time.time()
    second = LC.start("server", timeout_s=60)
    waited = time.time() - t_call > 3  # 被锁等待,而非并行二启
    th.join()
    n = len(count_procs())
    check("T3 慢启动不二启", n == 1 and second.get("status") == "RUNNING" and waited,
          "procs=%d second=%s waited=%.1fs" % (n, second.get("status"), time.time() - t_call))
    LC.stop("server")


def t4_stale_starting():
    LC._server_cmd = fake_server_cmd
    rec = LC._spawn("server", _load_state())
    pid = rec["pid"]
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Stop-Process -Id %d -Force" % pid], capture_output=True)
    time.sleep(1)
    r = LC.start("server", timeout_s=60)
    n = len(count_procs())
    check("T4 崩溃陈旧状态重启", n == 1 and r.get("status") == "RUNNING"
          and r.get("pid") != pid,
          "procs=%d newpid=%s oldpid=%s" % (n, r.get("pid"), pid))
    LC.stop("server")


def _load_state():
    return LC._load_json(LC.STATE_FILE, {})


def t5_pid_reuse_and_budget():
    LC._server_cmd = fake_server_cmd
    LC.start("server", timeout_s=60)
    state = _load_state()
    real_marker = state["server"]["marker"]
    # 模拟 PID 复用:状态 pid 指向另一个活着但无 marker 的进程(本测试进程)
    state["server"]["pid"] = os.getpid()
    LC._save_json(LC.STATE_FILE, state)
    refused = None
    try:
        LC.start("server", timeout_s=10)
        refused = False
    except RuntimeError:
        refused = True
    n = len(count_procs())
    fails = LC._load_json(LC.FAIL_FILE, {"consecutive": 0}).get("consecutive", 0)
    check("T5 PID复用拒启动", refused and n == 1 and fails >= 1,
          "refused=%s procs=%d fails=%d" % (refused, n, fails))
    # 恢复真 marker 以便清理
    state = _load_state()
    # 旧实例仍在(真 marker 进程)
    LC.stop("server")


def t6_stop_no_revive():
    LC._server_cmd = fake_server_cmd
    LC.start("server", timeout_s=60)
    LC.stop("server")
    time.sleep(5)
    n = len(count_procs())
    st = LC.status()
    check("T6 停后无复活", n == 0 and st["server"]["status"] == "STOPPED",
          "procs=%d status=%s" % (n, st["server"]["status"]))


def t7_budget_lock():
    LC._server_cmd = fake_server_cmd
    reset_state()
    # 连续三次启动失败(用必死命令模拟启动即崩)
    def dead_cmd(marker):
        return [sys.executable, "-c", "import sys;sys.exit(3)",
                "-Drcf1.instance.marker=%s" % marker], {}, LC.SERVER_DIR
    LC._server_cmd = dead_cmd
    locked = False
    for _ in range(4):
        try:
            LC.start("server", timeout_s=20)
        except RuntimeError as e:
            if "LIFECYCLE_LOCKED" in str(e):
                locked = True
                break
    check("T7 三败锁定BLOCKED", locked, "locked=%s" % locked)
    unlocked = LC.unlock("fake-test verification")
    r = None
    try:
        LC._server_cmd = fake_server_cmd
        r = LC.start("server", timeout_s=60)
    finally:
        LC.stop("server")
    check("T7b unlock后恢复", "UNLOCKED" in unlocked and r and r.get("status") == "RUNNING")


def main():
    print("== 假进程生命周期测试(无 Minecraft)==")
    kill_all_fake()
    t1_concurrent(None)
    t2_sequential()
    t3_slow_start()
    t4_stale_starting()
    t5_pid_reuse_and_budget()
    t6_stop_no_revive()
    t7_budget_lock()
    kill_all_fake()
    n_pass = sum(1 for _, ok, _ in RESULTS if ok)
    print("SUMMARY %d/%d" % (n_pass, len(RESULTS)))
    return 0 if n_pass == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
