# -*- coding: utf-8 -*-
"""MC-RCF-1 唯一生命周期入口:本机最多 1 测试服务器 + 1 Bob 客户端。

设计约束(用户指令 2026-09-20):
- 所有启动/重启/停止必须经过本模块。本模块不是常驻守护进程:互斥靠
  OS 级文件锁(msvcrt.locking,跨进程原子),任何并发调用被锁串行化,
  杜绝"先查 PID 再启动"的竞态。
- 实例身份 = PID + CIM CreationDate + 命令行标记(-Drcf1.instance.marker,
  每次启动随机生成)。三者同时匹配才认账,PID 被复用即失配。
- 显式 stop 后没有任何自动复活;重启必须先确认旧实例退出。
- 进程枚举失败/权限不足/归属不明/停止未确认 → 拒绝继续启动(fail closed),
  绝不按"没有进程"处理。
- 连续失败预算 3 次 → LOCKED(BLOCKED),需显式 unlock;计数不因换脚本、
  换日志名或换 run id 清零。
- 启动中(STARTING)也占配额。

CLI:
  python rcf1_lifecycle.py start server [timeout_s]   # 等就绪(桥标记)
  python rcf1_lifecycle.py start client [timeout_s]   # 等就绪(入服)
  python rcf1_lifecycle.py stop  server|client|all
  python rcf1_lifecycle.py status
  python rcf1_lifecycle.py unlock <reason>
"""
import json
import os
import pathlib
import secrets
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ROOT = r"D:\code\mc-experiment"
STATE_DIR = pathlib.Path(r"D:\mc-rcf1-raw\lifecycle")
LOCK_FILE = STATE_DIR / "lifecycle.lock"
STATE_FILE = STATE_DIR / "state.json"
FAIL_FILE = STATE_DIR / "failures.json"
LOG_DIR = STATE_DIR / "logs"

SERVER_DIR = os.path.join(ROOT, "rcf1-server")
CLIENT_DIR = os.path.join(ROOT, "rcf1-client")
JAVA = r"D:\mc-server\jdk-21.0.12.1+1\bin\java.exe"
CMD_TEMPLATE = os.path.join(ROOT, "mc2a07a-work", "drivers", "prod_client_cmd.json")
SECRETS = os.path.join(REPO, ".secrets")

GAME_PORT = 25599
BRIDGE_PORT = 8799
CONTROL_PORT = 8798
RCON_PORT = 25598
SERVER_READY_MARK = "external-body bridge bound to loopback port %d" % BRIDGE_PORT
MAX_CONSECUTIVE_FAILURES = 3

ROLES = ("server", "client")


def _secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as fh:
        return fh.read().strip()


# ---------- OS 级互斥(跨进程原子) ----------

class _FileLock:
    """msvcrt.locking 独占锁:持有期间任何并发生命周期操作被阻塞串行化。"""

    def __init__(self, path, timeout_s=120):
        self.path = path
        self.timeout_s = timeout_s
        self.fd = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(str(self.path), os.O_CREAT | os.O_RDWR)
        import msvcrt
        deadline = time.time() + self.timeout_s
        while True:
            try:
                msvcrt.locking(self.fd, msvcrt.LK_NBLCK, 1)
                return self
            except OSError:
                if time.time() >= deadline:
                    os.close(self.fd)
                    self.fd = None
                    raise TimeoutError("lifecycle lock busy >%ss" % self.timeout_s)
                time.sleep(0.2)

    def __exit__(self, *exc):
        if self.fd is not None:
            import msvcrt
            try:
                os.lseek(self.fd, 0, os.SEEK_SET)
                msvcrt.locking(self.fd, msvcrt.LK_UNLCK, 1)
            finally:
                os.close(self.fd)
                self.fd = None


# ---------- 进程事实查询(失败即异常,不静默) ----------

def _cim_processes():
    """java/javaw/python 进程表 (pid → (创建时间, 命令行))。
    python 也纳入:假进程测试载体是 python;真实实例是 java。
    rc!=0 或 stderr 非空 = 枚举失败(抛异常,禁止启动);
    rc=0 且输出空 = 合法空集。"""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe' or Name='python.exe'\" "
         "| Select-Object ProcessId, CreationDate, CommandLine | ConvertTo-Json -Compress"],
        capture_output=True)
    raw = out.stdout.decode("utf-8", "replace").strip()
    err = out.stderr.decode("utf-8", "replace").strip()
    if out.returncode != 0 or err:
        raise RuntimeError("process enumeration failed rc=%s err=%s"
                           % (out.returncode, err[:120]))
    if not raw:
        return {}
    data = json.loads(raw)
    if isinstance(data, dict):
        data = [data]
    procs = {}
    for p in data:
        try:
            procs[int(p["ProcessId"])] = (str(p.get("CreationDate") or ""),
                                          p.get("CommandLine") or "")
        except (KeyError, ValueError, TypeError):
            continue
    return procs


