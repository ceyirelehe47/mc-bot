# -*- coding: utf-8 -*-
"""MC-RCF-1 隔离环境管理器:rcf1 专用 server/client 起停、桥 HTTP、RCON。

取代旧 up.py 对历史实验目录(mc2a07ar-work/drivers)的 import 依赖。
- 目录: D:/code/mc-experiment/rcf1-server / rcf1-client(与生产 25565/8765/8766 端口隔离)
- 凭证: 只从 D:/code/mc-bot/.secrets/rcf1-*.token|pass 读入进程 env,绝不写日志
- 进程: PID 文件落在 D:/mc-rcf1-raw/logs 下,stop 只按确认过的 PID 杀,绝不按进程名批杀
"""
import json
import os
import pathlib
import secrets
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ROOT = r"D:\code\mc-experiment"
SERVER = os.path.join(ROOT, "rcf1-server")
CLIENT = os.path.join(ROOT, "rcf1-client")
RUNLOG = pathlib.Path(r"D:\mc-rcf1-raw")  # 仓库外原始日志,脱敏后才进交付
JAVA = r"D:\mc-server\jdk-21.0.12.1+1\bin\java.exe"
SECRETS = os.path.join(REPO, ".secrets")
CMD_TEMPLATE = os.path.join(ROOT, "mc2a07a-work", "drivers", "prod_client_cmd.json")
CMD_FILE = RUNLOG.joinpath("rcf1-client-cmd.json")

GAME_PORT = 25599
RCON_PORT = 25598
BRIDGE_PORT = 8799          # HTTP(loopback, Bearer)
CONTROL_PORT = 8798         # 服务器→真实客户端控制 TCP
BRIDGE_BASE = "http://127.0.0.1:%d" % BRIDGE_PORT
READY_MARK = "external-body bridge bound to loopback port %d" % BRIDGE_PORT


def _secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as fh:
        return fh.read().strip()


BRIDGE_TOKEN = _secret("rcf1-bridge.token")
CONTROL_TOKEN = _secret("rcf1-control.token")
RCON_PWD = _secret("rcf1-rcon.pass")


# ---------- 进程身份(只按确认 PID,不按名字) ----------

def _pid_file(kind):
    p = RUNLOG.joinpath("logs")
    p.mkdir(parents=True, exist_ok=True)
    return p / ("rcf1-%s.pid" % kind)


def _read_pid(kind):
    f = _pid_file(kind)
    if not f.exists():
        return None
    try:
        pid = int(f.read_text().strip())
    except ValueError:
        return None
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "(Get-Process -Id %d -ErrorAction SilentlyContinue).Id" % pid],
                         capture_output=True, text=True)
    return pid if out.stdout.strip() == str(pid) else None


def _alive_cmdline(pid):
    out = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "(Get-CimInstance Win32_Process -Filter 'ProcessId=%d').CommandLine" % pid],
                         capture_output=True, text=True)
    return (out.stdout or "").strip()


def server_pid():
    pid = _read_pid("server")
    if pid and "rcf1-server" in _alive_cmdline(pid):
        return pid
    return None


def client_pid():
    pid = _read_pid("client")
    if pid and "rcf1-client" in _alive_cmdline(pid):
        return pid
    return None


# ---------- 服务器 ----------

def start_server(extra_env=None, log_name="rcf1-server.log"):
    pid = server_pid()
    if pid:
        return {"pid": pid, "already": True}
    env = dict(os.environ)
    env.update({
        "AIBOT_EXTERNAL_BOT": "Bob",
        "AIBOT_EXTERNAL_BACKEND": "real_client",
        "AIBOT_BRIDGE_TOKEN": BRIDGE_TOKEN,
        "AIBOT_BRIDGE_PORT": str(BRIDGE_PORT),
        "AIBOT_REAL_CLIENT_PORT": str(CONTROL_PORT),
        "AIBOT_REAL_CLIENT_TOKEN": CONTROL_TOKEN,
    })
    env.update(extra_env or {})
    log_path = RUNLOG.joinpath("logs", log_name)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(log_path, "ab")
    p = subprocess.Popen([JAVA, "-Xmx3G", "-jar", "fabric-server-launch.jar", "nogui"],
                         cwd=SERVER, env=env, stdout=log, stderr=subprocess.STDOUT)
    _pid_file("server").write_text(str(p.pid))
    deadline = time.time() + 300
    while time.time() < deadline:
        if p.poll() is not None:
            log.close()
            raise RuntimeError("server exited early rc=%s; see %s" % (p.returncode, log_path))
        try:
            if READY_MARK in open(log_path, "rb").read().decode("utf-8", "replace"):
                return {"pid": p.pid}
        except OSError:
            pass
        time.sleep(2)
    log.close()
    raise RuntimeError("server bridge not ready in 300s")


