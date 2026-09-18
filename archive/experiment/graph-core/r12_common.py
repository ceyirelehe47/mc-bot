# -*- coding: utf-8
"""R1.2 LIVE 公共: 隔离服启停/就绪等待/RCON/状态文件。"""
import json, os, subprocess, sys, time

ROOT = r"D:\code\mc-experiment"
SERVER = os.path.join(ROOT, "mc-server-mc1ca")
JAVA = r"D:\mc-server\jdk-21.0.12.1+1\bin\java"
RCON = os.path.join(ROOT, "rcon.py")
STATE = os.path.join(ROOT, "r12_state.json")
SEM = os.path.join(SERVER, "world_play", "aibot", "external-semantics-bob.json")
JOURNAL = os.path.join(SERVER, "world_play", "aibot", "external-body-bob.journal")

READY_MARK = "external-body bridge bound to loopback port 8765"


def rcon(cmd):
    return subprocess.run([sys.executable, RCON, cmd], capture_output=True, text=True, timeout=30).stdout.strip()


def start_server(log_path):
    token = open(os.path.join(SERVER, "bridge-token.txt")).read().strip()
    env = dict(os.environ, AIBOT_EXTERNAL_BOT="Bob", AIBOT_BRIDGE_TOKEN=token, AIBOT_BRIDGE_PORT="8765")
    log = open(log_path, "wb")
    p = subprocess.Popen([JAVA, "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui"],
                         cwd=SERVER, env=env, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 180
    while time.time() < deadline:
        if p.poll() is not None:
            log.close()
            raise RuntimeError(f"server exited early rc={p.returncode}; see {log_path}")
        try:
            if READY_MARK in open(log_path, "rb").read().decode("utf-8", "replace"):
                return p
        except OSError:
            pass
        time.sleep(2)
    log.close()
    raise RuntimeError("server not ready in 180s")


def stop_server(proc, log_path):
    rcon("stop")
    deadline = time.time() + 60
    while time.time() < deadline and proc.poll() is None:
        time.sleep(2)
    if proc.poll() is None:
        proc.kill()
        proc.wait()
    time.sleep(3)


def save_state(**kw):
    st = {}
    if os.path.exists(STATE):
        st = json.load(open(STATE, encoding="utf-8"))
    st.update(kw)
    json.dump(st, open(STATE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


def load_state():
    return json.load(open(STATE, encoding="utf-8"))


def wait_quiet(path, tries=10):
    """等语义文件 mtime 稳定(持久化静默)。"""
    last = -1
    for _ in range(tries):
        m = os.path.getmtime(path)
        if m == last:
            return
        last = m
        time.sleep(1.5)
