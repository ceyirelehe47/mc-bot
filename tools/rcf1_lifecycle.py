# -*- coding: utf-8 -*-
"""MC-RCF-1-R1 唯一生命周期入口:本机最多 1 测试服务器 + 1 Bob 客户端。

R1-L 修复(相对上轮):
- L2/L03 启动意图预写:intents/<role>.json 原子落盘先于 Popen;后继 start
  先核对 intents + 全命名空间 marker 扫描,不只查 state 里的单一 PID。
  管理器在 spawn 前/后、登记前崩溃,后继只能接管/清理/阻断,不重复启动。
- L3/L04 状态 fail-closed:损坏/截断/权限失败上抛 CorruptStateError,
  绝不吞成空环境;只有文件不存在且全枚举健康才允许初始化。
- L4/L07/L08 停止语义:STOPPING 期望状态先落盘;STOP_FAILED 保留记录与
  预算;只有全部角色确认退出才 _record_success。启动超时但进程活着
  → 有界清理,不创建第二个。
- L5 命名空间:RCF1_LIFECYCLE_NS_ROOT 重定向 state/lock/intents/logs,
  假测试绝不触碰真实预算/进程/凭证。RCF1_LIFECYCLE_FAKE 切换轻量后端。
身份:PID + 创建时间 + 命令行 marker 三核验;停止按 marker 全量定位,
绝不按进程名批杀。所有写操作持跨进程文件锁(msvcrt)。
"""
import argparse
import json
import os
import pathlib
import re
import secrets
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
# R3C/D2:RCF1_ROOT 切换 fresh replay 部署目录;默认既有受管环境。
ROOT = os.environ.get("RCF1_ROOT", r"D:\code\mc-experiment")
_R3C = bool(os.environ.get("RCF1_ROOT"))
_SUFFIX = "rcf1-server-r3c" if _R3C else "rcf1-server"
_CSUFFIX = "rcf1-client-r3c" if _R3C else "rcf1-client"

NS_ROOT = os.environ.get("RCF1_LIFECYCLE_NS_ROOT") or r"D:\mc-rcf1-raw\lifecycle"
STATE_DIR = pathlib.Path(NS_ROOT)
LOCK_FILE = STATE_DIR / "lifecycle.lock"
STATE_FILE = STATE_DIR / "state.json"
FAIL_FILE = STATE_DIR / "failures.json"
INTENT_DIR = STATE_DIR / "intents"
LOG_DIR = STATE_DIR / "logs"

SERVER_DIR = os.path.join(ROOT, _SUFFIX)
CLIENT_DIR = os.path.join(ROOT, _CSUFFIX)
JAVA = r"D:\mc-server\jdk-21.0.12.1+1\bin\java.exe"
CMD_TEMPLATE = os.path.join(ROOT, "mc2a07a-work", "drivers", "prod_client_cmd.json")
SECRETS = os.path.join(REPO, ".secrets")

GAME_PORT = 25599
BRIDGE_PORT = 8799
CONTROL_PORT = 8798
RCON_PORT = 25598
SERVER_READY_MARK = "external-body bridge bound to loopback port %d" % BRIDGE_PORT
MAX_CONSECUTIVE_FAILURES = 3
FAKE_BACKEND = bool(os.environ.get("RCF1_LIFECYCLE_FAKE"))
# 故障注入开关(仅假测试命名空间使用;真实环境不得设置)
CIM_FAIL = os.environ.get("RCF1_LIFECYCLE_CIM_FAIL") == "1"
STOP_FAIL = os.environ.get("RCF1_LIFECYCLE_STOP_FAIL") == "1"

ROLES = ("server", "client")


class CorruptStateError(RuntimeError):
    """状态损坏/不可读:fail-closed;不得当作空环境。"""


class InstanceConflict(RuntimeError):
    """命名空间内发现无法归属的实例:拒绝启动,不动它。"""


def _secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as fh:
        return fh.read().strip()


# ---------- OS 级互斥 ----------

class _FileLock:
    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.fd = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.fd = os.open(str(self.path), os.O_RDWR | os.O_CREAT)
        import msvcrt
        deadline = time.time() + 60
        while True:
            try:
                msvcrt.locking(self.fd, msvcrt.LK_NBLCK, 1)
                return self
            except OSError:
                if time.time() > deadline:
                    os.close(self.fd)
                    self.fd = None
                    raise TimeoutError("lifecycle lock busy >60s")
                time.sleep(0.2)

    def __exit__(self, *exc):
        if self.fd is not None:
            import msvcrt
            try:
                msvcrt.locking(self.fd, msvcrt.LK_UNLCK, 1)
            except OSError:
                pass
            os.close(self.fd)
            self.fd = None