def stop_server():
    pid = server_pid()
    if not pid:
        return False
    rcon("stop")
    deadline = time.time() + 90
    while time.time() < deadline and _read_pid("server"):
        time.sleep(2)
    if _read_pid("server"):
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        "Stop-Process -Id %d -Force" % pid])
        time.sleep(3)
    _pid_file("server").unlink(missing_ok=True)
    return True


# ---------- Bob 真实客户端 ----------

def _client_cmd():
    """生产客户端命令模板,仅做目录替换(mc2a07-prod-client→rcf1-client)。"""
    raw = open(CMD_TEMPLATE, encoding="utf-8").read()
    raw = raw.replace("mc2a07-prod-client", "rcf1-client")
    CMD_FILE.parent.mkdir(parents=True, exist_ok=True)
    CMD_FILE.write_text(raw, encoding="utf-8")
    return json.loads(raw)


def start_bob(log_name="rcf1-bob-stdout.log"):
    pid = client_pid()
    if pid:
        return {"pid": pid, "already": True}
    env = os.environ.copy()
    env.update({
        "AIBOT_REAL_CLIENT": "1",
        "AIBOT_REAL_CLIENT_HOST": "127.0.0.1",
        "AIBOT_REAL_CLIENT_PORT": str(CONTROL_PORT),
        "AIBOT_REAL_CLIENT_TOKEN": CONTROL_TOKEN,
        "AIBOT_REAL_CLIENT_BODY_ID": "bob",
        "AIBOT_REAL_CLIENT_BOT_NAME": "Bob",
        "AIBOT_REAL_CLIENT_AUTO_JOIN": "127.0.0.1:%d" % GAME_PORT,
        "AIBOT_REAL_CLIENT_WINDOW_MODE": "background",
    })
    cmd = _client_cmd()
    log_path = RUNLOG.joinpath("logs", log_name)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(log_path, "ab")
    DETACHED = 0x00000008
    p = subprocess.Popen(cmd, creationflags=DETACHED, env=env,
                         stdout=log, stderr=subprocess.STDOUT, cwd=CLIENT)
    _pid_file("client").write_text(str(p.pid))
    return {"pid": p.pid}


def stop_bob():
    pid = client_pid()
    if not pid:
        return False
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Stop-Process -Id %d -Force" % pid])
    deadline = time.time() + 30
    while time.time() < deadline and _read_pid("client"):
        time.sleep(1)
    _pid_file("client").unlink(missing_ok=True)
    return True


def stop_all():
    stop_bob()
    stop_server()


# ---------- RCON ----------

def _pkt(rid, ptype, body):
    data = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def rcon(cmd, timeout=10):
    try:
        s = socket.create_connection(("127.0.0.1", RCON_PORT), timeout=timeout)
    except OSError:
        return None
    try:
        s.sendall(_pkt(1, 3, RCON_PWD))
        s.recv(4096)
        s.sendall(_pkt(2, 2, cmd))
        out = b""
        try:
            while True:
                chunk = s.recv(4096)
                if not chunk:
                    break
                out += chunk
                if len(chunk) < 4096:
                    break
        except socket.timeout:
            pass
        return out[8:-2].decode("utf-8", "replace")
    finally:
        s.close()


# ---------- 桥 HTTP ----------

def call(method, path, lease=None, body=None, headers=None, timeout=25):
    req = urllib.request.Request(BRIDGE_BASE + path, method=method)
    req.add_header("Authorization", "Bearer " + BRIDGE_TOKEN)
    if lease:
        req.add_header("X-Control-Token", lease)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = json.dumps(body).encode() if body is not None else None
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as fault:
        try:
            return {"_http": fault.code, "_body": fault.read().decode()[:400]}
        except Exception:
            return {"_http": fault.code}
    except (urllib.error.URLError, OSError, TimeoutError):
        return {"_unreachable": True}


LEASE_STATE_DIR = os.path.join(REPO, ".build")