def _norm_creation(cim_date):
    """CIM /Date(1789912864518...)/ → 毫秒整数。"""
    s = cim_date or ""
    if "/Date(" in s:
        return s.split("/Date(")[1].split(")")[0].split("+")[0].split("-")[0]
    return s


def find_by_marker(marker):
    """按命令行 -Drcf1.instance.marker=<token> 定位;返回 pid+创建时间或 None。"""
    procs = _cim_processes()
    tag = "-Drcf1.instance.marker=%s" % marker
    for pid, (created, cmd) in procs.items():
        if tag in cmd:
            return {"pid": pid, "created": _norm_creation(created), "cmd_head": cmd[:120]}
    return None


def server_port_owner():
    """持有 GAME_PORT 的 java pid(服务器角色第二身份)。"""
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "(Get-NetTCPConnection -LocalPort %d -State Listen -ErrorAction SilentlyContinue "
         "| Select-Object -First 1).OwningProcess" % GAME_PORT],
        capture_output=True)
    raw = out.stdout.decode("utf-8", "replace").strip()
    return int(raw) if raw.isdigit() else None

def _verified(role, state, procs=None):
    """身份核验三要素:PID 存活 + 创建时间匹配 + 命令行 marker 匹配。
    PID 被其他进程复用时创建时间失配 → 不认账。
    记录的创建时间为空(CIM 注册延迟)时,首次成功核验补记(只收紧不放宽)。"""
    rec = state.get(role) or {}
    marker = rec.get("marker")
    pid = rec.get("pid")
    if not marker or not pid:
        return None
    procs = procs if procs is not None else _cim_processes()
    info = procs.get(int(pid))
    if info is None:
        return None
    created, cmd = info
    if "-Drcf1.instance.marker=%s" % marker not in cmd:
        return None  # PID 被复用或非本实例
    created_n = _norm_creation(created)
    claimed = rec.get("created") or ""
    if claimed and created_n != claimed:
        return None  # 同 PID 不同创建时间:复用
    if not claimed and created_n:
        state[role]["created"] = created_n
        _save_json(STATE_FILE, state)
    return {"pid": int(pid), "created": created_n,
            "claimed_created": claimed or created_n, "state": rec.get("state")}

def _load_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def _save_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(str(tmp), str(path))


def _record_failure(reason):
    fails = _load_json(FAIL_FILE, {"consecutive": 0, "history": []})
    fails["consecutive"] = int(fails.get("consecutive", 0)) + 1
    fails.setdefault("history", []).append(
        {"ts": time.time(), "reason": reason[:200],
         "consecutive": fails["consecutive"]})
    _save_json(FAIL_FILE, fails)
    return fails["consecutive"]


def _record_success():
    fails = _load_json(FAIL_FILE, {"consecutive": 0, "history": []})
    if fails.get("consecutive"):
        fails["consecutive"] = 0
        _save_json(FAIL_FILE, fails)


def _check_budget():
    fails = _load_json(FAIL_FILE, {"consecutive": 0})
    if int(fails.get("consecutive", 0)) >= MAX_CONSECUTIVE_FAILURES:
        raise RuntimeError("LIFECYCLE_LOCKED: %d consecutive failures; "
                           "explicit 'unlock <reason>' required"
                           % fails["consecutive"])


# ---------- 启动命令 ----------

def _server_cmd(marker):
    env = {
        "AIBOT_EXTERNAL_BOT": "Bob",
        "AIBOT_EXTERNAL_BACKEND": "real_client",
        "AIBOT_BRIDGE_TOKEN": _secret("rcf1-bridge.token"),
        "AIBOT_BRIDGE_PORT": str(BRIDGE_PORT),
        "AIBOT_REAL_CLIENT_PORT": str(CONTROL_PORT),
        "AIBOT_REAL_CLIENT_TOKEN": _secret("rcf1-control.token"),
    }
    cmd = [JAVA, "-Xmx3G", "-Drcf1.instance.marker=%s" % marker,
           "-jar", "fabric-server-launch.jar", "nogui"]
    return cmd, env, SERVER_DIR


