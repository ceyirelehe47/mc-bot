# -*- coding: utf-8 -*-
"""LLM 直控环境管理:起克隆服 + Bob 生产客户端 + 等在线。

python up.py run    # 长驻 supervisor(hub start 用):起服->起 Bob->PLAY-READY->驻留
python up.py stop   # 停 Bob 客户端 + 停服务器
python up.py check  # 探测当前状态(server/bob)
"""
import json, os, sys, time

ROOT = r"D:\code\mc-experiment"
TOOLS = os.path.dirname(os.path.abspath(__file__))
for _p in (os.path.join(ROOT, "mc2a07ar-work", "drivers"), TOOLS):
    if _p not in sys.path:
        sys.path.insert(0, _p)
import mc2a07ar_live as L  # noqa: E402


def client_pids():
    out = __import__("subprocess").run(
        ["powershell", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object {$_.CommandLine -match 'KnotClient|mc2a07-prod-client'} | Select-Object -ExpandProperty ProcessId"],
        capture_output=True, text=True)
    return [x.strip() for x in out.stdout.split() if x.strip().isdigit()]


def kill_clients_confirmed(rounds=5):
    """杀客户端进程直到确认无残留(避免半死进程的 TCP 会话占坑)。"""
    for _ in range(rounds):
        pids = client_pids()
        if not pids:
            return True
        L.kill_clients()
        time.sleep(3)
    return not client_pids()


def start_bob_confirmed(tag="play"):
    """起 Bob 客户端并等在线;失败整轮重试(最多 3 轮)。"""
    for attempt in range(3):
        if not kill_clients_confirmed():
            print("CLIENT-KILL-STUCK", flush=True)
            return False
        time.sleep(3)
        L.start_bob(port=8766, stdout_name="bob-%s-stdout.log" % tag)
        if L.wait_body_online(timeout_s=150):
            return True
        print("BOB-JOIN-RETRY-%d" % attempt, flush=True)
        # 连续 join 失败常见于服务器 session 状态残留:整服务器冷重启再试
        if L.server_pid():
            L.stop_server()
        time.sleep(3)
        L.start_server(log_name="server-%s-restart.log" % tag)
        print("SERVER-RESTARTED-FOR-JOIN", flush=True)
    return False


def run():
    extra = {"AIBOT_REAL_CLIENT_PERCEPTION_BLOCK_RADIUS": "24",
             "AIBOT_REAL_CLIENT_PERCEPTION_RADIUS": "32"}
    if not L.server_pid():
        print("starting server...", flush=True)
        L.start_server(log_name="server-play.log", extra_env=extra)
    print("SERVER-READY", flush=True)
    out = L.rcon("list") or ""
    if "Bob" not in out:
        if not start_bob_confirmed():
            print("BOB-JOIN-FAILED", flush=True)
            raise SystemExit(1)
    print("PLAY-READY", flush=True)
    # 运行期巡检:服务器/客户端中途崩溃自动拉起(此前 PLAY-READY 后只睡,
    # java 全挂 supervisor 也不会发现,实测桥 10061 无响应)。
    fail_count = 0
    while True:
        time.sleep(30)
        try:
            if not L.server_pid():
                print("WATCHDOG: server gone, restarting", flush=True)
                kill_clients_confirmed()
                L.start_server(log_name="server-play-watchdog.log", extra_env=extra)
                if not start_bob_confirmed():
                    print("WATCHDOG: bob failed after server restart", flush=True)
                else:
                    print("PLAY-READY", flush=True)
                continue
            out = L.rcon("list") or ""
            if "Bob" not in out:
                fail_count += 1
                if fail_count >= 3:
                    print("WATCHDOG: bob offline, restarting client", flush=True)
                    kill_clients_confirmed()
                    if not start_bob_confirmed():
                        print("WATCHDOG: client restart failed", flush=True)
                    fail_count = 0
            else:
                fail_count = 0
        except Exception as e:
            print("WATCHDOG err: %s" % str(e)[:80], flush=True)


def stop():
    kill_clients_confirmed()
    if L.server_pid():
        L.stop_server()
    print("STOPPED")


def check():
    srv = L.server_pid() is not None
    out = L.rcon("list") or ""
    bob = "Bob" in out
    print(json.dumps({"server": srv, "bob_online": bob, "list": out}))
    return 0 if (srv and bob) else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "run":
        run()
    elif cmd == "stop":
        stop()
    else:
        sys.exit(check())