def acquire_lease(owner, wait_s=30, reuse=True):
    """获取控制租约。per-owner 状态文件复用未过期 token,避免同 owner 连续 CLI
    调用互相抢租约;状态文件绝不跨 owner 共享(owner 名即身份,写入时锁定)。"""
    state_file = os.path.join(LEASE_STATE_DIR, "rcf1-lease-%s.json" % owner)
    deadline = time.time() + wait_s
    while time.time() < deadline:
        if reuse and os.path.exists(state_file):
            try:
                st = json.load(open(state_file, encoding="utf-8"))
                if st.get("token") and st.get("owner", "").startswith(owner):
                    r = call("POST", "/v1/lease", st["token"], None,
                             {"X-Owner-Id": st["owner"]})
                    if r.get("ok"):
                        # 桥可能轮换 token:必须采用响应里的新值,回存状态文件
                        tok = (r.get("data") or {}).get("token") or st["token"]
                        if tok != st["token"]:
                            json.dump({"owner": st["owner"], "token": tok,
                                       "ts": time.time()},
                                      open(state_file, "w", encoding="utf-8"))
                        return tok
            except Exception:
                pass
        this_owner = "%s-%s" % (owner, secrets.token_hex(3))
        r = call("POST", "/v1/lease", headers={"X-Owner-Id": this_owner})
        if r.get("ok"):
            tok = r["data"]["token"]
            os.makedirs(LEASE_STATE_DIR, exist_ok=True)
            json.dump({"owner": this_owner, "token": tok, "ts": time.time()},
                      open(state_file, "w", encoding="utf-8"))
            return tok
        time.sleep(2)
    return None
def release_lease(lease):
    return call("DELETE", "/v1/lease", lease)


def submit(lease, op, args, tag):
    rid = "rcf1-%s-%d" % (tag, int(time.time() * 1000) % 100000000)
    return call("POST", "/v1/executions/" + op, lease, args, {"X-Request-Id": rid})


def execution(lease, ex_id):
    return call("GET", "/v1/executions/" + ex_id, lease)


def status():
    return call("GET", "/v1/status")


def view(lease=None):
    return call("POST", "/v1/view", lease, {})


def observe(lease=None):
    return call("GET", "/v1/observe", lease)


def control(lease, ex_id, action, tag):
    rid = "rcf1-%s-%d" % (tag, int(time.time() * 1000) % 100000000)
    return call("POST", "/v1/executions/%s/%s" % (ex_id, action), lease, {}, {"X-Request-Id": rid})


TERMINAL_STATES = ("completed", "failed", "cancelled", "outcome_unknown", "rejected")


def wait_terminal(lease, ex_id, timeout_s=120, poll_s=0.8):
    """轮询至终态;LEASE 全程续租。超时返回 {'state': 'TIMEOUT', ...}。"""
    t0 = time.time()
    last = None
    trail = []
    while time.time() - t0 < timeout_s:
        last = execution(lease, ex_id)
        d = last.get("data") or {}
        st = d.get("state")
        low = st.lower() if isinstance(st, str) else ""
        trail.append((round(time.time() - t0, 2), low, (d.get("reason") or "")[:80]))
        if low in TERMINAL_STATES:
            return d, trail
        call("POST", "/v1/lease/renew", lease)
        time.sleep(poll_s)
    return {"state": "TIMEOUT", "last": last}, trail


def events(cursor=None, wait_s=20):
    path = "/v1/events"
    if cursor:
        path += "?cursor=%s&waitMs=%d" % (cursor, wait_s * 1000)
    else:
        path += "?waitMs=%d" % (wait_s * 1000)
    return call("GET", path, timeout=wait_s + 15)


def inspect_local(radius=6, detail="summary"):
    return call("POST", "/v1/inspect-local?radius=%d&detail=%s" % (radius, detail), None, {})


def inspect(ref, detail="summary"):
    from urllib.parse import quote
    return call("POST", "/v1/inspect?ref=%s&detail=%s" % (quote(ref, safe=""), quote(detail)), None, {})


def graphs(lease, graph_id=None):
    return call("GET", "/v1/graphs" + ("/" + graph_id if graph_id else ""), lease)
def bob_online():
    out = rcon("list") or ""
    return "Bob" in out, out.strip()


# ---------- 入口 ----------

def check():
    srv = server_pid()
    cli = client_pid()
    on, lst = bob_online()
    print(json.dumps({"server_pid": srv, "client_pid": cli,
                      "bob_online": on, "list": lst}))
    return 0 if (srv and on) else 1


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "start-server":
        extra = {}
        for a in sys.argv[2:]:
            if "=" in a:
                k, v = a.split("=", 1)
                extra[k] = v
        print(json.dumps(start_server(extra_env=extra)))
    elif cmd == "start-bob":
        print(json.dumps(start_bob()))
    elif cmd == "stop":
        stop_all()
        print("STOPPED")
    elif cmd == "check":
        sys.exit(check())
    else:
        print(__doc__)