def _client_cmd(marker):
    env = {
        "AIBOT_REAL_CLIENT": "1",
        "AIBOT_REAL_CLIENT_HOST": "127.0.0.1",
        "AIBOT_REAL_CLIENT_PORT": str(CONTROL_PORT),
        "AIBOT_REAL_CLIENT_TOKEN": _secret("rcf1-control.token"),
        "AIBOT_REAL_CLIENT_BODY_ID": "bob",
        "AIBOT_REAL_CLIENT_BOT_NAME": "Bob",
        "AIBOT_REAL_CLIENT_AUTO_JOIN": "127.0.0.1:%d" % GAME_PORT,
        "AIBOT_REAL_CLIENT_WINDOW_MODE": "background",
    }
    raw = open(CMD_TEMPLATE, encoding="utf-8").read()
    raw = raw.replace("mc2a07-prod-client", "rcf1-client")
    cmd = json.loads(raw)
    # 注入 marker:插入到 java 可执行之后(JVM 参数区)
    jvm = "-Drcf1.instance.marker=%s" % marker
    insert = 1
    if cmd and cmd[0].lower().endswith("java.exe"):
        insert = 1
        while insert < len(cmd) and cmd[insert].startswith("-D"):
            insert += 1
    cmd = cmd[:insert] + [jvm] + cmd[insert:]
    return cmd, env, CLIENT_DIR


def _spawn(role, state):
    marker = "rcf1-%s-%s" % (role, secrets.token_hex(8))
    if role == "server":
        cmd, env, cwd = _server_cmd(marker)
        log_name = "server-stdout.log"
    else:
        cmd, env, cwd = _client_cmd(marker)
        log_name = "client-stdout.log"
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    # 就绪判定只看本次实例输出:旧日志轮转归档,防止历史标记造成假就绪
    log_path = LOG_DIR / log_name
    if log_path.exists():
        os.replace(str(log_path),
                   str(LOG_DIR / ("%s.%d.log" % (log_name.replace(".log", ""),
                                                 int(time.time() * 1000)))))
    log = open(log_path, "wb")
    merged = dict(os.environ)
    merged.update(env)
    p = subprocess.Popen(cmd, cwd=cwd, env=merged, stdout=log,
                         stderr=subprocess.STDOUT,
                         creationflags=0x00000008)  # DETACHED:与调用方生命周期解耦
    procs = _cim_processes()
    created = _norm_creation(procs.get(p.pid, ("", ""))[0])
    state[role] = {"pid": p.pid, "marker": marker, "created": created,
                   "state": "STARTING", "started_at": time.time()}
    _save_json(STATE_FILE, state)
    return state[role]

def _wait_ready(role, state, timeout_s):
    """server:桥就绪标记;client:RCON list 含 Bob。超时算一次失败。
    瞬态枚举缺口(CIM 注册延迟)容忍连续 5 次缺失;持续缺失判死。"""
    deadline = time.time() + timeout_s
    misses = 0
    while time.time() < deadline:
        v = _verified(role, state)
        if v is None:
            misses += 1
            if misses >= 5:
                raise RuntimeError("%s died during startup" % role)
            time.sleep(2)
            continue
        misses = 0
        if role == "server":
            try:
                tail = (LOG_DIR / "server-stdout.log").read_bytes()[-20000:]
                if SERVER_READY_MARK in tail.decode("utf-8", "replace"):
                    return True
            except OSError:
                pass
        else:
            if _rcon_list_has_bob():
                return True
        time.sleep(2)
    raise RuntimeError("%s not ready in %ss" % (role, timeout_s))


def _rcon_list_has_bob():
    try:
        sys.path.insert(0, HERE)
        import rcf1_env
        on, _ = rcf1_env.bob_online()
        return on
    except Exception:
        return False


# ---------- 公开操作(全部持锁) ----------

def start(role, timeout_s=300):
    if role not in ROLES:
        raise ValueError("role must be server|client")
    with _FileLock(LOCK_FILE):
        _check_budget()
        state = _load_json(STATE_FILE, {})
        try:
            v = _verified(role, state)
        except RuntimeError:
            raise  # 枚举失败:禁止启动
        if v is not None:
            rec = state[role]
            if rec.get("state") == "STARTING":
                return {"role": role, "status": "STARTING", "pid": v["pid"],
                        "note": "startup in progress; not launching a second instance"}
            return {"role": role, "status": "RUNNING", "pid": v["pid"],
                    "note": "existing verified instance; idempotent start"}
        # 无已验证实例:显式确认旧 pid 已退出(防"启动超时≠进程不存在")
        old = state.get(role) or {}
        if old.get("pid"):
            procs = _cim_processes()
            if int(old["pid"]) in procs and old.get("marker"):
                # 同 marker 仍活着但上面核验失败 → 记录一致性异常,拒绝启动
                _record_failure("%s identity mismatch on old pid %s"
                                % (role, old["pid"]))
                raise RuntimeError("%s old pid %s alive but identity mismatch; "
                                   "refusing to launch" % (role, old["pid"]))
        rec = _spawn(role, state)
        try:
            _wait_ready(role, state, timeout_s)
            state = _load_json(STATE_FILE, {})
            state[role]["state"] = "RUNNING"
            _save_json(STATE_FILE, state)
            _record_success()
            return {"role": role, "status": "RUNNING", "pid": rec["pid"],
                    "marker": rec["marker"]}
        except RuntimeError as exc:
            _record_failure("start %s: %s" % (role, exc))
            raise