# ---------- 进程事实查询 ----------

def _cim_processes():
    """java/javaw/python 进程表 {pid: (creation_ms, cmdline)}。失败上抛。"""
    if CIM_FAIL:
        raise RuntimeError("process enumeration failed (injected)")
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-CimInstance Win32_Process | Where-Object { $_.Name -match "
         "'^(java|javaw|python)' } | ForEach-Object { "
         "\"$($_.ProcessId)`t$($_.CreationDate)`t$($_.CommandLine)\" }"],
        capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError("process enumeration failed rc=%s" % out.returncode)
    procs = {}
    for line in (out.stdout or "").splitlines():
        parts = line.split("\t")
        if len(parts) != 3 or not parts[0].isdigit():
            continue
        procs[int(parts[0])] = (parts[1], parts[2])
    if not procs:
        # 全空输出对生产机不可信,但当 CIM 只返回零行且 rc=0 时允许
        # (纯离线诊断机)。配合 marker 扫描二次确认。
        pass
    return procs


def _norm_creation(cim_date):
    if not cim_date:
        return ""
    s = re.sub(r"[^0-9]", "", cim_date.split(".")[0])
    try:
        return int(s)
    except ValueError:
        return ""


def find_by_marker(marker, procs=None):
    """按命令行 marker 定位;返回 {pid, created} 或 None。"""
    procs = procs if procs is not None else _cim_processes()
    tag = "rcf1.instance.marker=%s" % marker
    for pid, (created, cmdline) in procs.items():
        if tag in (cmdline or ""):
            return {"pid": pid, "created": _norm_creation(created),
                    "cmdline": cmdline}
    return None


def scan_namespace(prefix="rcf1-%s-" % "server"):
    """R1-L L2: 全命名空间 marker 扫描。返回 {role: [ {pid, marker, created} ]}。
    发现 state 之外的实例 = 冲突(接管/清理/阻断,不重复启动)。"""
    out = {r: [] for r in ROLES}
    pref = _marker_prefix()
    procs = _cim_processes()
    for pid, (created, cmdline) in procs.items():
        m = re.search(r"rcf1\.instance\.marker=%s(server|client)-([0-9a-f]+)"
                      % re.escape(pref), cmdline or "")
        if m:
            out[m.group(1)].append(
                {"pid": pid,
                 "marker": "%s%s-%s" % (pref, m.group(1), m.group(2)),
                 "created": _norm_creation(created)})
    return out
def server_port_owner():
    raw = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "(Get-NetTCPConnection -LocalPort %d -State Listen "
         "-ErrorAction SilentlyContinue | Select-Object -First 1)"
         ".OwningProcess" % GAME_PORT],
        capture_output=True, text=True, timeout=20)
    s = (raw.stdout or "").strip()
    return int(s) if s.isdigit() else None


# ---------- 状态读写(严格 + 原子) ----------

def _load_json_strict(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return None
    except (json.JSONDecodeError, OSError, ValueError) as exc:
        raise CorruptStateError("%s unreadable/corrupt: %r" % (path, exc))


def _save_json(path, obj):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = str(path) + ".tmp.%d" % os.getpid()
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, indent=1)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, str(path))


def _load_budget():
    """R2/R06:预算按角色分账。旧全局 schema 遗留未结失败无法归因,
    保守迁入 unattributed 桶双向阻塞;历史原样保留,不换名重记。"""
    raw = _load_json_strict(FAIL_FILE)
    if raw is None:
        return {"roles": {}, "history": []}
    if "roles" in raw:
        return raw
    legacy = int(raw.get("consecutive", 0) or 0)
    migrated = {"roles": {},
                "history": list(raw.get("history", []))}
    if legacy > 0:
        migrated["roles"]["unattributed"] = {"consecutive": legacy}
    return migrated


def _role_consecutive(budget, role):
    return int((budget.get("roles", {}).get(role) or {})
               .get("consecutive", 0))


def _record_failure(role, reason):
    """R2/R06:失败预算绑定角色+恢复链;另一角色成功不得清零。"""
    budget = _load_budget()
    entry = budget.setdefault("roles", {}).setdefault(
        role, {"consecutive": 0})
    entry["consecutive"] = int(entry.get("consecutive", 0)) + 1
    budget.setdefault("history", []).append(
        {"ts": time.time(), "role": role, "reason": reason[:300]})
    budget["total_failures"] = int(budget.get("total_failures", 0)) + 1
    _save_json(FAIL_FILE, budget)
    return entry["consecutive"]


