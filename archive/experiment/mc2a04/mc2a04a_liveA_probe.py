# -*- coding: utf-8 -*-
"""MC-2A0.4A LIVE-A 探针:duplicate authority 拒绝 + gather typed 409。"""
import json, socket, struct, sys, importlib.util

toks = dict(l.strip().split("=", 1) for l in open(
    r"D:\code\mc-experiment\mc2a04a-tokens.env").read().splitlines() if "=" in l)

def frame(obj):
    body = json.dumps(obj).encode()
    return struct.pack(">i", len(body)) + body

def read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("server closed the connection")
        data += chunk
    return data

def read_frame(sock):
    n = struct.unpack(">i", read_exact(sock, 4))[0]
    return read_exact(sock, n).decode()

hello = {"type": "hello", "protocol": 2, "token": toks["AIBOT_REAL_CLIENT_TOKEN"],
         "body_id": "bob", "player_name": "Bob",
         "session_epoch": "duplicate-authority-probe-uuid-3"}
result = {}
try:
    s = socket.create_connection(("127.0.0.1", 8766), timeout=5)
    s.sendall(frame(hello))
    try:
        resp = read_frame(s)
        result = {"rejected": False, "unexpected_welcome": resp[:100]}
    except (ConnectionError, socket.timeout) as fault:
        result = {"rejected": True, "evidence": str(fault)[:80]}
    finally:
        s.close()
except Exception as outer:
    result = {"rejected": True, "connect_phase": str(outer)[:80]}
print("duplicate authority probe:", json.dumps(result, ensure_ascii=False))
json.dump(result, open(r"D:\code\mc-experiment\mc2a04a-liveA-duplicate-authority.json", "w"))

spec = importlib.util.spec_from_file_location("lv", "D:/code/mc-experiment/mc2a04a_live.py")
lv = importlib.util.module_from_spec(spec); spec.loader.exec_module(lv)
lease = lv.observe_for_lease("mc2a04a-liveA")
g = lv.submit(lease, "gather", {"item": "minecraft:oak_log", "count": 1}, "liveA-gather409")
print("gather:", json.dumps(g, ensure_ascii=False)[:180])
json.dump(g, open(r"D:\code\mc-experiment\mc2a04a-liveA-gather-409.json", "w"))
print("active_execution:", lv.status()["data"].get("active_execution"))