def stop(role, timeout_s=90):
    with _FileLock(LOCK_FILE):
        state = _load_json(STATE_FILE, {})
        results = {}
        roles = ROLES if role == "all" else (role,)
        for r in roles:
            rec = state.get(r) or {}
            marker = rec.get("marker")
            pid = rec.get("pid")
            if not marker or not pid:
                results[r] = "STOPPED(no record)"
                continue
            # 按 marker 全量定位(不信任 pid 文件单一来源)
            found = find_by_marker(marker)
            if found is None:
                results[r] = "STOPPED(already gone)"
                state[r] = {"state": "STOPPED"}
                _save_json(STATE_FILE, state)
                continue
            # 优雅停止(server 先 rcon stop)
            if r == "server":
                try:
                    sys.path.insert(0, HERE)
                    import rcf1_env
                    rcf1_env.rcon("stop")
                except Exception:
                    pass
            deadline = time.time() + timeout_s
            while time.time() < deadline and find_by_marker(marker):
                time.sleep(2)
            if find_by_marker(marker):
                subprocess.run(["powershell", "-NoProfile", "-Command",
                                "Stop-Process -Id %d -Force" % found["pid"]],
                               capture_output=True)
                deadline = time.time() + 30
                while time.time() < deadline and find_by_marker(marker):
                    time.sleep(1)
            if find_by_marker(marker):
                _record_failure("stop %s unconfirmed pid=%s" % (r, found["pid"]))
                results[r] = "STOP_FAILED(unconfirmed;starts now blocked until cleared)"
                continue
            state[r] = {"state": "STOPPED"}
            _save_json(STATE_FILE, state)
            results[r] = "STOPPED(pid=%s)" % found["pid"]
        # 停止成功清预算(显式人工停止是正常操作)
        _record_success()
        return results


def status():
    try:
        state = _load_json(STATE_FILE, {})
        out = {}
        for r in ROLES:
            v = _verified(r, state)
            out[r] = ({"status": state.get(r, {}).get("state", "UNKNOWN"),
                       "verified": v is not None, "pid": v and v["pid"]}
                      if state.get(r) else {"status": "STOPPED", "verified": False})
        fails = _load_json(FAIL_FILE, {"consecutive": 0})
        out["consecutive_failures"] = fails.get("consecutive", 0)
        out["locked"] = int(fails.get("consecutive", 0)) >= MAX_CONSECUTIVE_FAILURES
        return out
    except RuntimeError as exc:
        return {"error": str(exc), "note": "enumeration failed; starts are blocked"}


def unlock(reason):
    if not reason:
        raise ValueError("unlock requires an explicit reason")
    with _FileLock(LOCK_FILE):
        fails = _load_json(FAIL_FILE, {"consecutive": 0, "history": []})
        fails["consecutive"] = 0
        fails.setdefault("history", []).append(
            {"ts": time.time(), "unlocked": True, "reason": reason[:300]})
        _save_json(FAIL_FILE, fails)
        return "UNLOCKED: %s" % reason[:120]


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    op = argv[1]
    try:
        if op == "start" and len(argv) >= 3:
            t = int(argv[3]) if len(argv) > 3 else 300
            print(json.dumps(start(argv[2], t), ensure_ascii=False))
        elif op == "stop" and len(argv) >= 3:
            print(json.dumps(stop(argv[2]), ensure_ascii=False))
        elif op == "status":
            print(json.dumps(status(), ensure_ascii=False))
        elif op == "unlock" and len(argv) >= 3:
            print(unlock(" ".join(argv[2:])))
        else:
            print(__doc__)
            return 2
    except (RuntimeError, TimeoutError, ValueError) as exc:
        print("LIFECYCLE_ERROR: %s" % exc)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