def _record_success(role):
    budget = _load_budget()
    entry = budget.get("roles", {}).get(role)
    if entry and entry.get("consecutive"):
        entry["consecutive"] = 0
        _save_json(FAIL_FILE, budget)


def _check_budget(role):
    budget = _load_budget()
    n = max(_role_consecutive(budget, role),
            _role_consecutive(budget, "unattributed"))
    if n >= MAX_CONSECUTIVE_FAILURES:
        raise RuntimeError(
            "BLOCKED: role %s has %d consecutive lifecycle failures; "
            "explicit unlock with repair evidence required"
            % (role, n))


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
    cmd = [JAVA, "-Xmx3G",
           "-Drcf1.instance.marker=%s" % marker,
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
    base = json.load(open(CMD_TEMPLATE, encoding="utf-8"))
    cmd = [c.replace("mc2a07-prod-client", _CSUFFIX) for c in base]
    cmd = [c if c != JAVA else JAVA for c in cmd]
    # marker 注入到第一个 -D 参数位(java -D 在 classpath 前)
    for i, c in enumerate(cmd):
        if c.startswith("-D") or c.endswith(".exe") or c.endswith("java"):
            cmd = cmd[:i + 1] + ["-Drcf1.instance.marker=%s" % marker] + cmd[i + 1:]
            break
    return cmd, env, CLIENT_DIR


def _fake_cmd(role, marker):
    """L5: 轻量假后端。长驻 python,打印 marker,可注入慢启动/崩溃。"""
    script = os.path.join(HERE, "_rcf1_fake_proc.py")
    return ([sys.executable, script, "--role", role,
             "-Drcf1.instance.marker=%s" % marker], {}, str(STATE_DIR))


# ---------- spawn:意图预写两阶段 ----------

def _marker_prefix():
    """R1-L L5/L10 + R2:假后端用 NS_ROOT 派生的唯一前缀——不同假测试
    命名空间的进程互不可见(全局 rcf1fake- 前缀会让并行套件的孤儿
    污染彼此的命名空间扫描);真实命名空间仍为 rcf1-。"""
    if not FAKE_BACKEND:
        return "rcf1-"
    import hashlib
    token = hashlib.sha1(
        str(STATE_DIR.resolve()).lower().encode("utf-8")).hexdigest()[:8]
    return "rcf1fake%s-" % token


def _spawn(role, state):
    marker = "%s%s-%s" % (_marker_prefix(), role, secrets.token_hex(8))
    if FAKE_BACKEND:
        cmd, env, cwd = _fake_cmd(role, marker)
        log_name = "fake-%s.log" % role
    elif role == "server":
        cmd, env, cwd = _server_cmd(marker)
        log_name = "server-stdout.log"
    else:
        cmd, env, cwd = _client_cmd(marker)
        log_name = "client-stdout.log"
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    log_path = LOG_DIR / log_name
    if log_path.exists():
        os.replace(str(log_path), str(log_path.with_suffix(".old")))
    log = open(log_path, "wb")
    merged = dict(os.environ)
    merged.update(env)
    try:
        p = subprocess.Popen(cmd, cwd=cwd, env=merged, stdout=log,
                             stderr=subprocess.STDOUT,
                             creationflags=0x00000008)  # DETACHED
    except Exception:
        # spawn 失败:意图立即失效,清掉,防幽灵意图
        (INTENT_DIR / ("%s.json" % role)).unlink(missing_ok=True)
        raise
    procs = _cim_processes()
    created = _norm_creation(procs.get(p.pid, ("", ""))[0])
    state[role] = {"pid": p.pid, "marker": marker, "created": created,
                   "state": "STARTING", "started_at": time.time()}
    _save_json(STATE_FILE, state)
    return state[role]


def _clear_intent(role):
    (INTENT_DIR / ("%s.json" % role)).unlink(missing_ok=True)


def _wait_ready(role, state, timeout_s):
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
                tail = (LOG_DIR / ("fake-server.log" if FAKE_BACKEND
                                   else "server-stdout.log")).read_bytes()[-20000:]
                mark = (b"FAKE_READY" if FAKE_BACKEND
                        else SERVER_READY_MARK.encode())
                if mark in tail:
                    return True
            except OSError:
                pass
        else:
            if FAKE_BACKEND:
                return True  # 假后端:进程活着即就绪
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


def _verified(role, state, procs=None):
    """三核验:PID 存活 + 创建时间 + marker。失败/冲突上抛或 None。"""
    rec = state.get(role) or {}
    pid = rec.get("pid")
    marker = rec.get("marker")
    if not pid or not marker:
        return None
    procs = procs if procs is not None else _cim_processes()
    if pid not in procs:
        return None
    created, cmdline = procs[pid]
    if marker not in (cmdline or ""):
        return None
    claimed = rec.get("created")
    created_n = _norm_creation(created)
    if claimed and created_n and int(claimed) != created_n:
        return None
    return {"pid": pid,
            "claimed_created": claimed or created_n, "state": rec.get("state")}


def _preflight(role):
    """L2/L04/L05 + R2/R06:启动前全命名空间核对。
    返回 (state, verified)。发现未知/冲突实例时抛 InstanceConflict。"""
    state = _load_json_strict(STATE_FILE)
    if state is None:
        state = {}
    try:
        v = _verified(role, state)
    except CorruptStateError:
        raise
    if v is not None:
        # R2/R06:已验证一个合法记录 ≠ 配额合规——仍须扫描本轮同角色
        # 额外实例(不同 marker = 竞争写入者),不能在 _verified 成功后
        # 提前认为全机只有这一套(审查点名)。
        rec_marker = (state.get(role) or {}).get("marker")
        live = scan_namespace()
        extra = [x for x in (live.get(role) or [])
                 if x.get("marker") != rec_marker]
        if extra:
            raise InstanceConflict(
                "extra %s instance(s) beyond verified record: %s; "
                "stop them before starting"
                % (role, [x["pid"] for x in extra]))
        return state, v
    try:
        v = _verified(role, state)
    except CorruptStateError:
        raise
    if v is not None:
        return state, v
    # 无 state 记录实例:未决意图?
    intent_p = INTENT_DIR / ("%s.json" % role)
    intent = _load_json_strict(intent_p) if intent_p.exists() else None
    live = scan_namespace()
    role_live = live.get(role) or []
    other_live = [x for r in ROLES if r != role for x in (live.get(r) or [])]
    if role_live:
        # state 没有但进程在:接管判断——单一实例才允许接管登记
        if len(role_live) == 1 and intent and role_live[0]["marker"] == intent.get("marker"):
            # 崩溃窗口恢复:接管原实例,不启动第二个
            rec = state.get(role) or {}
            state[role] = {"pid": role_live[0]["pid"],
                           "marker": role_live[0]["marker"],
                           "created": role_live[0]["created"],
                           "state": "ADOPTED", "started_at": time.time()}
            _save_json(STATE_FILE, state)
            return state, _verified(role, state)
        raise InstanceConflict(
            "untracked %s instance(s) in namespace: %s; adopt/clear manually"
            % (role, [x["pid"] for x in role_live]))
    # 本角色干净。意图残留但无进程 = 崩溃于 spawn 前 → 意图失效,清掉
    if intent:
        _clear_intent(role)
    return state, None


def start(role, timeout_s=300):
    if role not in ROLES:
        raise ValueError("role must be server|client")
    with _FileLock(LOCK_FILE):
        _check_budget(role)
        state, v = _preflight(role)
        if v is not None:
            rec = state[role]
            if rec.get("state") == "STARTING":
                _clear_intent(role)
                return {"role": role, "status": "STARTING", "pid": v["pid"],
                        "note": "startup in progress; no second instance"}
            if rec.get("state") == "STOPPING":
                return {"role": role, "status": "STOPPING", "pid": v["pid"],
                        "note": "stop in progress; start refused until confirmed exit"}
            if rec.get("state") == "STOP_FAILED":
                # L7 修复:停止未确认的实例不得被 start 当作 RUNNING 复活。
                _record_failure(role, "start %s refused: STOP_FAILED pid=%s"
                                % (role, v["pid"]))
                raise RuntimeError("%s pid=%s STOP_FAILED (exit unconfirmed); "
                                   "confirm cleanup before restart" % (role, v["pid"]))
            _clear_intent(role)
            return {"role": role, "status": "RUNNING", "pid": v["pid"],
                    "note": "existing verified instance; idempotent start"}
        old = state.get(role) or {}
        if old.get("pid"):
            procs = _cim_processes()
            if int(old["pid"]) in procs and old.get("marker"):
                _record_failure(role, "%s identity mismatch on old pid %s"
                                % (role, old["pid"]))
                raise RuntimeError("%s old pid %s alive but identity mismatch; "
                                   "refusing to launch" % (role, old["pid"]))
        rec = _spawn(role, state)
        try:
            _wait_ready(role, state, timeout_s)
            state = _load_json_strict(STATE_FILE) or {}
            state.setdefault(role, rec)
            state[role]["state"] = "RUNNING"
            _save_json(STATE_FILE, state)
            _clear_intent(role)
            _record_success(role)
            return {"role": role, "status": "RUNNING", "pid": rec["pid"],
                    "marker": rec["marker"]}
        except RuntimeError as exc:
            # 启动失败:进程可能还活着——有界清理,不创建第二个
            found = find_by_marker(rec["marker"])
            if found:
                subprocess.run(["powershell", "-NoProfile", "-Command",
                                "Stop-Process -Id %d -Force" % found["pid"]],
                               capture_output=True)
                for _ in range(15):
                    if not find_by_marker(rec["marker"]):
                        break
                    time.sleep(1)
            _clear_intent(role)
            state = _load_json_strict(STATE_FILE) or {}
            state[role] = {"state": "FAILED_START", "error": str(exc)[:200]}
            _save_json(STATE_FILE, state)
            _record_failure(role, "start %s: %s" % (role, exc))
            raise


def stop(role, timeout_s=90):
    with _FileLock(LOCK_FILE):
        state = _load_json_strict(STATE_FILE)
        if state is None:
            state = {}
        results = {}
        any_failed = False
        roles = ROLES if role == "all" else (role,)
        for r in roles:
            rec = state.get(r) or {}
            marker = rec.get("marker")
            pid = rec.get("pid")
            if not marker or not pid:
                # R2/R06:state 无记录 ≠ 全停——命名空间内仍可能有本轮
                # 孤儿实例(旧 stop 回执不能证明零实例)。可归属(意图
                # marker 匹配)则登记后按正常流程停;不可归属的孤儿
                # BLOCKED,不得伪报 STOPPED,也不误杀。
                live = scan_namespace()
                orphans = live.get(r) or []
                if not orphans:
                    results[r] = "STOPPED(no record; namespace clean)"
                    continue
                intent_p = INTENT_DIR / ("%s.json" % r)
                intent = (_load_json_strict(intent_p)
                          if intent_p.exists() else None)
                adoptable = [o for o in orphans
                             if intent and o["marker"] == intent.get("marker")]
                untracked = [o for o in orphans
                             if o not in adoptable]
                if untracked:
                    _record_failure(
                        r, "stop %s blocked: untracked instances %s"
                        % (r, [o["pid"] for o in untracked]))
                    results[r] = ("BLOCKED(untracked instances: %s; "
                                  "adopt/clear manually)"
                                  % [o["pid"] for o in untracked])
                    any_failed = True
                    continue
                for o in adoptable:
                    state[r] = {"pid": o["pid"], "marker": o["marker"],
                                "created": o["created"], "state": "ADOPTED"}
                    _save_json(STATE_FILE, state)
                rec = state.get(r) or {}
                marker = rec.get("marker")
                pid = rec.get("pid")
                if not marker or not pid:
                    results[r] = "STOPPED(no adoptable record)"
            # L4: 先落 STOPPING 期望状态(阻止并发 start/自动复活)
            state[r] = dict(rec, state="STOPPING")
            _save_json(STATE_FILE, state)
            found = find_by_marker(marker)
            if found is None:
                state[r] = {"state": "STOPPED"}
                _save_json(STATE_FILE, state)
                # MC-RCF-1-R3 F06:no-record path still must VERIFY the
                # role scope is actually zero before claiming STOPPED.
                live = scan_namespace().get(r) or []
                if live:
                    _record_failure(r, "stop %s already-gone but %d "
                                    "untracked instances remain"
                                    % (r, len(live)))
                    results[r] = ("BLOCKED(untracked instances: %s)"
                                  % [o["pid"] for o in live])
                    any_failed = True
                    continue
                results[r] = "STOPPED(already gone; scope verified 0)"
                continue
            if r == "server" and not FAKE_BACKEND:
                try:
                    sys.path.insert(0, HERE)
                    import rcf1_env
                    rcf1_env.rcon("stop")
                except Exception:
                    pass
            deadline = time.time() + timeout_s
            while time.time() < deadline and find_by_marker(marker):
                time.sleep(2)
            if find_by_marker(marker) and not STOP_FAIL:
                subprocess.run(["powershell", "-NoProfile", "-Command",
                                "Stop-Process -Id %d -Force" % found["pid"]],
                               capture_output=True)
                deadline = time.time() + 30
                while time.time() < deadline and find_by_marker(marker):
                    time.sleep(1)
            if find_by_marker(marker):
                _record_failure(r, "stop %s unconfirmed pid=%s" % (r, found["pid"]))
                state = _load_json_strict(STATE_FILE) or state
                state[r] = {"state": "STOP_FAILED", "pid": found["pid"],
                            "marker": marker, "error": "exit unconfirmed"}
                _save_json(STATE_FILE, state)
                results[r] = ("STOP_FAILED(unconfirmed;starts blocked "
                              "until cleared)")
                any_failed = True
                continue
            state = _load_json_strict(STATE_FILE) or state
            state[r] = {"state": "STOPPED"}
            _save_json(STATE_FILE, state)
            # MC-RCF-1-R3 F06:STOPPED requires the WHOLE role scope to be
            # zero — stopping the one recorded PID while a same-role
            # sibling keeps running must not be reported as a full stop.
            remaining = scan_namespace().get(r) or []
            if remaining:
                _record_failure(r, "stop %s recorded pid gone but %d "
                                "same-role instances remain: %s"
                                % (r, len(remaining),
                                   [o["pid"] for o in remaining]))
                results[r] = ("BLOCKED(same-role instances remain: %s)"
                              % [o["pid"] for o in remaining])
                any_failed = True
                continue
            results[r] = ("STOPPED(pid=%s; scope verified 0)"
                          % found["pid"])
        # L7/L08 + R2/R06:stop 永不清失败预算。清零只发生在对应角色
        # start 成功(新实例确证就绪=该角色恢复链真正成功);另一角色
        # 的成功、stop、换 run_id、重开脚本都不能清除未结失败。
        return results


def status():
    try:
        state = _load_json_strict(STATE_FILE)
        if state is None:
            state = {}
        out = {}
        live = scan_namespace()
        for r in ROLES:
            rec = state.get(r) or {}
            v = _verified(r, state, procs=None)
            # R2/R06:status 汇报命名空间内超出已验证记录的同角色实例
            extra = [x["pid"] for x in (live.get(r) or [])
                     if x.get("marker") != rec.get("marker")]
            entry = ({"status": rec.get("state", "UNKNOWN"),
                      "verified": v is not None, "pid": v and v["pid"]}
                     if rec else {"status": "STOPPED", "verified": False})
            if extra:
                entry["namespace_extras"] = extra
            out[r] = entry
        budget = _load_budget()
        out["consecutive_failures"] = {
            r: _role_consecutive(budget, r) for r in ROLES}
        out["unattributed_failures"] = _role_consecutive(
            budget, "unattributed")
        out["locked"] = {
            r: max(_role_consecutive(budget, r),
                   _role_consecutive(budget, "unattributed"))
            >= MAX_CONSECUTIVE_FAILURES for r in ROLES}
        out["intents_pending"] = sorted(
            p.name for p in INTENT_DIR.glob("*.json")) if INTENT_DIR.exists() else []
        return out
    except CorruptStateError as exc:
        return {"error": str(exc),
                "note": "corrupt state; starts are blocked (fail-closed)"}
    except RuntimeError as exc:
        return {"error": str(exc), "note": "enumeration failed; starts are blocked"}


def unlock(reason):
    """显式解锁:需修复依据;保留总历史。"""
    if not reason:
        raise ValueError("unlock requires an explicit reason")
    with _FileLock(LOCK_FILE):
        budget = _load_budget()
        for r in list(budget.get("roles", {})):
            budget["roles"][r] = {"consecutive": 0}
        budget.setdefault("history", []).append(
            {"ts": time.time(), "unlocked": True, "reason": reason[:300]})
        _save_json(FAIL_FILE, budget)
        return "UNLOCKED: %s" % reason[:120]



def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("op", choices=["start", "stop", "status", "unlock"])
    ap.add_argument("role", nargs="?", default="all")
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--reason", default="")
    a = ap.parse_args(argv)
    if a.op == "start":
        print(json.dumps(start(a.role, a.timeout)))
    elif a.op == "stop":
        print(json.dumps(stop(a.role, a.timeout)))
    elif a.op == "unlock":
        print(unlock(a.reason))
    else:
        print(json.dumps(status()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
